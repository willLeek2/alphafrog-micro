package world.willfrog.agentlangchain.tooljob;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * P0-09 reverse: status terminal, result unavailable -- no finalizer call.
 * <p>
 * Production gap: {@link ToolJobReconciler#fetchResult(String, String, String)}
 * catches exceptions and returns {@code null}. When {@code resultResp == null},
 * {@link ToolJobReconciler#processItem(String)} does NOT call the finalizer --
 * it only updates {@code nextPollAt}, persists the anchor, re-enqueues in the
 * due ZSET, and returns. The anchor stays PENDING and the reservation stays
 * occupied (capacity is never released).
 * <p>
 * Fixture:
 * <ol>
 *   <li>Seed a run with status=WAITING_TOOL_JOB and a PENDING anchor with
 *       reservation, taskId, and nextPollAt in the past</li>
 *   <li>Write the run into the Redis due ZSET</li>
 *   <li>Stub {@code sandboxService.getTaskStatus()} -- returns SUCCEEDED</li>
 *   <li>Stub {@code sandboxService.getTaskResult()} -- throws RuntimeException</li>
 *   <li>Call {@code reconciler.reconcileFromDue()}</li>
 * </ol>
 * <p>
 * Oracles:
 * <ol>
 *   <li>{@code finalizer.handleTerminal} called 0 times</li>
 *   <li>Anchor state still PENDING (no state change)</li>
 *   <li>{@code resultFetchState} still null</li>
 *   <li>Reservation JSON preserved (capacity not released)</li>
 *   <li>{@code nextPollAt} moved forward (rescheduled for later retry)</li>
 * </ol>
 */
@Testcontainers
class ToolJobReconcilerP009ReverseTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "run-p009";
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";

    // Redis infra -- shared across tests
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    // MyBatis -- per-test session
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession currentSession;

    private AgentRunMapper mapper;
    private ToolJobRedisCache redisCache;
    private ToolJobReconciler reconciler;
    private ToolJobFinalizer finalizerSpy;

    // ---- Infrastructure lifecycle ----

    @BeforeAll
    static void setUpInfra() throws Exception {
        // PostgreSQL schema
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE alphafrog_agent_run (
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

        // Redis connection via Lettuce
        redisConnectionFactory = new LettuceConnectionFactory(
                redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownInfra() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    // ---- Per-test lifecycle ----

    @BeforeEach
    void setUp() throws Exception {
        // Clean PostgreSQL
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }

        // Clean Redis
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // MyBatis mapper
        mapper = newMapper();

        // Real services
        ToolJobConfig config = new ToolJobConfig();
        redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);

        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        // Capacity service stub (never invoked in this test, but needed for
        // ToolJobFinalizer construction)
        DataAnalysisCapacityService capacityService = new DataAnalysisCapacityService() {
            @Override
            public DataAnalysisReservation reserve(DataAnalysisOperationIdentity i, DataAnalysisEstimate e) {
                return null;
            }
            @Override
            public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation r) {
                return DataAnalysisRestoreOutcome.CONFLICT;
            }
            @Override
            public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest r) {
                return DataAnalysisReleaseOutcome.CONFLICT;
            }
            @Override
            public DataAnalysisCapacityRecoveryReport recover(
                    List<DataAnalysisReservation> dr, int cmu, int cmha) {
                return null;
            }
            @Override
            public DataAnalysisAdmissionState admissionState() {
                return DataAnalysisAdmissionState.OPEN;
            }
        };

        // Real ToolJobFinalizer wrapped in a spy so we can verify handleTerminal
        // was never called
        ToolJobFinalizer realFinalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService, config);
        finalizerSpy = spy(realFinalizer);

        // Create the reconciler with the spy finalizer (5-arg constructor)
        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizerSpy, resumeService, config);

        // Inject a stub PythonSandboxService via reflection since the field is
        // @DubboReference-injected (not a constructor parameter)
        injectSandboxStub(reconciler);
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    // ---- Test ----

    @Test
    void shouldNotCallFinalizerWhenResultUnavailable() throws Exception {
        // Step 1: Seed -- insert run with WAITING_TOOL_JOB status and a PENDING anchor
        Instant originalNextPollAt = Instant.now().minusSeconds(60); // in the past

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(RUN_ID + ":tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p009");
        anchor.setAutoResume(true);
        anchor.setReservationJson(
                "{\"reservationId\":\"res-p009\",\"identity\":{\"operationId\":\"" + RUN_ID + ":tc-1:1"
                        + "\",\"runId\":\"" + RUN_ID + "\",\"toolCallId\":\"tc-1\",\"attempt\":1,"
                        + "\"reservationId\":\"res-p009\"},"
                        + "\"resourceClass\":\"CPU\",\"capacityUnits\":1,"
                        + "\"state\":\"PENDING\",\"taskId\":\"task-p009\","
                        + "\"acquiredAt\":\"" + Instant.now().minusSeconds(120) + "\"}");
        anchor.setNextPollAt(originalNextPollAt);
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(RUN_ID, "WAITING_TOOL_JOB", anchor.toJson());

        // Step 2: Write into Redis due ZSET with nextPollAt in the past
        redisCache.upsertDue(RUN_ID, anchor);

        // Precondition: run is in the due ZSET
        Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
        assertThat(score).isNotNull();
        assertThat(score.longValue()).isLessThanOrEqualTo(System.currentTimeMillis());

        // Precondition: anchor loaded from DB is PENDING with reservation
        AgentRun run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getResultFetchState()).isNull();

        // Step 3: Call reconcileFromDue -- this triggers processItem which:
        //   a) loadAnchor
        //   b) getTaskStatus -> SUCCEEDED
        //   c) fetchResult -> getTaskResult throws -> returns null
        //   d) resultResp == null -> reschedule (no finalizer call)
        reconciler.reconcileFromDue();

        // ---- Oracles ----

        // Oracle 1: handleTerminal was NEVER called (0 times)
        verify(finalizerSpy, never()).handleTerminal(
                any(String.class), any(ToolJobAnchor.class), any(String.class),
                any(TaskResultResponse.class), anyBoolean());

        // Oracle 2: Anchor state still PENDING (no state change)
        run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Oracle 3: resultFetchState still null
        assertThat(dbAnchor.getResultFetchState()).isNull();

        // Oracle 4: Reservation JSON preserved (capacity NOT released)
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getReservationJson()).contains("res-p009");

        // Oracle 5: nextPollAt moved forward (rescheduled for later retry)
        assertThat(dbAnchor.getNextPollAt()).isNotNull();
        assertThat(dbAnchor.getNextPollAt()).isAfter(originalNextPollAt);

        // Oracle 6: run is re-enqueued in the due ZSET (upsertDue called with
        //           the new nextPollAt, so ZSET now has a future score)
        score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
        assertThat(score).isNotNull();

        // Oracle 7: finalizer step is still null (no finalizer progress)
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Oracle 8: terminal status fields remain null (no terminal processing)
        assertThat(dbAnchor.getTerminalStatus()).isNull();
        assertThat(dbAnchor.getTerminalAt()).isNull();
    }

    // ---- Helpers ----

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newMapper() throws Exception {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                    .build(config);
        }
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status, String anchorJson)
            throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) "
                             + "VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    /**
     * Injects a stub {@link PythonSandboxService} into the reconciler via
     * reflection. The sandboxService field is normally injected by Dubbo's
     * {@code @DubboReference} annotation, but in tests we provide our own stub.
     */
    private static void injectSandboxStub(ToolJobReconciler reconciler) throws Exception {
        Field field = ToolJobReconciler.class.getDeclaredField("sandboxService");
        field.setAccessible(true);
        field.set(reconciler, new StubPythonSandboxService());
    }

    /**
     * Stub sandbox service:
     * <ul>
     *   <li>{@code getTaskStatus} -- returns SUCCEEDED (terminal status)</li>
     *   <li>{@code getTaskResult} -- throws RuntimeException (result unavailable)</li>
     *   <li>All other methods -- return default/empty instances (never called)</li>
     * </ul>
     */
    private static class StubPythonSandboxService implements PythonSandboxService {

        @Override
        public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
            return TaskStatusResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus("SUCCEEDED")
                    .build();
        }

        @Override
        public CompletableFuture<TaskStatusResponse> getTaskStatusAsync(GetTaskStatusRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
            throw new RuntimeException("Result temporarily unavailable (sandbox eviction / transient error)");
        }

        @Override
        public CompletableFuture<TaskResultResponse> getTaskResultAsync(GetTaskResultRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public ExecuteResponse createTask(ExecuteRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<ExecuteResponse> createTaskAsync(ExecuteRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public GetTaskByOperationIdResponse getTaskByOperationId(GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<GetTaskByOperationIdResponse> getTaskByOperationIdAsync(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }
    }
}
