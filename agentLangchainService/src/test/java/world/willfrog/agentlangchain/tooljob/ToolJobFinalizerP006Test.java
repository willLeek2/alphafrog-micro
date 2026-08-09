package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P0-06: RELEASE step DB write failure to re-entry idempotent.
 *
 * <p>Fixture: capacity release side effect succeeds but the subsequent DB
 * write of finalizerStep=RELEASE fails. On re-entry the finalizer detects
 * ALREADY_RELEASED in the capacity ledger, skips the duplicate release, and
 * advances the DB anchor past RELEASE.
 *
 * <p>Uses a <b>fail-once spy</b> wrapping a real {@link ToolJobAnchorService}
 * backed by Testcontainers PostgreSQL (not Mockito), so the anchor state
 * in PG is verifiable between calls. A stateful {@link StatefulCapacityFake}
 * proves the ledger transitions at most once across two release attempts.
 */
@Testcontainers
class ToolJobFinalizerP006Test {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static SqlSessionFactory sqlSessionFactory;
    private final List<SqlSession> activeSessions = new ArrayList<>();

    @BeforeAll
    static void createTable() throws Exception {
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
    }

    @AfterAll
    static void closeContainer() { /* @Container handles cleanup */ }

    @AfterEach
    void closeSessions() {
        for (SqlSession s : activeSessions) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        activeSessions.clear();
    }

    @BeforeEach
    void cleanTable() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /**
     * Creates a fresh SqlSession and returns a mapper from it.
     * Sessions accumulate in {@link #activeSessions} and are closed in {@link #closeSessions()}.
     */
    private AgentRunMapper newMapper() throws Exception {
        ensureFactory();
        SqlSession session = sqlSessionFactory.openSession(true);
        activeSessions.add(session);
        return session.getMapper(AgentRunMapper.class);
    }

    private void ensureFactory() throws Exception {
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        }
    }

    private static void insertRun(String id, String status, String anchorJson) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    // ===== P0-06: RELEASE step DB write failure / re-entry idempotent =====

    @Test
    void handleTerminalHappyPathCompletesAllStepsWithRealPG() throws Exception {
        // Establish baseline: plain ToolJobAnchorService against PG, no spy.
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService realService = new ToolJobAnchorService(mapper);

        String reservationJson = buildReservationJson("run-happy", "tc-happy", 1, "task-happy",
                DataAnalysisReservationState.PENDING_TRANSFERRED);
        ToolJobAnchor seed = buildTerminalAnchor("run-happy", "tc-happy", 1, "task-happy", reservationJson);
        seed.setAnchorState("PENDING");
        insertRun("run-happy", "WAITING_TOOL_JOB", seed.toJson());

        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DurableUsageFake usageFake = new DurableUsageFake();
        DedupedEventFake eventFake = new DedupedEventFake();

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                realService, redisCache, capacityFake, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer, "eventHook", (ToolJobEventHook) eventFake);

        ToolJobAnchor anchor = buildTerminalAnchor("run-happy", "tc-happy", 1, "task-happy", reservationJson);
        anchor.setAnchorState("PENDING");
        finalizer.handleTerminal("run-happy", anchor, "SUCCEEDED", null, true);

        ToolJobAnchor result = realService.loadAnchor("run-happy");
        assertThat(result.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_RESUME_READY);
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
        verify(resumeService, times(1)).tryResume("run-happy");
    }

    @Test
    void releaseStepWriteFailsReentryIsIdempotent() throws Exception {
        // Use a single mapper session for all services so writes are immediately visible.
        ensureFactory();
        SqlSession session = sqlSessionFactory.openSession(true);
        activeSessions.add(session);
        AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

        // Build fail-once spy wrapping real ToolJobAnchorService -> real PG
        FailOnceAnchorService spyService = new FailOnceAnchorService(mapper);

        // Seed: run (status=WAITING_TOOL_JOB), anchor with anchorState=PENDING + valid terminal data
        String reservationJson = buildReservationJson("run-p06", "tc-p06", 1, "task-p06",
                DataAnalysisReservationState.PENDING_TRANSFERRED);
        ToolJobAnchor seedAnchor = buildTerminalAnchor("run-p06", "tc-p06", 1, "task-p06", reservationJson);
        seedAnchor.setAnchorState("PENDING");
        insertRun("run-p06", "WAITING_TOOL_JOB", seedAnchor.toJson());

        // Stateful fakes and mocks
        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DurableUsageFake usageFake = new DurableUsageFake();
        DedupedEventFake eventFake = new DedupedEventFake();

        ToolJobFinalizer finalizer1 = new ToolJobFinalizer(
                spyService, redisCache, capacityFake, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer1, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer1, "eventHook", (ToolJobEventHook) eventFake);

        // === First call: ENVELOPE persists to PG, RELEASE capacity side effect
        //     succeeds, then updateAnchor(RELEASE) returns false -> finalizer returns ===
        ToolJobAnchor anchor1 = buildTerminalAnchor("run-p06", "tc-p06", 1, "task-p06", reservationJson);
        anchor1.setAnchorState("PENDING");
        finalizer1.handleTerminal("run-p06", anchor1, "SUCCEEDED", null, true);

        // Oracle: PG anchor is ENVELOPE after first (failed) call
        // Use same mapper-based service so we read from the same session
        ToolJobAnchorService verifyService = new ToolJobAnchorService(mapper);
        ToolJobAnchor pgAnchor = verifyService.loadAnchor("run-p06");
        assertThat(pgAnchor).isNotNull();
        assertThat(pgAnchor.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_ENVELOPE);
        // Fail-closed gate requires terminalRetryable, buildEnvelope requires estimateJson
        assertThat(pgAnchor.getTerminalRetryable()).isNotNull();
        assertThat(pgAnchor.getEstimateJson()).isNotNull();

        // Oracle: capacity release called once, exactly one ledger transition
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);

        // No resume on failed call (finalizer returned early at RELEASE)
        verify(resumeService, never()).tryResume(any());

        // === Second call: re-entry with NEW SqlSession (simulates process restart) ===
        //     ENVELOPE isStepDone -> skip
        //     RELEASE: restoreReservation CONFLICT, releaseReservation -> ALREADY_RELEASED,
        //     then updateAnchor(RELEASE) succeeds via real PG -> finalizer proceeds ===
        // Open a fresh SqlSession to simulate a process restart — codex "new SqlSession/new mapper"
        SqlSession reentrySession = sqlSessionFactory.openSession(true);
        activeSessions.add(reentrySession);
        AgentRunMapper reentryMapper = reentrySession.getMapper(AgentRunMapper.class);
        ToolJobAnchorService reentryService = new ToolJobAnchorService(reentryMapper);

        // Reload anchor from PG via the new session (simulates startup recovery load)
        ToolJobAnchor reentryAnchor = reentryService.loadAnchor("run-p06");
        assertThat(reentryAnchor).isNotNull();
        assertThat(reentryAnchor.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_ENVELOPE);

        ToolJobFinalizer finalizer2 = new ToolJobFinalizer(
                reentryService, redisCache, capacityFake, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer2, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer2, "eventHook", (ToolJobEventHook) eventFake);

        // reentryAnchor loaded from new session; pass it to re-entry
        finalizer2.handleTerminal("run-p06", reentryAnchor, "SUCCEEDED", null, true);

        // Oracle: capacity release called twice, but only one ledger transition
        assertThat(capacityFake.releaseCallCount).isEqualTo(2);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
        // Same reservation identity throughout
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);

        // Oracle: second release returns ALREADY_RELEASED
        // (proved by releaseCallCount=2, transitionCount=1 -- second call was a no-op)

        // Oracle: anchor advances past RELEASE on re-entry
        // Read back through the reentry session to verify
        ToolJobAnchor finalAnchor = reentryService.loadAnchor("run-p06");
        assertThat(finalAnchor.getFinalizerStep())
                .isEqualTo(ToolJobFinalizer.STEP_RESUME_READY);

        // Oracle: No double-release side effect (transitionCount=1 proves this)
        verify(resumeService, times(1)).tryResume("run-p06");
    }

    // ===== Fail-once spy =====

    /**
     * Fail-once spy wrapping a real {@link ToolJobAnchorService} backed by real PG.
     * When {@code updateAnchor} is called with finalizerStep=RELEASE, it swallows
     * the write and returns false exactly once. All other calls delegate to the
     * real service so anchor state is verifiable in PostgreSQL.
     */
    static class FailOnceAnchorService extends ToolJobAnchorService {
        private boolean releaseFailed = false;

        FailOnceAnchorService(AgentRunMapper mapper) {
            super(mapper);
        }

        @Override
        public boolean updateAnchor(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
            if (!releaseFailed && ToolJobFinalizer.STEP_RELEASE.equals(anchor.getFinalizerStep())) {
                releaseFailed = true;
                return false; // swallow the write, simulating DB write failure
            }
            return super.updateAnchor(runId, anchor, expectedStatus);
        }
    }

    // ===== Stateful fakes (same pattern as ToolJobFinalizerDbWriteFailureTest) =====

    /**
     * Stateful capacity ledger that tracks actual reserved-to-released transitions.
     * Unlike a Mockito mock, this proves the ledger state changes at most once
     * even when releaseReservation is called multiple times during crash/re-entry.
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

    /** Tracks upsert calls keyed by operationId (stable idempotency key per contract). */
    static class DurableUsageFake implements ToolJobUsageHook {
        final List<CallRecord> calls = new ArrayList<>();
        int callCount;

        @Override
        public boolean upsertUsage(String runId, ToolJobAnchor anchor) {
            callCount++;
            calls.add(new CallRecord(anchor.getOperationId(), runId));
            return true;
        }

        int distinctOperationIds() {
            return (int) calls.stream().map(c -> c.operationId).distinct().count();
        }

        record CallRecord(String operationId, String runId) {}
    }

    /**
     * Models the real {@code ToolJobEventHookImpl} dedupe contract:
     * key = {@code runId:toolCallId:logical_terminal}.
     * Tracks every invocation but only admits the first occurrence into the store.
     */
    static class DedupedEventFake implements ToolJobEventHook {
        final List<CallRecord> calls = new ArrayList<>();
        final Set<String> store = new LinkedHashSet<>();
        int callCount;

        @Override
        public boolean emitTerminalEvent(String runId, ToolJobAnchor anchor) {
            callCount++;
            String dedupeKey = runId + ":" + anchor.getToolCallId() + ":logical_terminal";
            calls.add(new CallRecord(runId, anchor.getToolCallId(), dedupeKey));
            store.add(dedupeKey);
            return true;
        }

        int distinctDedupeKeys() {
            return store.size();
        }

        record CallRecord(String runId, String toolCallId, String dedupeKey) {}
    }

    // ===== helpers =====

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String buildReservationJson(String runId, String toolCallId, int attempt,
                                                String taskId,
                                                DataAnalysisReservationState state) throws Exception {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                state, taskId, Instant.now());
        return objectMapper.writeValueAsString(reservation);
    }

    private static ToolJobAnchor buildTerminalAnchor(String runId, String toolCallId,
                                                       int attempt, String taskId,
                                                       String reservationJson) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setAutoResume(true);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(reservationJson);
        anchor.setTerminalRetryable(false); // SUCCEEDED runs are not retryable
        return anchor;
    }
}
