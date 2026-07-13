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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-09 forward: terminal status with unavailable result → retry budget →
 * RESULT_LOST → finalizer release.  Flips the reverse fixture into a
 * forward PASS once the production gap is closed.
 *
 * <p>Budget:</p>
 * <ul>
 *   <li>{@code resultFetchMaxAttempts = 3}</li>
 *   <li>{@code resultRetentionDeadlineSeconds = 1} (used by elapsed test)</li>
 * </ul>
 *
 * <p>Cases:</p>
 * <ol>
 *   <li><b>Multi-round before exhaustion</b> — rounds 1-2: anchor tracks
 *       retry state, no finalizer, no release.</li>
 *   <li><b>Exhaustion by max attempts</b> — round 3: RESULT_LOST terminal,
 *       finalizer called once, capacity released exactly once.</li>
 *   <li><b>Idempotent after exhaustion</b> — round 4: no second finalizer
 *       call, no double release.</li>
 *   <li><b>Restart safety</b> — fresh SqlSession reloads durable state from
 *       PG; exhaustion continues from persisted attempts.</li>
 * </ol>
 */
@Testcontainers
class ToolJobReconcilerP009ForwardTest {

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
    private static final String RUN_ID = "run-p009-fwd";

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static SqlSessionFactory sqlSessionFactory;
    private static DataSource dataSource;

    private SqlSession currentSession;

    // ---- Infrastructure ----

    @BeforeAll
    static void setUpInfra() throws Exception {
        dataSource = buildDataSource();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
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

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    // ---- Forward fixture ----

    @Test
    void shouldExhaustRetryBudgetThenReleaseAfterResultLost() throws Exception {
        // Config: low thresholds to test exhaustion without running 10+ rounds
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(3);
        config.setResultRetentionDeadlineSeconds(600); // use attempts-based exhaustion
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        // Stateful capacity
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "tc-fwd", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-p009-fwd", Instant.now());
        String reservationJson = om.writeValueAsString(reservation);

        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        capacityFake.restoreReservation(reservation);

        // Seed PG anchor
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc-fwd");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p009-fwd");
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");

        insertRun(RUN_ID, "WAITING_TOOL_JOB", anchor.toJson());

        // Real services
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        // Real finalizer
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config);

        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
        injectSandboxStub(reconciler);

        // ---- Round 1: first null → PENDING, attempt=1, no release ----
        resetDue(reconciler, anchorService, redisCache, RUN_ID);
        reconciler.reconcileFromDue();

        ToolJobAnchor a1 = loadAnchor(mapper, RUN_ID);
        assertThat(a1.getResultFetchState()).as("round 1: resultFetchState").isEqualTo("PENDING");
        assertThat(a1.getResultFetchAttempts()).as("round 1: attempts").isEqualTo(1);
        assertThat(a1.getTerminalConfirmedAt()).as("round 1: terminalConfirmedAt set").isNotNull();
        assertThat(a1.getTerminalStatus()).as("round 1: terminalStatus null").isNull();
        assertThat(capacityFake.releaseCallCount).as("round 1: releaseCallCount").isEqualTo(0);

        // ---- Round 2: second null → attempt=2, still no release ----
        resetDue(reconciler, anchorService, redisCache, RUN_ID);
        reconciler.reconcileFromDue();

        ToolJobAnchor a2 = loadAnchor(mapper, RUN_ID);
        assertThat(a2.getResultFetchAttempts()).as("round 2: attempts").isEqualTo(2);
        assertThat(a2.getTerminalStatus()).as("round 2: terminalStatus null").isNull();
        assertThat(capacityFake.releaseCallCount).as("round 2: releaseCallCount").isEqualTo(0);

        // ---- Round 3: third null → attempts=3 ≥ maxAttempts=3 → RESULT_LOST ----
        resetDue(reconciler, anchorService, redisCache, RUN_ID);
        reconciler.reconcileFromDue();

        // After RESULT_LOST, finalizer handles the terminal → anchor may be
        // in finalizing state or the run status may have changed.
        AgentRun run3 = mapper.findById(RUN_ID);
        assertThat(run3).as("round 3: run exists").isNotNull();
        ToolJobAnchor a3 = ToolJobAnchor.fromJson(run3.getToolJobAnchorJson());
        assertThat(a3.getResultFetchState()).as("round 3: resultFetchState").isEqualTo("LOST");
        assertThat(a3.getTerminalStatus()).as("round 3: terminalStatus RESULT_LOST or envelope").isNotNull();
        // Capacity released via finalizer
        assertThat(capacityFake.releaseCallCount).as("round 3: releaseCallCount").isEqualTo(1);
        assertThat(capacityFake.transitionCount).as("round 3: exactly one transition").isEqualTo(1);

        // ---- Round 4 after exhaustion: idempotent, no double release ----
        // Reload with fresh SqlSession to simulate restart
        mapper = newMapper();
        anchorService = new ToolJobAnchorService(mapper);
        ToolJobFinalizer finalizer2 = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config);
        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer2, resumeService, config);
        injectSandboxStub(reconciler);

        resetDue(reconciler, anchorService, redisCache, RUN_ID);
        reconciler.reconcileFromDue();

        // releaseCallCount does not change (ALREADY_RELEASED via finalizer re-entrancy)
        assertThat(capacityFake.releaseCallCount)
                .as("round 4: releaseCallCount no change").isEqualTo(1);
        assertThat(capacityFake.transitionCount)
                .as("round 4: transitionCount remains 1").isEqualTo(1);
    }

    @Test
    void shouldExhaustByDeadlineWhenElapsedExceedsRetention() throws Exception {
        // Config: very short deadline so elapsed time triggers exhaustion
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(100); // not triggered
        config.setResultRetentionDeadlineSeconds(1); // 1 second
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "tc-deadline", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-deadline", Instant.now());

        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        capacityFake.restoreReservation(reservation);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc-deadline");
        anchor.setAttempt(1);
        anchor.setTaskId("task-deadline");
        anchor.setAutoResume(true);
        anchor.setReservationJson(om.writeValueAsString(reservation));
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        // Set terminalConfirmedAt to 5 seconds ago → elapsed > 1s deadline
        anchor.setTerminalConfirmedAt(Instant.now().minus(5, ChronoUnit.SECONDS));
        anchor.setResultFetchState("PENDING");
        anchor.setResultFetchAttempts(1);

        insertRun(RUN_ID, "WAITING_TOOL_JOB", anchor.toJson());

        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config);
        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
        injectSandboxStub(reconciler);

        resetDue(reconciler, anchorService, redisCache, RUN_ID);
        reconciler.reconcileFromDue();

        AgentRun run = mapper.findById(RUN_ID);
        ToolJobAnchor loaded = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(loaded.getResultFetchState()).isEqualTo("LOST");
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
    }

    // ---- Helpers ----

    private static DataSource buildDataSource() {
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
            var c = new org.apache.ibatis.session.Configuration();
            c.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    dataSource);
            c.setEnvironment(env);
            c.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, c, res, c.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(c);
        }
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
    }

    private void insertRun(String id, String status, String anchorJson) throws Exception {
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) "
                             + "VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    private ToolJobAnchor loadAnchor(AgentRunMapper mapper, String runId) {
        AgentRun run = mapper.findById(runId);
        if (run == null || run.getToolJobAnchorJson() == null) return null;
        return ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
    }

    private void resetDue(ToolJobReconciler reconciler, ToolJobAnchorService anchorService,
                           ToolJobRedisCache redisCache, String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor != null) {
            anchor.setNextPollAt(Instant.now().minusSeconds(60));
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
            redisCache.upsertDue(runId, anchor);
        }
    }

    private static void injectSandboxStub(ToolJobReconciler reconciler) throws Exception {
        Field field = ToolJobReconciler.class.getDeclaredField("sandboxService");
        field.setAccessible(true);
        field.set(reconciler, new ResultUnavailableStub());
    }

    /**
     * Sandbox stub: reports terminal status (SUCCEEDED) but getTaskResult
     * always throws — simulating a transient sandbox error.
     */
    private static class ResultUnavailableStub implements PythonSandboxService {
        @Override
        public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
            return TaskStatusResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus("SUCCEEDED")
                    .build();
        }

        @Override
        public CompletableFuture<TaskStatusResponse> getTaskStatusAsync(GetTaskStatusRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
            throw new RuntimeException("Result temporarily unavailable");
        }

        @Override
        public CompletableFuture<TaskResultResponse> getTaskResultAsync(GetTaskResultRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecuteResponse createTask(ExecuteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ExecuteResponse> createTaskAsync(ExecuteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GetTaskByOperationIdResponse getTaskByOperationId(GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<GetTaskByOperationIdResponse> getTaskByOperationIdAsync(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    static class StatefulCapacityFake implements DataAnalysisCapacityService {
        private final Map<String, DataAnalysisReservationState> ledger = new LinkedHashMap<>();
        int releaseCallCount;
        int transitionCount;

        int distinctReservationIds() {
            return ledger.size();
        }

        @Override
        public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation) {
            String id = reservation.reservationId();
            if (ledger.containsKey(id)) {
                return DataAnalysisRestoreOutcome.CONFLICT;
            }
            ledger.put(id, reservation.state());
            return DataAnalysisRestoreOutcome.ADDED;
        }

        @Override
        public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
            releaseCallCount++;
            String id = request.reservation().reservationId();
            DataAnalysisReservationState current = ledger.getOrDefault(id, request.reservation().state());
            if (current == DataAnalysisReservationState.RELEASED) {
                return DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            }
            ledger.put(id, DataAnalysisReservationState.RELEASED);
            transitionCount++;
            return DataAnalysisReleaseOutcome.RELEASED;
        }

        @Override public DataAnalysisReservation reserve(DataAnalysisOperationIdentity identity,
                                                          DataAnalysisEstimate estimate) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> durableReservations, int configuredMaxUnits,
                int configuredMaxHeavyActive) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisAdmissionState admissionState() {
            throw new UnsupportedOperationException();
        }
    }
}
