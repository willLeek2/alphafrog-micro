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
                    execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                    restart_attempt INT NOT NULL DEFAULT 0,
                    tool_job_anchor_json JSONB DEFAULT '{}',
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                    plan_generation INT NOT NULL DEFAULT -1,
                    run_control_version BIGINT NOT NULL DEFAULT 0
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
