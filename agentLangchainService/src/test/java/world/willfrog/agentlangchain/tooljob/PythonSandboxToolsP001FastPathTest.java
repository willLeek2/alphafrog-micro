package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunEventRedisStore;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.DataAnalysisObservabilityService;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * P0-01: Real fast-path production test using PythonSandboxTools.executePython
 * with real PG-backed PythonSandboxDispatchStoreImpl.
 *
 * <p>Follows the exact fixture pattern from PythonSandboxToolsDataIntenseTest
 * with PG Testcontainers added for real anchor persistence verification.
 */
@Testcontainers
class PythonSandboxToolsP001FastPathTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TempDir Path tempDir;

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "run-test";
    private static final String TOOL_CALL_ID = "call-1";

    private static SqlSessionFactory sqlSessionFactory;
    private final List<SqlSession> openSessions = new ArrayList<>();

    private PythonSandboxTools tools;
    private PythonSandboxService sandbox;
    private DataAnalysisCapacityService capacity;
    private PythonSandboxDispatchStore dispatchStore;
    private DataAnalysisTerminalRecorder recorder;
    private AgentRunDatasetRegistry registry;

    @BeforeAll
    static void setUpInfra() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    status VARCHAR(32) NOT NULL,
                    current_step INT DEFAULT 0,
                    max_steps INT DEFAULT 20,
                    plan_json JSONB DEFAULT '{}',
                    snapshot_json JSONB DEFAULT '{}',
                    last_error TEXT,
                    ttl_expires_at TIMESTAMPTZ,
                    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ,
                    ext JSONB DEFAULT '{}',
                    tool_job_anchor_json JSONB DEFAULT '{}',
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                    plan_generation INT NOT NULL DEFAULT -1,
                    run_control_version BIGINT NOT NULL DEFAULT 0
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run_event (
                    id BIGSERIAL PRIMARY KEY,
                    run_id VARCHAR(64) NOT NULL,
                    seq INT NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    payload_json JSONB DEFAULT '{}',
                    dedupe_key VARCHAR(256),
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_event_dedupe
                ON alphafrog_agent_run_event (run_id, dedupe_key)
                WHERE dedupe_key IS NOT NULL""");
        }
        // Redis
        redisConnectionFactory = new LettuceConnectionFactory(
                redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownInfra() {
        if (redisConnectionFactory != null) redisConnectionFactory.destroy();
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run_event");
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        // Clean Redis
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        insertRun(RUN_ID, "EXECUTING");

        // Real dispatch store backed by PG + real Redis
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobConfig toolJobConfig = new ToolJobConfig();
        toolJobConfig.setDurableRecoveryEnabled(true);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, toolJobConfig);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolJobContinuationTracker> trackerProvider = mock(ObjectProvider.class);
        dispatchStore = spy(new PythonSandboxDispatchStoreImpl(
                anchorService, redisCache, toolJobConfig, trackerProvider,
                new world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry())));

        // PythonSandboxTools with real dispatch store (rest mocked per existing pattern)
        tools = new PythonSandboxTools(om);
        sandbox = mock(PythonSandboxService.class);
        capacity = mock(DataAnalysisCapacityService.class);
        recorder = mock(DataAnalysisTerminalRecorder.class);
        registry = mock(AgentRunDatasetRegistry.class);
        inject(tools, "pythonSandboxService", sandbox);
        inject(tools, "agentRunDatasetRegistry", registry);
        inject(tools, "dataAnalysisCapacityService", capacity);
        inject(tools, "dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        inject(tools, "pythonSandboxDispatchStore", dispatchStore);
        inject(tools, "dataAnalysisTerminalRecorder", recorder);
        inject(tools, "fastPathMs", 5000L);

        AgentContext.setRunId(RUN_ID);
        AgentContext.setToolCallId(TOOL_CALL_ID);
        AgentContext.setTodoContext("todo-1", 1);
        AgentContext.setWorkflow("linear");

        fixtureDataset();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        for (SqlSession s : openSessions) {
            try { s.close(); } catch (Exception ignored) {}
        }
        openSessions.clear();
    }



    // ===== P0-01 second half: real ToolRouterToolExecutor appendOnce → clear =====


    private static void injectEventServiceFields(AgentRunEventService svc) throws Exception {
        java.lang.reflect.Field ttlField = AgentRunEventService.class.getDeclaredField("ttlMinutes");
        ttlField.setAccessible(true);
        ttlField.set(svc, 60);
        java.lang.reflect.Field ittlField = AgentRunEventService.class.getDeclaredField("interruptedTtlDays");
        ittlField.setAccessible(true);
        ittlField.set(svc, 7);
        java.lang.reflect.Field cvField = AgentRunEventService.class.getDeclaredField("checkpointVersion");
        cvField.setAccessible(true);
        cvField.set(svc, "v1");
        java.lang.reflect.Field pcField = AgentRunEventService.class.getDeclaredField("payloadMaxChars");
        pcField.setAccessible(true);
        pcField.set(svc, 50000);
        java.lang.reflect.Field ppField = AgentRunEventService.class.getDeclaredField("payloadPreviewChars");
        ppField.setAccessible(true);
        ppField.set(svc, 500);
    }

    // ===== P0-07: EVENT step crash → PG reload → re-entry → dedup =====


    /** Build anchor with ENVELOPE+RELEASE+USAGE steps already done */
    private ToolJobAnchor buildTerminalAnchorWithUsagesDone() throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(RUN_ID + ":" + TOOL_CALL_ID + ":1");
        anchor.setToolCallId(TOOL_CALL_ID);
        anchor.setAttempt(1);
        anchor.setTaskId("task-p007");
        anchor.setAnchorState("TERMINAL");
        anchor.setFinalizerStep("USAGE");
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalAt(Instant.now());
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(buildReleasedJson(RUN_ID, TOOL_CALL_ID, 1, "task-p007"));
        return anchor;
    }

    private static String buildReleasedJson(String runId, String tcId, int attempt, String taskId) throws Exception {
        DataAnalysisOperationIdentity id = new DataAnalysisOperationIdentity(runId, tcId, attempt);
        DataAnalysisReservation r = new DataAnalysisReservation(
                id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.RELEASED, taskId, Instant.now());
        return om.writeValueAsString(r);
    }

    // ===== P0-12: real DataAnalysisObservabilityService.upsert with partial attribution (cpuMillis=null) =====


    // ===== P0-10: crash → startup recovery → real launcher + handoff + durable clear =====


    // ===== P0-11: public reconciler.reconcileFromDue with OOM + real hooks =====


    // ---- Helpers (exact pattern from PythonSandboxToolsDataIntenseTest) ----

    private void fixtureDataset() throws Exception {
        Path csv = tempDir.resolve("prices.csv");
        Files.writeString(csv, "ts_code,close\n600000.SH,10\n600001.SH,11\n");
        Path meta = tempDir.resolve("prices.meta.json");
        Files.writeString(meta, "{\"rowCount\":2,\"bytes\":" + Files.size(csv)
                + ",\"columns\":[\"ts_code\",\"close\"]}");
        AgentRunDatasetEntry dataset = AgentRunDatasetEntry.forDataset(
                1, "ds-1", csv.toString(), "600000.SH", "prices.csv");
        when(registry.snapshot(RUN_ID)).thenReturn(
                new AgentRunDatasetSnapshot(List.of(dataset), List.of()));
        when(registry.listDatasetNumbers(RUN_ID)).thenReturn(List.of(1));
        when(registry.listManifestNumbers(RUN_ID)).thenReturn(List.of());
        when(registry.findDatasetByNumber(RUN_ID, 1)).thenReturn(java.util.Optional.of(dataset));
    }

    private DataAnalysisReservation preparingReservation() {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(RUN_ID, TOOL_CALL_ID, 1);
        return new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
    }

    private SandboxResourceUsage completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(1).setMemoryPeakBytes(2).setLogicalBytesScanned(3)
                .setQueueWaitMillis(4).setPrepareMillis(5).setExecutionWallMillis(6)
                .setCleanupMillis(7).setDatasetOpenCount(1).setExitReason("SUCCEEDED")
                .setAttributionComplete(true).build();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = PythonSandboxTools.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newMapper() throws Exception {
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        }
        SqlSession session = sqlSessionFactory.openSession(true);
        openSessions.add(session);
        return session.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, user_id, status, tool_job_anchor_json) VALUES (?, ?, ?, '{}'::jsonb)")) {
            ps.setString(1, id);
            ps.setString(2, "user-test");
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    private AgentRunEventMapper newEventMapper() throws Exception {
        if (eventMapperConfigured) {
            SqlSession session = sqlSessionFactory.openSession(true);
            openSessions.add(session);
            return session.getMapper(AgentRunEventMapper.class);
        }
        // Add event mapper to the shared factory
        var configuration = sqlSessionFactory.getConfiguration();
        configuration.addMapper(AgentRunEventMapper.class);
        String res = "mapper/AgentRunEventMapper.xml";
        try (Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    r, configuration, res, configuration.getSqlFragments()).parse();
        }
        eventMapperConfigured = true;
        SqlSession session = sqlSessionFactory.openSession(true);
        openSessions.add(session);
        return session.getMapper(AgentRunEventMapper.class);
    }

    private static boolean eventMapperConfigured = false;
}
