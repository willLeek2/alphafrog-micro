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
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
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
 * Runs 3 consecutive rounds of {@code reconcileFromDue()} to prove the
 * non-finalization behavior is stable across multiple reconciler cycles.
 * Before each round, {@code nextPollAt} and the due ZSET score are reset
 * to the past so the run is picked up again.
 * <p>
 * Fixture:
 * <ol>
 *   <li>Build a real {@link DataAnalysisReservation} via the Java record
 *       (replaces hand-written illegal JSON)</li>
 *   <li>Pre-seed a stateful capacity ledger with this reservation</li>
 *   <li>Seed a run with status=WAITING_TOOL_JOB and a PENDING anchor</li>
 *   <li>Write the run into the Redis due ZSET</li>
 *   <li>Stub {@code sandboxService.getTaskStatus()} -- returns SUCCEEDED</li>
 *   <li>Stub {@code sandboxService.getTaskResult()} -- throws RuntimeException</li>
 *   <li>For each of 3 rounds: reset nextPollAt/ZSET, reconcile, assert</li>
 * </ol>
 * <p>
 * Oracles (after each round):
 * <ol>
 *   <li>{@code finalizer.handleTerminal} called 0 times</li>
 *   <li>{@code releaseCallCount == 0} (capacity never released)</li>
 *   <li>Same active reservation identity still in ledger</li>
 *   <li>Anchor state still PENDING, resultFetchState still null</li>
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
    private ToolJobAnchorService anchorService;
    private StatefulCapacityFake capacityFake;

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
        anchorService = new ToolJobAnchorService(mapper);

        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        // Stateful capacity ledger (pre-seeded later in the test with the
        // real DataAnalysisReservation)
        capacityFake = new StatefulCapacityFake();

        // Real ToolJobFinalizer wrapped in a spy so we can verify handleTerminal
        // was never called
        ToolJobFinalizer realFinalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
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
        // Step 1: Build a real DataAnalysisReservation via the Java record
        // (replaces the old hand-written illegal JSON resourceClass=CPU,state=PENDING)
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "tc-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-p009", Instant.now());
        String reservationJson = om.writeValueAsString(reservation);

        // Pre-seed the stateful capacity ledger with this reservation
        capacityFake.restoreReservation(reservation);
        assertThat(capacityFake.releaseCallCount).isEqualTo(0);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);

        // Step 2: Seed PG -- insert run with WAITING_TOOL_JOB status and a
        //          PENDING anchor carrying the real reservation JSON
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p009");
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(RUN_ID, "WAITING_TOOL_JOB", anchor.toJson());

        // Step 3: Run 3 consecutive rounds of reconcileFromDue().
        // Before each round, reset nextPollAt and the due ZSET score to the past
        // so the run is picked up again. After each round, assert the finalizer
        // was never called, capacity was never released, and the anchor is
        // unchanged (still PENDING, resultFetchState null, reservation preserved).
        for (int round = 1; round <= 3; round++) {
            // --- Reset: load, set nextPollAt to past, persist PG + Redis ---
            ToolJobAnchor currentAnchor = anchorService.loadAnchor(RUN_ID);
            assertThat(currentAnchor).as("round %d: anchor present", round).isNotNull();
            currentAnchor.setNextPollAt(Instant.now().minusSeconds(60));
            anchorService.updateAnchor(RUN_ID, currentAnchor, AgentRunStatus.WAITING_TOOL_JOB);
            redisCache.upsertDue(RUN_ID, currentAnchor);

            // Precondition: run is in the due ZSET with a past score
            Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
            assertThat(score).as("round %d: due ZSET score present", round).isNotNull();
            assertThat(score.longValue())
                    .as("round %d: due ZSET score in the past", round)
                    .isLessThanOrEqualTo(System.currentTimeMillis());

            // --- Reconcile ---
            reconciler.reconcileFromDue();

            // --- Assert after each round ---

            // 1. handleTerminal was NEVER called (0 times across all rounds)
            verify(finalizerSpy, never()).handleTerminal(
                    any(String.class), any(ToolJobAnchor.class), any(String.class),
                    any(TaskResultResponse.class), anyBoolean());

            // 2. Capacity release never called
            assertThat(capacityFake.releaseCallCount)
                    .as("round %d: releaseCallCount == 0", round)
                    .isEqualTo(0);

            // 3. Same active reservation identity still in the ledger
            assertThat(capacityFake.distinctReservationIds())
                    .as("round %d: distinctReservationIds == 1", round)
                    .isEqualTo(1);

            // 4. PG anchor: retry tracking initialized (resultFetchState=PENDING,
            //    terminalConfirmedAt set, attempts counting), but finalizer never called.
            //    anchorState unchanged (not modified by retry path).
            AgentRun run = mapper.findById(RUN_ID);
            assertThat(run).as("round %d: run present", round).isNotNull();
            ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
            assertThat(dbAnchor.getResultFetchState())
                    .as("round %d: resultFetchState=PENDING (retry tracking active)", round)
                    .isEqualTo("PENDING");
            assertThat(dbAnchor.getResultFetchAttempts())
                    .as("round %d: resultFetchAttempts=%d", round, round)
                    .isEqualTo(round);
            assertThat(dbAnchor.getTerminalConfirmedAt())
                    .as("round %d: terminalConfirmedAt set", round)
                    .isNotNull();
            assertThat(dbAnchor.getFinalizerStep())
                    .as("round %d: finalizerStep still null (finalizer never called)", round)
                    .isNull();

            // 5. Reservation JSON preserved (capacity not released, identity unchanged)
            assertThat(dbAnchor.getReservationJson())
                    .as("round %d: reservationJson present", round)
                    .isNotNull().isNotBlank();

            // 6. Terminal fields: terminalStatus remains null (no RESULT_LOST yet —
            //    exhaustion budget not reached within 3 rounds)
            assertThat(dbAnchor.getTerminalStatus())
                    .as("round %d: terminalStatus null (budget not exhausted)", round)
                    .isNull();
            assertThat(dbAnchor.getTerminalAt())
                    .as("round %d: terminalAt null", round)
                    .isNull();
        }
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

        // D11 cancelTask RPC (W2 task #102 — ccmax proto/Gateway 单 writer).
        // 测试桩不在本波实现 cancelTask；与 PythonSandboxService 接口的其他 RPC 一致，
        // 抛 UnsupportedOperationException 让任何意外调用立即失败。生产 Gateway 实现遵守
        // D14 装配依赖（codex a3aee2ad 第 六 节裁定 4），DubboPythonSandboxServiceTriple
        // 生成的默认 cancelTask 返回 UNIMPLEMENTED，不写假 override。
        @Override
        public CancelTaskResponse cancelTask(CancelTaskRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<CancelTaskResponse> cancelTaskAsync(CancelTaskRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }
    }

    /**
     * Stateful capacity ledger that tracks actual reserved-to-released transitions.
     * Copied from {@code ToolJobFinalizerP006Test.StatefulCapacityFake}.
     * <p>
     * Unlike a Mockito mock, this proves the ledger state changes at most once
     * even when {@code releaseReservation} is called multiple times during
     * crash/re-entry. In this test, releaseReservation is never called at all
     * (the finalizer is never reached), so {@code releaseCallCount} stays 0
     * and the pre-seeded reservation stays in the ledger with its original state.
     */
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

        // ---- unused in this fixture ----
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
