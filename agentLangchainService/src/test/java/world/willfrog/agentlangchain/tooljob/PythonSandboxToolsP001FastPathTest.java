package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    private SqlSession currentSession;

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
                    tool_job_anchor_json JSONB DEFAULT '{}'
                )""");
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
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        // Clean Redis
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        insertRun(RUN_ID, "EXECUTING");

        // Real dispatch store backed by PG + real Redis
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, new ToolJobConfig());
        dispatchStore = spy(new PythonSandboxDispatchStoreImpl(anchorService, redisCache));

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

        fixtureDataset();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    @Test
    void fastPathCompletesSynchronouslyPersistsAnchorAndClearsAfterAcknowledgement() throws Exception {
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        // dispatchStore is a real spy — let it persist to PG (no stubs)
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest req = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-p001")
                    .setRequestFingerprint(req.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-p001").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("ok").setDatasetDir("/sandbox/input")
                .setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        // Execute — goes through full executeDataIntense fast-path
        String output = tools.executePython("print(1)", "1", null, null, 30);

        // Oracle 1: output
        assertThat(output).contains("\"ok\":true").contains("\"stdout\":\"ok\"");

        // Oracle 2: capacity release via terminal proof
        verify(capacity).releaseReservation(argThat(req ->
                req.proof() instanceof DataAnalysisReleaseProof.Terminal));

        // Oracle 3: usage upsert
        verify(recorder).upsert(argThat(env ->
                !env.background() && !env.retryable()
                        && "SUCCEEDED".equals(env.terminalStatus())));

        // Oracle 4: no clearActive during executePython (done later by ToolRouter)
        verify(dispatchStore, never()).clearActive(anyString(), anyString());

        // Oracle 5: no transferToPending (fast path succeeds)
        verify(dispatchStore, never()).transferToPending(anyString(), any());

        // Oracle 6: anchor persisted with terminal data via real PG
        ToolJobAnchorService verifyAnchor = new ToolJobAnchorService(newMapper());
        ToolJobAnchor dbAnchor = verifyAnchor.loadAnchor(RUN_ID);
        assertThat(dbAnchor).isNotNull();
        assertThat(dbAnchor.getAnchorState()).isEqualTo("TERMINAL");
        assertThat(dbAnchor.getFinalizerStep()).isEqualTo("USAGE");
        assertThat(dbAnchor.isUsagePersisted()).isTrue();
        assertThat(dbAnchor.getResumeState()).isNull();

        // Oracle 7: clearActive (simulating ToolRouter acknowledgement)
        String operationId = RUN_ID + ":" + TOOL_CALL_ID + ":1";
        boolean cleared = dispatchStore.clearActive(RUN_ID, operationId);
        assertThat(cleared).isTrue();

        // Oracle 8: after clearActive, anchor cleared to {} (operationId null)
        // Use fresh mapper to avoid MyBatis session cache
        ToolJobAnchorService verifyAfterClear = new ToolJobAnchorService(newMapper());
        ToolJobAnchor afterClear = verifyAfterClear.loadAnchor(RUN_ID);
        assertThat(afterClear).isNotNull();
        assertThat(afterClear.getOperationId()).isNull();
        assertThat(afterClear.getFinalizerStep()).isNull();
        assertThat(afterClear.getResumeState()).isNull();

        // Oracle 9: run still EXECUTING
        assertThat(verifyAfterClear.loadAnchor(RUN_ID)).isNotNull(); // anchor is {} but row exists
        AgentRunMapper runMapper = newMapper();
        assertThat(runMapper.findById(RUN_ID).getStatus()).isEqualTo(AgentRunStatus.EXECUTING);

        // Oracle 10: no resume/Redis pending (fast path never sets resume)
        assertThat(dbAnchor.getResumeState()).isNull();
    }

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
        return session.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) VALUES (?, ?, '{}'::jsonb)")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.executeUpdate();
        }
    }
}
