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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * P0-01: Fast-path completed (completeSynchronously) contract fixture.
 *
 * <p>Verifies the key fast-path contracts without requiring the full Dubbo sandbox
 * stack. Uses ToolJobFinalizer.handleTerminal() as the equivalent of
 * completeSynchronously ENVELOPE+RELEASE+USAGE, then clearActive to simulate
 * the acknowledgeSynchronousPythonCompletion cleanup.
 *
 * <p>Oracles (from DESIGN-v5.md):
 * <ol>
 *   <li>After finalizer completes + clearActive: anchor cleared to {@code {}} or
 *       loadAnchor returns effectively-empty anchor</li>
 *   <li>run.status NOT COMPLETED (pipeline sets it later — RECEIVED)</li>
 *   <li>TOOL_CALL_FINISHED event with dedupeKey exists via
 *       eventMapper.findByRunIdAndDedupeKey</li>
 *   <li>No resumeState set after clearActive</li>
 *   <li>finalizer.handleTerminal architectural fact: ENVELOPE step uses
 *       updateAnchor(WAITING_TOOL_JOB) not updateAnchor(EXECUTING)</li>
 * </ol>
 */
@Testcontainers
class ToolJobFinalizerP001Test {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";

    // Redis
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    // MyBatis
    private static SqlSessionFactory sqlSessionFactory;
    private final List<SqlSession> activeSessions = new ArrayList<>();

    @BeforeAll
    static void setUpInfra() throws Exception {
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
            stmt.execute("""
                CREATE TABLE alphafrog_agent_run_event (
                    id BIGSERIAL PRIMARY KEY,
                    run_id VARCHAR(64) NOT NULL,
                    seq INT NOT NULL DEFAULT 0,
                    event_type VARCHAR(64) NOT NULL,
                    payload_json JSONB DEFAULT '{}',
                    dedupe_key VARCHAR(256),
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE UNIQUE INDEX idx_event_dedupe
                    ON alphafrog_agent_run_event (run_id, dedupe_key)
                    WHERE dedupe_key IS NOT NULL""");
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
    void cleanUp() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run_event");
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void closeSessions() {
        for (SqlSession s : activeSessions) {
            try { s.close(); } catch (Exception ignored) { }
        }
        activeSessions.clear();
    }

    // ===== Infrastructure helpers =====

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newRunMapper() throws Exception {
        ensureFactory();
        SqlSession session = sqlSessionFactory.openSession(true);
        activeSessions.add(session);
        return session.getMapper(AgentRunMapper.class);
    }

    private AgentRunEventMapper newEventMapper() throws Exception {
        ensureFactory();
        SqlSession session = sqlSessionFactory.openSession(true);
        activeSessions.add(session);
        return session.getMapper(AgentRunEventMapper.class);
    }

    private void ensureFactory() throws Exception {
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            config.addMapper(AgentRunEventMapper.class);
            String runMapperRes = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(runMapperRes)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, runMapperRes, config.getSqlFragments()).parse();
            }
            String eventMapperRes = "mapper/AgentRunEventMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(eventMapperRes)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, eventMapperRes, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        }
    }

    private static void insertRun(String id, String status, String userId, String anchorJson) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, user_id, tool_job_anchor_json) "
                             + "VALUES (?, ?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, userId);
            ps.setString(4, anchorJson);
            ps.executeUpdate();
        }
    }

    // ===== Test: P0-01 fast-path completeSynchronously equivalent =====

    @Test
    void fastPathEquivalentEnvelopeReleaseUsageThenClearActive() throws Exception {
        // ---- Seed: run with WAITING_TOOL_JOB status and PENDING anchor ----
        // This simulates the state just before the reconciler/finalizer picks up
        // a pending job. The anchor has all terminal data that completeSynchronously
        // would have written.
        String runId = "run-p001";
        String toolCallId = "tc-p001";
        int attempt = 1;
        String taskId = "task-p001";
        String operationId = runId + ":" + toolCallId + ":" + attempt;

        String reservationJson = buildReservationJson(runId, toolCallId, attempt, taskId,
                DataAnalysisReservationState.PENDING_TRANSFERRED);

        ToolJobAnchor seed = buildTerminalAnchor(runId, toolCallId, attempt, taskId, reservationJson);
        seed.setAnchorState("PENDING");
        seed.setAutoResume(true);

        insertRun(runId, "WAITING_TOOL_JOB", "user-p001", seed.toJson());

        // ---- Wire up services ----
        AgentRunMapper runMapper = newRunMapper();
        AgentRunEventMapper eventMapper = newEventMapper();

        ToolJobAnchorService anchorService = new ToolJobAnchorService(runMapper);
        ToolJobConfig config = new ToolJobConfig();
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, objectMapper, config);
        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        DurableUsageFake usageFake = new DurableUsageFake();
        PgBackedEventHook eventHook = new PgBackedEventHook(eventMapper);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config);
        inject(finalizer, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer, "eventHook", (ToolJobEventHook) eventHook);

        // ---- Act: handleTerminal (equivalent to completeSynchronously path) ----
        ToolJobAnchor anchor = buildTerminalAnchor(runId, toolCallId, attempt, taskId, reservationJson);
        anchor.setAnchorState("PENDING");
        anchor.setAutoResume(true);
        finalizer.handleTerminal(runId, anchor, "SUCCEEDED", null, true);

        // ---- Oracle 1: finalizer completed through RESUME_READY ----
        ToolJobAnchor result = anchorService.loadAnchor(runId);
        assertThat(result).isNotNull();
        assertThat(result.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_RESUME_READY);
        assertThat(result.getResumeState()).isEqualTo("READY");

        // ---- Oracle 2: ENVELOPE step used WAITING_TOOL_JOB (architectural fact) ----
        // Proved implicitly: the seed had status=WAITING_TOOL_JOB and ENVELOPE CAS
        // succeeded (updateAnchor uses WHERE status = expectedStatus). If the finalizer
        // used EXECUTING, the CAS would have returned 0 rows and the finalizer would
        // have returned early.
        // We additionally verify that capacity release happened exactly once.
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(1);

        // ---- Oracle 3: run.status = RECEIVED (NOT COMPLETED) ----
        // CAS_STATUS step transitions to RECEIVED; pipeline sets COMPLETED later.
        AgentRun run = runMapper.findById(runId);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(run.getStatus()).isNotEqualTo(AgentRunStatus.COMPLETED);

        // ---- Oracle 4: TOOL_CALL_FINISHED event exists with dedupeKey ----
        String dedupeKey = runId + ":" + toolCallId + ":logical_terminal";
        AgentRunEvent event = eventMapper.findByRunIdAndDedupeKey(runId, dedupeKey);
        assertThat(event).isNotNull();
        assertThat(event.getEventType()).isEqualTo("TOOL_CALL_FINISHED");
        assertThat(event.getDedupeKey()).isEqualTo(dedupeKey);

        // ---- Oracle 5: capacity release, usage upsert, and event emit each called once ----
        assertThat(usageFake.callCount).isEqualTo(1);
        assertThat(eventHook.callCount).isEqualTo(1);

        // ---- Oracle 6: resumeService.tryResume was called ----
        verify(resumeService, times(1)).tryResume(runId);

        // ---- Act: clearActive = acknowledgeSynchronousPythonCompletion equivalent ----
        boolean cleared = anchorService.clearActive(runId, AgentRunStatus.RECEIVED, operationId);
        assertThat(cleared).isTrue();

        // ---- Oracle 7: anchor cleared after clearActive ----
        ToolJobAnchor clearedAnchor = anchorService.loadAnchor(runId);
        // After clearActive sets tool_job_anchor_json to '{}', loadAnchor returns
        // a ToolJobAnchor with all null fields (fromJson parses empty object).
        assertThat(clearedAnchor).isNotNull(); // non-null because "{}" is not blank
        assertThat(clearedAnchor.getOperationId()).isNull();
        assertThat(clearedAnchor.getAnchorState()).isNull();
        assertThat(clearedAnchor.getFinalizerStep()).isNull();

        // ---- Oracle 8: no resumeState after clearActive ----
        assertThat(clearedAnchor.getResumeState()).isNull();

        // ---- Oracle 9: run.status still RECEIVED after clearActive ----
        // clearActive only clears the anchor JSON, not the status.
        run = runMapper.findById(runId);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RECEIVED);

        // ---- Oracle 10: Redis pending cache cleaned up by action after handleTerminal ----
        // The RESUME_READY step writes to Redis pending cache.
        ToolJobAnchor cachedAnchor = redisCache.readPendingCache(runId);
        assertThat(cachedAnchor).isNotNull();
        assertThat(cachedAnchor.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_RESUME_READY);
    }

    // ===== PgBackedEventHook: writes TOOL_CALL_FINISHED to real PG =====

    static class PgBackedEventHook implements ToolJobEventHook {
        private final AgentRunEventMapper eventMapper;
        int callCount;

        PgBackedEventHook(AgentRunEventMapper eventMapper) {
            this.eventMapper = eventMapper;
        }

        @Override
        public boolean emitTerminalEvent(String runId, ToolJobAnchor anchor) {
            callCount++;
            AgentRunEvent event = new AgentRunEvent();
            event.setRunId(runId);
            event.setSeq(1);
            event.setEventType("TOOL_CALL_FINISHED");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("run_id", runId);
            payload.put("tool_call_id", anchor.getToolCallId());
            payload.put("attempt", anchor.getAttempt());
            payload.put("success", "SUCCEEDED".equals(anchor.getTerminalStatus()));
            try {
                event.setPayloadJson(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                event.setPayloadJson("{}");
            }
            String dedupeKey = runId + ":" + anchor.getToolCallId() + ":logical_terminal";
            event.setDedupeKey(dedupeKey);
            event.setCreatedAt(OffsetDateTime.now());
            try {
                eventMapper.insertOnce(event);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ===== Stateful fakes (same pattern as P0-06) =====

    static class StatefulCapacityFake implements DataAnalysisCapacityService {
        private final Map<String, DataAnalysisReservationState> ledger = new LinkedHashMap<>();
        int releaseCallCount;
        int transitionCount;

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

        @Override
        public DataAnalysisReservation reserve(DataAnalysisOperationIdentity identity,
                                               DataAnalysisEstimate estimate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> durableReservations, int configuredMaxUnits,
                int configuredMaxHeavyActive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DataAnalysisAdmissionState admissionState() {
            throw new UnsupportedOperationException();
        }
    }

    static class DurableUsageFake implements ToolJobUsageHook {
        int callCount;

        @Override
        public boolean upsertUsage(String runId, ToolJobAnchor anchor) {
            callCount++;
            return true;
        }
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
        anchor.setTerminalResultPreview("mock output for run " + runId);
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(reservationJson);
        anchor.setTerminalRetryable(false);
        return anchor;
    }
}
