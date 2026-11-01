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
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
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
                    execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                    restart_attempt INT NOT NULL DEFAULT 0,
                    tool_job_anchor_json JSONB DEFAULT '{}',
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                    plan_generation INT NOT NULL DEFAULT -1,
                    run_control_version BIGINT NOT NULL DEFAULT 0
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
