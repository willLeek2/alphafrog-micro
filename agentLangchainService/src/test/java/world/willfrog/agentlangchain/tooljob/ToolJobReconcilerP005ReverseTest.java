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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * P0-05 reverse: cancel with active task -> capacity leak evidence.
 * <p>
 * Production gap: When a run is CANCELED while a tool job is active
 * (WAITING_TOOL_JOB with a PENDING anchor), the reconciler still picks up
 * the run from the due ZSET and calls {@code finalizer.handleTerminal}.
 * However, handleTerminal's ENVELOPE step CAS expects
 * {@link AgentRunStatus#WAITING_TOOL_JOB} status. Since the run is now
 * CANCELED, the CAS fails, the finalizer returns early, capacity is never
 * released, and the reservation stays occupied forever (capacity leak).
 * <p>
 * Path A -- public {@link ToolJobReconciler#reconcileFromDue()}:
 * <ol>
 *   <li>Seed run with status=WAITING_TOOL_JOB, PENDING anchor with
 *       autoResume=true, reservation, and nextPollAt in the past</li>
 *   <li>Write run into Redis due ZSET</li>
 *   <li>Directly update run status to CANCELED via SQL</li>
 *   <li>Stub sandboxService.getTaskStatus() -- returns SUCCEEDED</li>
 *   <li>Stub sandboxService.getTaskResult() -- returns valid result</li>
 *   <li>Call reconciler.reconcileFromDue()</li>
 * </ol>
 * <p>
 * Oracles (Path A):
 * <ol>
 *   <li>Anchor finalizerStep is null in DB (ENVELOPE CAS failed, not persisted)</li>
 *   <li>Capacity NOT released (reservationJson preserved)</li>
 *   <li>Anchor terminalStatus, terminalAt, sandboxTerminalStatus remain null</li>
 *   <li>finalizerSpy.handleTerminal WAS called (reconciler tried)</li>
 *   <li>Anchor still PENDING (no state advancement)</li>
 * </ol>
 * <p>
 * Path B -- direct finalizer (narrow evidence):
 * <ol>
 *   <li>Directly call finalizer.handleTerminal with seed anchor + valid result</li>
 *   <li>Verify anchor object in-memory has finalizerStep=ENVELOPE (set before CAS)</li>
 *   <li>Reload from DB: finalizerStep=null (CAS failed), terminalStatus=null,
 *       capacity unchanged</li>
 * </ol>
 */
@Testcontainers
class ToolJobReconcilerP005ReverseTest {

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
    private static final String RUN_ID = "run-p005";
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

        // Redis via Lettuce
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

        // Capacity service stub (required for ToolJobFinalizer construction)
        DataAnalysisCapacityService capacityService = new DataAnalysisCapacityService() {
            @Override
            public DataAnalysisReservation reserve(DataAnalysisOperationIdentity i,
                                                    DataAnalysisEstimate e) {
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
        // was called (proving the reconciler tried)
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

    // ---- Test: Path A -- reconcileFromDue ----

    @Test
    void shouldFailEnvelopeCasWhenRunIsCanceled() throws Exception {
        // Step 1: Seed -- insert run with WAITING_TOOL_JOB and a PENDING anchor
        Instant originalNextPollAt = Instant.now().minusSeconds(60); // in the past

        String reservationJson = "{\"reservationId\":\"res-p005\","
                + "\"identity\":{\"operationId\":\"" + RUN_ID + ":tc-1:1\","
                + "\"runId\":\"" + RUN_ID + "\",\"toolCallId\":\"tc-1\",\"attempt\":1,"
                + "\"reservationId\":\"res-p005\"},"
                + "\"resourceClass\":\"CPU\",\"capacityUnits\":1,"
                + "\"state\":\"PENDING\",\"taskId\":\"task-p005\","
                + "\"acquiredAt\":\"" + Instant.now().minusSeconds(120) + "\"}";

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(RUN_ID + ":tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p005");
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setNextPollAt(originalNextPollAt);
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(RUN_ID, AgentRunStatus.WAITING_TOOL_JOB.name(), anchor.toJson());

        // Step 2: Write into Redis due ZSET with nextPollAt in the past
        redisCache.upsertDue(RUN_ID, anchor);

        // Precondition: run is in the due ZSET
        Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
        assertThat(score).isNotNull();
        assertThat(score.longValue()).isLessThanOrEqualTo(System.currentTimeMillis());

        // Precondition: run loaded from DB has WAITING_TOOL_JOB status
        AgentRun run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);

        // Precondition: anchor is PENDING with reservation
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(dbAnchor.isAutoResume()).isTrue();
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Step 3: Directly update run status to CANCELED via SQL
        // (simulating cancelRun() without needing all its dependencies)
        updateRunStatus(RUN_ID, AgentRunStatus.CANCELED.name());

        // Step 4: Verify status is CANCELED via JDBC (bypass MyBatis session cache)
        assertThat(queryRunStatus(RUN_ID)).isEqualTo(AgentRunStatus.CANCELED);
        // Reload anchor from fresh JDBC read
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(dbAnchor.isAutoResume()).isTrue();
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getReservationJson()).contains("res-p005");
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Step 5: Call reconcileFromDue -- this triggers processItem which:
        //   a) loadAnchor (finds PENDING anchor with autoResume=true)
        //   b) getTaskStatus -> SUCCEEDED (stub)
        //   c) fetchResult -> valid TaskResultResponse (stub)
        //   d) finalizer.handleTerminal(runId, anchor, "SUCCEEDED", result, true)
        //      -> ENVELOPE step CAS (updateAnchor expects WAITING_TOOL_JOB, but
        //         status is CANCELED so 0 rows updated) -> returns early
        reconciler.reconcileFromDue();

        // ---- Oracles ----

        // Oracle 1: handleTerminal WAS called (reconciler tried)
        verify(finalizerSpy).handleTerminal(
                eq(RUN_ID), any(ToolJobAnchor.class), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(true));

        // Oracle 2: finalizerStep is null in DB (ENVELOPE CAS failed, not persisted)
        run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Oracle 3: terminalStatus, terminalAt remain null
        assertThat(dbAnchor.getTerminalStatus()).isNull();
        assertThat(dbAnchor.getTerminalAt()).isNull();

        // Oracle 4: sandboxTerminalStatus remains null
        assertThat(dbAnchor.getSandboxTerminalStatus()).isNull();

        // Oracle 5: capacity NOT released (reservationJson preserved)
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getReservationJson()).contains("res-p005");

        // Oracle 6: anchor still PENDING (no state advancement past ENVELOPE)
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Oracle 7: terminal result fields remain null
        assertThat(dbAnchor.getTerminalResultPreview()).isNull();
        assertThat(dbAnchor.getTerminalRawRef()).isNull();
        assertThat(dbAnchor.getTerminalErrorCode()).isNull();

        // Oracle 8: resultFetchState still null (no result fetch state recorded)
        assertThat(dbAnchor.getResultFetchState()).isNull();
    }

    // ---- Test: Path B -- direct finalizer (narrow evidence) ----

    @Test
    void shouldFailEnvelopeCasOnDirectFinalizerCall() throws Exception {
        String runIdB = RUN_ID + "-b";

        String reservationJson = "{\"reservationId\":\"res-p005b\","
                + "\"identity\":{\"operationId\":\"" + runIdB + ":tc-1:1\","
                + "\"runId\":\"" + runIdB + "\",\"toolCallId\":\"tc-1\",\"attempt\":1,"
                + "\"reservationId\":\"res-p005b\"},"
                + "\"resourceClass\":\"CPU\",\"capacityUnits\":1,"
                + "\"state\":\"PENDING\",\"taskId\":\"task-p005b\","
                + "\"acquiredAt\":\"" + Instant.now().minusSeconds(120) + "\"}";

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(runIdB + ":tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p005b");
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(runIdB, AgentRunStatus.WAITING_TOOL_JOB.name(), anchor.toJson());

        // Directly set run status to CANCELED
        updateRunStatus(runIdB, AgentRunStatus.CANCELED.name());

        // Build a valid terminal result (so ENVELOPE sets terminalStatus)
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("task-p005b")
                .setStatus("SUCCEEDED")
                .setStdout("some output data")
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("OK").build())
                .build();

        // Directly call handleTerminal on the spy (delegates to real method)
        finalizerSpy.handleTerminal(runIdB, anchor, "SUCCEEDED", result, true);

        // In-memory evidence: finalizerStep was set to ENVELOPE before CAS
        assertThat(anchor.getFinalizerStep()).isEqualTo("ENVELOPE");
        assertThat(anchor.getTerminalStatus()).isEqualTo("SUCCEEDED");

        // DB evidence: ENVELOPE CAS failed, nothing persisted
        AgentRun run = mapper.findById(runIdB);
        assertThat(run).isNotNull();
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getFinalizerStep()).isNull();
        assertThat(dbAnchor.getTerminalStatus()).isNull();
        assertThat(dbAnchor.getTerminalAt()).isNull();

        // Capacity unchanged (releaseCapacity never reached)
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getReservationJson()).contains("res-p005b");

        // Anchor still PENDING
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Verify handleTerminal was invoked on spy
        verify(finalizerSpy).handleTerminal(
                eq(runIdB), any(ToolJobAnchor.class), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(true));
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

    private static void updateRunStatus(String id, String status) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static AgentRunStatus queryRunStatus(String id) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status FROM alphafrog_agent_run WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return AgentRunStatus.valueOf(rs.getString("status"));
                }
            }
        }
        return null;
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
     *   <li>{@code getTaskResult} -- returns a valid result with stdout
     *       (so fetchResult returns non-null and handleTerminal is called)</li>
     *   <li>All other methods -- return default/empty instances (never called)</li>
     * </ul>
     * <p>
     * Unlike the P0-09 stub (where getTaskResult throws to simulate result
     * unavailability), this stub returns a valid result so the reconciler
     * actually calls handleTerminal, which then fails at the ENVELOPE CAS
     * because the run status is CANCELED instead of WAITING_TOOL_JOB.
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
        public CompletableFuture<TaskStatusResponse> getTaskStatusAsync(
                GetTaskStatusRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
            // Return a valid result so fetchResult passes validation and
            // handleTerminal is called (as opposed to null, which causes
            // the reconciler to skip the finalizer entirely)
            return TaskResultResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus("SUCCEEDED")
                    .setStdout("mock output for " + request.getTaskId())
                    .setResourceUsage(SandboxResourceUsage.newBuilder()
                            .setExitReason("OK").build())
                    .build();
        }

        @Override
        public CompletableFuture<TaskResultResponse> getTaskResultAsync(
                GetTaskResultRequest request) {
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
        public GetTaskByOperationIdResponse getTaskByOperationId(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<GetTaskByOperationIdResponse> getTaskByOperationIdAsync(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }
    }
}
