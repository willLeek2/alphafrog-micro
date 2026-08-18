package world.willfrog.agentlangchain.tooljob;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL integration tests using real MyBatis AgentRunMapper mapped statements.
 * Every test calls production AgentRunMapper methods — not hand-copied SQL.
 * Verifies JSONB predicates and ToolJobAnchor.fromJson roundtrip after each write.
 */
@Testcontainers
class ToolJobAnchorMapperIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String VALID_SNAPSHOT_JSON;
    private static final String VALID_SNAPSHOT_DIGEST;
    private static final String VALID_ESTIMATE_JSON;

    static {
        try {
            var snapshot = AgentRunDatasetSnapshot.empty();
            VALID_SNAPSHOT_JSON = om.writeValueAsString(snapshot);
            VALID_SNAPSHOT_DIGEST = snapshot.immutableDigest();

            var estimate = new DataAnalysisEstimate(0, 0, 0, 0.0, 0,
                    List.of(), DataAnalysisResourceClass.STANDARD, 1);
            VALID_ESTIMATE_JSON = om.writeValueAsString(estimate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
                    tool_job_anchor_json JSONB DEFAULT '{}'
                )""");
        }
    }

    @AfterAll
    static void closeContainer() { /* @Container handles cleanup */ }

    private SqlSession currentSession;

    @AfterEach
    void closeSession() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
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

    private static SqlSessionFactory sqlSessionFactory;

    private AgentRunMapper newMapper() throws Exception {
        // Close previous session before opening a new one
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
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
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
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

    private static void updateLastError(String id, String lastError) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET last_error = ? WHERE id = ?")) {
            ps.setString(1, lastError);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static void updateUserId(String id, String userId) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET user_id = ? WHERE id = ?")) {
            ps.setString(1, userId);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    // ========== updateToolJobCheckpoint: atomic merge CAS ==========

    @Test
    void checkpointShouldUpdateWhenAllIdentityMatch() throws Exception {
        insertRun("run-1", "EXECUTING", """
            {"operationId":"run-1:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r1"}""");

        AgentRunMapper m = newMapper();
        int rows = m.updateToolJobCheckpoint(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "tc-1", 1, "task-123", 0,
                "todo_3", 3,
                "[{\"todoId\":\"todo_1\"}]",
                "{\"digest\":\"abc\"}", "abc123",
                "[\"ds1\"]", 2,
                "{\"cpu\":100}");

        assertThat(rows).isEqualTo(1);

        // fromJson roundtrip: all String fields survive
        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-1").getToolJobAnchorJson());
        assertThat(a.getCheckpointVersion()).isEqualTo(1);
        assertThat(a.getTodoId()).isEqualTo("todo_3");
        assertThat(a.getSequence()).isEqualTo(3);
        assertThat(a.getCompletedTodosJson()).isEqualTo("[{\"todoId\":\"todo_1\"}]");
        assertThat(a.getDatasetSnapshotJson()).isEqualTo("{\"digest\":\"abc\"}");
        assertThat(a.getDatasetSnapshotDigest()).isEqualTo("abc123");
        assertThat(a.getDatasetRefsJson()).isEqualTo("[\"ds1\"]");
        assertThat(a.getToolCallsUsed()).isEqualTo(2);
        assertThat(a.getEstimateJson()).isEqualTo("{\"cpu\":100}");
        assertThat(a.getOperationId()).isEqualTo("run-1:tc-1:1");
        assertThat(a.getReservationJson()).isNotNull();
    }

    @Test
    void checkpointShouldRejectWhenOperationIdMismatch() throws Exception {
        insertRun("run-2", "EXECUTING", """
            {"operationId":"run-2:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-2", AgentRunStatus.EXECUTING,
                "run-2:tc-2:1", "tc-1", 1, "task-123", 0,  // wrong operationId
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void checkpointShouldRejectWhenToolCallIdMismatch() throws Exception {
        insertRun("run-3", "EXECUTING", """
            {"operationId":"run-3:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-3", AgentRunStatus.EXECUTING,
                "run-3:tc-1:1", "tc-2", 1, "task-123", 0,  // wrong toolCallId
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void checkpointShouldRejectWhenAttemptMismatch() throws Exception {
        insertRun("run-4", "EXECUTING", """
            {"operationId":"run-4:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-4", AgentRunStatus.EXECUTING,
                "run-4:tc-1:1", "tc-1", 2, "task-123", 0,  // wrong attempt
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void checkpointShouldRejectWhenTaskIdMismatch() throws Exception {
        insertRun("run-5", "EXECUTING", """
            {"operationId":"run-5:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-999","checkpointVersion":0}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-5", AgentRunStatus.EXECUTING,
                "run-5:tc-1:1", "tc-1", 1, "task-123", 0,  // wrong taskId
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void checkpointShouldRejectWhenVersionMismatch() throws Exception {
        insertRun("run-6", "EXECUTING", """
            {"operationId":"run-6:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":5}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-6", AgentRunStatus.EXECUTING,
                "run-6:tc-1:1", "tc-1", 1, "task-123", 3,  // expected 3, DB has 5
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void checkpointShouldRejectWhenStatusMismatch() throws Exception {
        insertRun("run-7", "WAITING_TOOL_JOB", """
            {"operationId":"run-7:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-7", AgentRunStatus.EXECUTING,  // DB has WAITING_TOOL_JOB
                "run-7:tc-1:1", "tc-1", 1, "task-123", 0,
                "todo_1", 1, null, "{}", "d1", null, 0, null);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void dualCheckpointWritersOnlyFirstSucceeds() throws Exception {
        insertRun("run-8", "EXECUTING", """
            {"operationId":"run-8:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r8"}""");

        int rows1 = newMapper().updateToolJobCheckpoint(
                "run-8", AgentRunStatus.EXECUTING,
                "run-8:tc-1:1", "tc-1", 1, "task-123", 0,
                "todo_A", 1, null, "{\"w\":\"A\"}", "dA", null, 0, null);
        assertThat(rows1).isEqualTo(1);

        int rows2 = newMapper().updateToolJobCheckpoint(
                "run-8", AgentRunStatus.EXECUTING,
                "run-8:tc-1:1", "tc-1", 1, "task-123", 0,  // same version → fails
                "todo_B", 1, null, "{\"w\":\"B\"}", "dB", null, 0, null);
        assertThat(rows2).isEqualTo(0);

        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-8").getToolJobAnchorJson());
        assertThat(a.getTodoId()).isEqualTo("todo_A");
        assertThat(a.getCheckpointVersion()).isEqualTo(1);
        assertThat(a.getReservationJson()).isNotNull();
    }

    @Test
    void checkpointMergePreservesNonCheckpointFields() throws Exception {
        insertRun("run-9", "EXECUTING", """
            {"operationId":"run-9:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r99","terminalStatus":"SUCCEEDED","finalizerStep":"ENVELOPE","resumeState":"READY","resumeToken":"tok-xyz"}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-9", AgentRunStatus.EXECUTING,
                "run-9:tc-1:1", "tc-1", 1, "task-123", 0,
                "todo_new", 5, null, "{\"d\":\"data\"}", "dig99", null, 3, null);
        assertThat(rows).isEqualTo(1);

        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-9").getToolJobAnchorJson());
        assertThat(a.getTodoId()).isEqualTo("todo_new");
        assertThat(a.getToolCallsUsed()).isEqualTo(3);
        assertThat(a.getCheckpointVersion()).isEqualTo(1);
        assertThat(a.getDatasetSnapshotJson()).isEqualTo("{\"d\":\"data\"}");
        // Non-checkpoint preserved after fromJson roundtrip
        assertThat(a.getOperationId()).isEqualTo("run-9:tc-1:1");
        assertThat(a.getReservationJson()).isNotNull();
        assertThat(a.getTerminalStatus()).isEqualTo("SUCCEEDED");
        assertThat(a.getFinalizerStep()).isEqualTo("ENVELOPE");
        assertThat(a.getResumeState()).isEqualTo("READY");
        assertThat(a.getResumeToken()).isEqualTo("tok-xyz");
    }

    @Test
    void checkpointNullVersionHandledAsZero() throws Exception {
        insertRun("run-10", "EXECUTING", """
            {"operationId":"run-10:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123"}""");

        int rows = newMapper().updateToolJobCheckpoint(
                "run-10", AgentRunStatus.EXECUTING,
                "run-10:tc-1:1", "tc-1", 1, "task-123", 0,
                "todo_z", 1, null, "{}", "dz", null, 0, null);
        assertThat(rows).isEqualTo(1);

        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-10").getToolJobAnchorJson());
        assertThat(a.getCheckpointVersion()).isEqualTo(1);
    }

    @Test
    void checkpointFailureMergeOwnsWaitingRunAndPreservesAnchor() throws Exception {
        insertRun("run-f1", "WAITING_TOOL_JOB", """
            {"operationId":"run-f1:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":3,"reservationJson":"keep","terminalStatus":"SUCCEEDED","finalizerStep":"EVENT"}""");

        ToolJobCheckpointRequest request = ToolJobCheckpointRequest.builder("run-f1")
                .operationId("run-f1:tc-1:1").toolCallId("tc-1").attempt(1)
                .taskId("task-123").expectedCheckpointVersion(3).build();
        boolean persisted = new ToolJobAnchorService(newMapper()).markCheckpointFailed(
                request, "durable_checkpoint_write_failed");

        assertThat(persisted).isTrue();
        ToolJobAnchor anchor = ToolJobAnchor.fromJson(
                newMapper().findById("run-f1").getToolJobAnchorJson());
        assertThat(anchor.isAutoResume()).isFalse();
        assertThat(anchor.getRunDisposition()).isEqualTo("CHECKPOINT_FAILED");
        assertThat(anchor.getFinalizerError()).isEqualTo("durable_checkpoint_write_failed");
        assertThat(anchor.getReservationJson()).isEqualTo("keep");
        assertThat(anchor.getTerminalStatus()).isEqualTo("SUCCEEDED");
        assertThat(anchor.getFinalizerStep()).isEqualTo("EVENT");
    }

    @Test
    void checkpointFailureMergeRejectsStaleIdentityAndVersion() throws Exception {
        insertRun("run-f2", "WAITING_TOOL_JOB", """
            {"operationId":"run-f2:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":4}""");

        ToolJobAnchorService service = new ToolJobAnchorService(newMapper());
        ToolJobCheckpointRequest stale = ToolJobCheckpointRequest.builder("run-f2")
                .operationId("run-f2:tc-1:1").toolCallId("tc-1").attempt(1)
                .taskId("task-123").expectedCheckpointVersion(3).build();
        ToolJobCheckpointRequest wrongOperation = ToolJobCheckpointRequest.builder("run-f2")
                .operationId("run-f2:tc-2:1").toolCallId("tc-2").attempt(1)
                .taskId("task-123").expectedCheckpointVersion(4).build();
        ToolJobCheckpointRequest wrongTask = ToolJobCheckpointRequest.builder("run-f2")
                .operationId("run-f2:tc-1:1").toolCallId("tc-1").attempt(1)
                .taskId("task-other").expectedCheckpointVersion(4).build();

        assertThat(service.markCheckpointFailed(stale, "err")).isFalse();
        assertThat(service.markCheckpointFailed(wrongOperation, "err")).isFalse();
        assertThat(service.markCheckpointFailed(wrongTask, "err")).isFalse();
        ToolJobAnchor healthy = ToolJobAnchor.fromJson(
                newMapper().findById("run-f2").getToolJobAnchorJson());
        assertThat(healthy.getCheckpointVersion()).isEqualTo(4);
        assertThat(healthy.getRunDisposition()).isNull();
        assertThat(healthy.isAutoResume()).isTrue();
    }

    @Test
    void checkpointFailureRetryMarkerIsDurableAndCompareCleared() throws Exception {
        insertRun("run-fr", "WAITING_TOOL_JOB", """
            {"operationId":"run-fr:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123"}""");
        AgentRunMapper mapper = newMapper();
        String marker = ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX + "{\"runId\":\"run-fr\"}";

        assertThat(mapper.markToolJobCheckpointFailurePending(
                "run-fr", "run-fr:tc-1:1", "tc-1", 1, "task-123", 0, marker)).isEqualTo(1);
        assertThat(newMapper().findById("run-fr").getLastError()).isEqualTo(marker);
        assertThat(newMapper().clearToolJobCheckpointFailurePending("run-fr", "wrong")).isZero();
        assertThat(newMapper().clearToolJobCheckpointFailurePending("run-fr", marker)).isEqualTo(1);
        assertThat(newMapper().findById("run-fr").getLastError()).isNull();
    }

    @Test
    void checkpointFailureRetryMarkerRejectsReplacedOwnerTuple() throws Exception {
        insertRun("run-fr-stale", "WAITING_TOOL_JOB", """
            {"operationId":"run-fr-stale:tc-old:1","toolCallId":"tc-old","attempt":1,"taskId":"task-old","checkpointVersion":3}""");
        AgentRunMapper mapper = newMapper();
        ToolJobAnchor replacement = new ToolJobAnchor();
        replacement.setOperationId("run-fr-stale:tc-new:1");
        replacement.setToolCallId("tc-new");
        replacement.setAttempt(1);
        replacement.setTaskId("task-new");
        replacement.setCheckpointVersion(4);
        assertThat(mapper.updateToolJobAnchor(
                "run-fr-stale", replacement.toJson(), AgentRunStatus.WAITING_TOOL_JOB)).isEqualTo(1);

        String staleMarker = ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX + "{\"runId\":\"run-fr-stale\"}";
        assertThat(newMapper().markToolJobCheckpointFailurePending(
                "run-fr-stale", "run-fr-stale:tc-old:1", "tc-old", 1, "task-old", 3, staleMarker))
                .isZero();
        assertThat(newMapper().findById("run-fr-stale").getLastError()).isNull();
        ToolJobAnchor current = ToolJobAnchor.fromJson(
                newMapper().findById("run-fr-stale").getToolJobAnchorJson());
        assertThat(current.getOperationId()).isEqualTo("run-fr-stale:tc-new:1");
        assertThat(current.getTaskId()).isEqualTo("task-new");
        assertThat(current.getCheckpointVersion()).isEqualTo(4);
    }

    @Test
    void checkpointFailureRetryMarkerDoesNotOverwriteDifferentErrorOrMarker() throws Exception {
        insertRun("run-fr-error", "WAITING_TOOL_JOB", """
            {"operationId":"run-fr-error:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-1","checkpointVersion":2}""");
        String marker = ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX + "{\"runId\":\"run-fr-error\"}";

        updateLastError("run-fr-error", "existing_error");
        assertThat(newMapper().markToolJobCheckpointFailurePending(
                "run-fr-error", "run-fr-error:tc-1:1", "tc-1", 1, "task-1", 2, marker)).isZero();
        assertThat(newMapper().findById("run-fr-error").getLastError()).isEqualTo("existing_error");

        String otherMarker = ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX + "{\"runId\":\"other\"}";
        updateLastError("run-fr-error", otherMarker);
        assertThat(newMapper().markToolJobCheckpointFailurePending(
                "run-fr-error", "run-fr-error:tc-1:1", "tc-1", 1, "task-1", 2, marker)).isZero();
        assertThat(newMapper().findById("run-fr-error").getLastError()).isEqualTo(otherMarker);
    }

    // ========== casUpdateAnchorResumeState: claim CAS ==========

    @Test
    void claimCasShouldSucceedOnMatchingTokenVersionState() throws Exception {
        insertRun("run-c1", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"tok-abc","resumeLeaseVersion":5}""");

        ToolJobAnchor na = new ToolJobAnchor();
        na.setResumeState("LAUNCHING");
        na.setResumeToken("tok-abc");
        na.setResumeLeaseVersion(6);

        int rows = newMapper().casUpdateAnchorResumeState(
                "run-c1", na.toJson(), AgentRunStatus.RECEIVED, "READY", "tok-abc", 5);
        assertThat(rows).isEqualTo(1);

        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-c1").getToolJobAnchorJson());
        assertThat(a.getResumeState()).isEqualTo("LAUNCHING");
        assertThat(a.getResumeLeaseVersion()).isEqualTo(6);
    }

    @Test
    void claimCasShouldRejectWrongToken() throws Exception {
        insertRun("run-c2", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"tok-xyz","resumeLeaseVersion":3}""");

        ToolJobAnchor na = new ToolJobAnchor();
        na.setResumeState("LAUNCHING");
        int rows = newMapper().casUpdateAnchorResumeState(
                "run-c2", na.toJson(), AgentRunStatus.RECEIVED, "READY", "tok-wrong", 3);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void claimCasShouldRejectWrongVersion() throws Exception {
        insertRun("run-c3", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"tok-v","resumeLeaseVersion":10}""");

        ToolJobAnchor na = new ToolJobAnchor();
        int rows = newMapper().casUpdateAnchorResumeState(
                "run-c3", na.toJson(), AgentRunStatus.RECEIVED, "READY", "tok-v", 7);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void claimCasShouldRejectWrongResumeState() throws Exception {
        insertRun("run-c4", "RECEIVED", """
            {"resumeState":"LAUNCHING","resumeToken":"tok-abc","resumeLeaseVersion":5}""");

        ToolJobAnchor na = new ToolJobAnchor();
        int rows = newMapper().casUpdateAnchorResumeState(
                "run-c4", na.toJson(), AgentRunStatus.RECEIVED, "READY", "tok-abc", 5);
        assertThat(rows).isEqualTo(0);
    }

    // ========== clearToolJobAnchorWithToken ==========

    @Test
    void consumedClearShouldSucceedOnFullMatch() throws Exception {
        insertRun("run-cl1", "RECEIVED", """
            {"resumeState":"CONSUMED","resumeToken":"clear-tok","resumeLeaseVersion":8}""");

        int rows = newMapper().clearToolJobAnchorWithToken("run-cl1", "CONSUMED", "clear-tok", 8);
        assertThat(rows).isEqualTo(1);
        assertThat(newMapper().findById("run-cl1").getToolJobAnchorJson()).isEqualTo("{}");
    }

    @Test
    void consumedClearShouldRejectWhenStateNotConsumed() throws Exception {
        insertRun("run-cl2", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"old-tok","resumeLeaseVersion":9}""");

        int rows = newMapper().clearToolJobAnchorWithToken("run-cl2", "CONSUMED", "old-tok", 9);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void consumedClearShouldRejectWrongToken() throws Exception {
        insertRun("run-cl3", "RECEIVED", """
            {"resumeState":"CONSUMED","resumeToken":"real-tok","resumeLeaseVersion":5}""");

        int rows = newMapper().clearToolJobAnchorWithToken("run-cl3", "CONSUMED", "stale-tok", 5);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void activeDispatchClearRequiresStatusAndOperationIdentity() throws Exception {
        insertRun("run-dispatch-clear", "EXECUTING", """
            {"operationId":"run-dispatch-clear:call-1:1","anchorState":"TERMINAL"}""");

        AgentRunMapper mapper = newMapper();
        assertThat(mapper.clearActiveToolJobAnchor(
                "run-dispatch-clear", AgentRunStatus.WAITING_TOOL_JOB,
                "run-dispatch-clear:call-1:1")).isEqualTo(0);
        assertThat(mapper.clearActiveToolJobAnchor(
                "run-dispatch-clear", AgentRunStatus.EXECUTING,
                "run-dispatch-clear:call-stale:1")).isEqualTo(0);
        assertThat(mapper.clearActiveToolJobAnchor(
                "run-dispatch-clear", AgentRunStatus.EXECUTING,
                "run-dispatch-clear:call-1:1")).isEqualTo(1);
        assertThat(mapper.findById("run-dispatch-clear").getToolJobAnchorJson()).isEqualTo("{}");
    }

    @Test
    void dispatchClaimUpdateAndTransferAreOperationFenced() throws Exception {
        insertRun("run-dispatch-cas", "EXECUTING", "{}");
        AgentRunMapper mapper = newMapper();
        String preparing = """
            {"operationId":"run-dispatch-cas:call-1:1","anchorState":"PREPARING"}""";
        String attached = """
            {"operationId":"run-dispatch-cas:call-1:1","anchorState":"ATTACHED","taskId":"task-1"}""";
        String pending = """
            {"operationId":"run-dispatch-cas:call-1:1","anchorState":"PENDING","taskId":"task-1"}""";

        assertThat(mapper.claimPreparingToolJobAnchor(
                "run-dispatch-cas", preparing, AgentRunStatus.EXECUTING)).isEqualTo(1);
        assertThat(mapper.claimPreparingToolJobAnchor(
                "run-dispatch-cas", preparing, AgentRunStatus.EXECUTING)).isEqualTo(0);
        assertThat(mapper.updateActiveToolJobAnchor(
                "run-dispatch-cas", attached, AgentRunStatus.EXECUTING,
                "run-dispatch-cas:stale:1")).isEqualTo(0);
        assertThat(mapper.updateActiveToolJobAnchor(
                "run-dispatch-cas", attached, AgentRunStatus.EXECUTING,
                "run-dispatch-cas:call-1:1")).isEqualTo(1);
        assertThat(mapper.updateToolJobAnchorAndStatusByOperation(
                "run-dispatch-cas", pending, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-dispatch-cas:stale:1")).isEqualTo(0);
        assertThat(mapper.updateToolJobAnchorAndStatusByOperation(
                "run-dispatch-cas", pending, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-dispatch-cas:call-1:1")).isEqualTo(1);
        assertThat(mapper.findById("run-dispatch-cas").getStatus())
                .isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);
    }

    @Test
    void acceptedResumeHandoffReturnsToExecutingAndAllowsExactSecondPreparing() throws Exception {
        insertRun("run-resume-dispatch", "RECEIVED", """
            {"operationId":"run-resume-dispatch:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"resume-token","resumeLeaseVersion":4,
             "resumeLauncherOwnerId":"owner-1",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":false}""");
        AgentRunMapper mapper = newMapper();
        String accepted = """
            {"operationId":"run-resume-dispatch:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"resume-token","resumeLeaseVersion":4,
             "resumeLauncherOwnerId":"owner-1",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":true}""";
        assertThat(mapper.acceptResumeHandoff(
                "run-resume-dispatch", accepted, "resume-token", 4L, "owner-1", 30L))
                .isEqualTo(1);
        assertThat(mapper.findById("run-resume-dispatch").getStatus())
                .isEqualTo(AgentRunStatus.EXECUTING);

        String nextPreparing = """
            {"operationId":"run-resume-dispatch:call-2:1","anchorState":"PREPARING"}""";
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-resume-dispatch", nextPreparing, "stale-token", 4L)).isEqualTo(0);
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-resume-dispatch", nextPreparing, "resume-token", 3L)).isEqualTo(0);
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-resume-dispatch", nextPreparing, "resume-token", 4L)).isEqualTo(1);
        assertThat(mapper.findById("run-resume-dispatch").getToolJobAnchorJson())
                .contains("run-resume-dispatch:call-2:1", "PREPARING")
                .doesNotContain("resume-token");
    }

    // ========== 260818: batch 20260818-182948 root causes ==========

    /**
     * Root cause 1: production {@code markHandoffAccepted} advances the anchor to
     * resumeState=ACCEPTED (resultConsumed=true) for the WHOLE resumed execution,
     * so the second long tool necessarily meets an ACCEPTED handoff — the SQL
     * previously matched only 'LAUNCHING' and both CAS paths failed
     * (TOOL_JOB_ANCHOR_INVALID ×15, 5-17ms each).
     */
    @Test
    void secondPreparingReplacesAcceptedStateHandoffDuringResumedExecution() throws Exception {
        insertRun("run-accepted-handoff", "EXECUTING", """
            {"operationId":"run-accepted-handoff:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"accepted-token","resumeLeaseVersion":11,
             "resumeLauncherOwnerId":"owner-1",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":true}""");
        AgentRunMapper mapper = newMapper();
        String nextPreparing = """
            {"operationId":"run-accepted-handoff:call-2:1","anchorState":"PREPARING"}""";

        // token/version fences stay intact under the widened resumeState match
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-accepted-handoff", nextPreparing, "stale-token", 11L)).isEqualTo(0);
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-accepted-handoff", nextPreparing, "accepted-token", 10L)).isEqualTo(0);
        // the 260818 fix: exact credentials replace the ACCEPTED handoff
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-accepted-handoff", nextPreparing, "accepted-token", 11L)).isEqualTo(1);
        assertThat(mapper.findById("run-accepted-handoff").getToolJobAnchorJson())
                .contains("run-accepted-handoff:call-2:1", "PREPARING")
                .doesNotContain("accepted-token");
    }

    @Test
    void secondPreparingStillRejectedWhenAcceptedHandoffNotConsumed() throws Exception {
        // resultConsumed=false means the terminal result is not durable yet —
        // the handoff must NOT be replaceable (fail-closed retention).
        insertRun("run-accepted-unconsumed", "EXECUTING", """
            {"operationId":"run-accepted-unconsumed:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok","resumeLeaseVersion":3,
             "resumeLauncherOwnerId":"owner-1",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":false}""");
        AgentRunMapper mapper = newMapper();
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-accepted-unconsumed",
                "{\"operationId\":\"run-accepted-unconsumed:call-2:1\",\"anchorState\":\"PREPARING\"}",
                "tok", 3L)).isEqualTo(0);
        assertThat(mapper.findById("run-accepted-unconsumed").getToolJobAnchorJson())
                .contains("run-accepted-unconsumed:call-1:1");
    }

    /**
     * Root cause 2: cancel landing after markHandoffAccepted leaves the run
     * EXECUTING with a CANCELED-disposition anchor; the finalizer's terminal
     * CAS previously required WAITING_TOOL_JOB only and retried forever
     * (5s finalizer loop + ~60s resume takeover loop, resumeLeaseVersion → 27).
     */
    @Test
    void cancelTerminalCasAcceptsExecutingAndWaitingToolJobAndFencesOperation() throws Exception {
        // EXECUTING + matching operationId → CANCELED lands (the fixed gap)
        insertRun("run-cancel-exec", "EXECUTING", """
            {"operationId":"run-cancel-exec:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok","resumeLeaseVersion":27,
             "runDisposition":"CANCELED","resultConsumed":true}""");
        AgentRunMapper mapper = newMapper();
        String canceledAnchor = """
            {"operationId":"run-cancel-exec:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok","resumeLeaseVersion":27,
             "runDisposition":"CANCELED","finalizerStep":"CANCELED","resultConsumed":true}""";
        assertThat(mapper.cancelToolJobAnchorFromStatuses(
                "run-cancel-exec", canceledAnchor, AgentRunStatus.CANCELED,
                "run-cancel-exec:call-1:1")).isEqualTo(1);
        assertThat(mapper.findById("run-cancel-exec").getStatus())
                .isEqualTo(AgentRunStatus.CANCELED);

        // Wrong operationId (anchor replaced by a newer dispatch) → fenced out,
        // neither status nor anchor changes
        insertRun("run-cancel-fence", "EXECUTING", """
            {"operationId":"run-cancel-fence:call-2:1","anchorState":"PREPARING"}""");
        assertThat(mapper.cancelToolJobAnchorFromStatuses(
                "run-cancel-fence", canceledAnchor, AgentRunStatus.CANCELED,
                "run-cancel-fence:call-1:1")).isEqualTo(0);
        AgentRun fenced = mapper.findById("run-cancel-fence");
        assertThat(fenced.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(fenced.getToolJobAnchorJson()).contains("call-2:1").doesNotContain("CANCELED");

        // WAITING_TOOL_JOB regression: the normal background-wait cancel still lands
        insertRun("run-cancel-wait", "WAITING_TOOL_JOB", """
            {"operationId":"run-cancel-wait:call-1:1","anchorState":"PENDING",
             "runDisposition":"CANCELED"}""");
        assertThat(mapper.cancelToolJobAnchorFromStatuses(
                "run-cancel-wait",
                "{\"operationId\":\"run-cancel-wait:call-1:1\",\"anchorState\":\"PENDING\","
                        + "\"runDisposition\":\"CANCELED\",\"finalizerStep\":\"CANCELED\"}",
                AgentRunStatus.CANCELED, "run-cancel-wait:call-1:1")).isEqualTo(1);
        assertThat(mapper.findById("run-cancel-wait").getStatus())
                .isEqualTo(AgentRunStatus.CANCELED);

        // Any other status (e.g. RECEIVED) → rejected
        insertRun("run-cancel-other", "RECEIVED", """
            {"operationId":"run-cancel-other:call-1:1","anchorState":"TERMINAL",
             "runDisposition":"CANCELED"}""");
        assertThat(mapper.cancelToolJobAnchorFromStatuses(
                "run-cancel-other",
                "{\"operationId\":\"run-cancel-other:call-1:1\",\"anchorState\":\"TERMINAL\","
                        + "\"runDisposition\":\"CANCELED\",\"finalizerStep\":\"CANCELED\"}",
                AgentRunStatus.CANCELED, "run-cancel-other:call-1:1")).isEqualTo(0);
        assertThat(mapper.findById("run-cancel-other").getStatus())
                .isEqualTo(AgentRunStatus.RECEIVED);
    }

    @Test
    void acceptedExecutingHandoffWithLiveLeaseIsNotRequeuedOrTreatedAsDispatch() throws Exception {
        insertRun("run-resume-scan", "EXECUTING", """
            {"operationId":"run-resume-scan:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"scan-token","resumeLeaseVersion":8,
             "resumeLauncherOwnerId":"owner-live",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z",
             "resultConsumed":true}""");
        AgentRunMapper mapper = newMapper();

        assertThat(mapper.listResumeReadyAnchors(10))
                .extracting(AgentRun::getId)
                .doesNotContain("run-resume-scan");
        assertThat(mapper.listActiveToolJobAnchors(10))
                .extracting(AgentRun::getId)
                .doesNotContain("run-resume-scan");
    }

    /**
     * 260818（grace round-1）：正常 autoResume（默认 true）的 EXECUTING+ACCEPTED+consumed
     * handoff 由恢复 worker 持有，不能被 60s 补扫写回 due 再次进入 Sandbox finalizer；
     * 取消后的 autoResume=false ACCEPTED 仍必须能被补扫发现并走终态收口。
     */
    @Test
    void activeBackfillExcludesNormalAcceptedHandoffButKeepsCanceledAccepted() throws Exception {
        // 正常恢复执行中：autoResume 缺省 = true → 排除
        insertRun("run-accepted-normal", "EXECUTING", """
            {"operationId":"run-accepted-normal:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-n","resumeLeaseVersion":5,
             "resumeLauncherOwnerId":"owner-n",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":true}""");
        // 取消 disposition 落在恢复执行期：autoResume=false → 保留（终态收口要发现它）
        insertRun("run-accepted-cancel", "EXECUTING", """
            {"operationId":"run-accepted-cancel:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-c","resumeLeaseVersion":6,
             "resumeLauncherOwnerId":"owner-c",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":true,
             "autoResume":false,"runDisposition":"CANCELED"}""");

        AgentRunMapper mapper = newMapper();
        assertThat(mapper.listActiveToolJobAnchors(10))
                .extracting(AgentRun::getId)
                .doesNotContain("run-accepted-normal")
                .contains("run-accepted-cancel");
    }

    @Test
    void twoIndependentLaunchersOnlyFirstReadyClaimWins() throws Exception {
        insertRun("run-resume-claim", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"ready-token","resumeLeaseVersion":1,
             "resultConsumed":false}""");
        String launchingA = """
            {"resumeState":"LAUNCHING","resumeToken":"ready-token","resumeLeaseVersion":2,
             "resumeLauncherOwnerId":"owner-a","resultConsumed":false}""";
        String launchingB = """
            {"resumeState":"LAUNCHING","resumeToken":"ready-token","resumeLeaseVersion":2,
             "resumeLauncherOwnerId":"owner-b","resultConsumed":false}""";

        assertThat(newMapper().claimResumeLauncher(
                "run-resume-claim", launchingA,
                AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED,
                "ready-token", 1L, "owner-a", 30L)).isEqualTo(1);
        assertThat(newMapper().claimResumeLauncher(
                "run-resume-claim", launchingB,
                AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED,
                "ready-token", 1L, "owner-b", 30L)).isZero();

        ToolJobAnchor claimed = ToolJobAnchor.fromJson(
                newMapper().findById("run-resume-claim").getToolJobAnchorJson());
        assertThat(claimed.getResumeLauncherOwnerId()).isEqualTo("owner-a");
        assertThat(claimed.getResumeLeaseVersion()).isEqualTo(2L);
        assertThat(claimed.getResumeLauncherLeaseUntil()).isNotNull();
        assertThat(newMapper().listResumeReadyAnchors(10))
                .extracting(AgentRun::getId)
                .doesNotContain("run-resume-claim");
    }

    @Test
    void expiredTakeoverRotatesLeaseAndFencesOldTerminalWriterAndCleanup() throws Exception {
        insertRun("run-resume-takeover", "EXECUTING", """
            {"operationId":"run-resume-takeover:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"token-old","resumeLeaseVersion":6,
             "resumeLauncherOwnerId":"owner-old",
             "resumeLauncherLeaseUntil":"2000-01-01T00:00:00Z","resultConsumed":true}""");
        updateUserId("run-resume-takeover", "user-1");

        AgentRunMapper mapper = newMapper();
        assertThat(mapper.listResumeReadyAnchors(10))
                .extracting(AgentRun::getId)
                .contains("run-resume-takeover");
        assertThat(mapper.heartbeatResumeLauncher(
                "run-resume-takeover", "token-old", 6L, "owner-old", 30L)).isZero();
        assertThat(mapper.clearAcceptedResumeHandoff(
                "run-resume-takeover", "token-old", 6L, "owner-old")).isZero();

        String takeover = """
            {"operationId":"run-resume-takeover:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"token-new","resumeLeaseVersion":7,
             "resumeLauncherOwnerId":"owner-new","resultConsumed":true}""";
        assertThat(mapper.takeoverExpiredResumeLauncher(
                "run-resume-takeover", takeover, AgentRunStatus.EXECUTING,
                "token-old", 6L, "owner-old", "owner-new", 30L, 120L)).isEqualTo(1);

        assertThat(mapper.updateResumedTerminal(
                "run-resume-takeover", "user-1", AgentRunStatus.COMPLETED,
                "{\"winner\":\"old\"}", "{\"status\":\"old\"}", true, null,
                "token-old", 6L, "owner-old")).isZero();
        assertThat(mapper.clearAcceptedResumeHandoff(
                "run-resume-takeover", "token-old", 6L, "owner-old")).isZero();

        assertThat(mapper.updateResumedTerminal(
                "run-resume-takeover", "user-1", AgentRunStatus.COMPLETED,
                "{\"winner\":\"new\"}", "{\"status\":\"COMPLETED\"}", true, null,
                "token-new", 7L, "owner-new")).isEqualTo(1);
        assertThat(mapper.clearAcceptedResumeHandoff(
                "run-resume-takeover", "token-new", 7L, "owner-new")).isEqualTo(1);

        AgentRun completed = mapper.findById("run-resume-takeover");
        assertThat(completed.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.getPlanJson()).contains("new").doesNotContain("old");
        assertThat(completed.getSnapshotJson()).contains("COMPLETED").doesNotContain("old");
        assertThat(completed.getToolJobAnchorJson()).isEqualTo("{}");
    }

    @Test
    void activeDispatchScanIncludesExecutingAnchorForCrashRecovery() throws Exception {
        insertRun("run-dispatch-active", "EXECUTING", """
            {"operationId":"run-dispatch-active:call-1:1","anchorState":"PREPARING"}""");

        assertThat(newMapper().listActiveToolJobAnchors(10))
                .extracting(run -> run.getId())
                .contains("run-dispatch-active");
    }

    @Test
    void dataAnalysisObservabilityCasSupportsMissingExpectedAndRejectsStaleWriter() throws Exception {
        insertRun("run-obs-1", "EXECUTING", "{}");
        AgentRunMapper mapper = newMapper();
        String first = """
                {"version":1,"runId":"run-obs-1","summary":{"toolCallCount":0},"calls":[]}
                """;
        String second = """
                {"version":1,"runId":"run-obs-1","summary":{"toolCallCount":1},"calls":[{"id":"a"}]}
                """;

        assertThat(mapper.casUpdateDataAnalysisObservability("run-obs-1", null, first)).isEqualTo(1);
        assertThat(mapper.casUpdateDataAnalysisObservability("run-obs-1", null, second)).isEqualTo(0);
        assertThat(mapper.casUpdateDataAnalysisObservability("run-obs-1", first, second)).isEqualTo(1);
        assertThat(mapper.findDataAnalysisObservabilityJsonById("run-obs-1"))
                .contains("\"toolCallCount\": 1");
        assertThat(mapper.findDataAnalysisObservabilitySummaryJsonById("run-obs-1"))
                .contains("\"toolCallCount\": 1");
    }

    /**
     * 260818（grace round-2）：取消与恢复的三层封锁在数据库层的证明。
     * 取消写（updateAnchor）不轮换 token/version——恢复线程持旧 autoResume=true 对象、
     * 取消线程先落 autoResume=false+CANCELED 时，三个所有权 CAS（claim / takeover /
     * accept）都必须因数据库 autoResume 栅栏返回 0，且取消字段保持不变。
     */
    @Test
    void staleResumeWriterCannotOverwriteCancelDisposition() throws Exception {
        AgentRunMapper mapper = newMapper();

        // takeover 场景：EXECUTING+ACCEPTED+consumed，取消已落，租约已过期。
        insertRun("run-race-takeover", "EXECUTING", """
            {"operationId":"run-race-takeover:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-t","resumeLeaseVersion":7,
             "resumeLauncherOwnerId":"owner-old",
             "resumeLauncherLeaseUntil":"2000-01-01T00:00:00Z","resultConsumed":true,
             "autoResume":false,"runDisposition":"CANCELED"}""");
        // 恢复线程手里的旧对象：autoResume 仍是 true，token/version 与数据库一致。
        String staleTakeover = """
            {"operationId":"run-race-takeover:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-t","resumeLeaseVersion":7,
             "resumeLauncherOwnerId":"owner-old","resultConsumed":true,
             "autoResume":true,"resumeLauncherLeaseUntil":"2000-01-01T00:00:00Z"}""";
        assertThat(mapper.takeoverExpiredResumeLauncher(
                "run-race-takeover", staleTakeover, AgentRunStatus.EXECUTING,
                "tok-t", 7L, "owner-old", "owner-new", 30L, 120L)).isZero();
        ToolJobAnchor afterTakeover = ToolJobAnchor.fromJson(
                mapper.findById("run-race-takeover").getToolJobAnchorJson());
        assertThat(afterTakeover.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(afterTakeover.isAutoResume()).isFalse();

        // claim 场景：RECEIVED+READY，取消已落。
        insertRun("run-race-claim", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"tok-r","resumeLeaseVersion":2,
             "autoResume":false,"runDisposition":"CANCELED"}""");
        String staleClaim = """
            {"resumeState":"LAUNCHING","resumeToken":"tok-r","resumeLeaseVersion":3,
             "resumeLauncherOwnerId":"owner-new","autoResume":true}""";
        assertThat(mapper.claimResumeLauncher(
                "run-race-claim", staleClaim, AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED,
                "tok-r", 2L, "owner-new", 30L)).isZero();
        ToolJobAnchor afterClaim = ToolJobAnchor.fromJson(
                mapper.findById("run-race-claim").getToolJobAnchorJson());
        assertThat(afterClaim.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(afterClaim.isAutoResume()).isFalse();

        // accept 场景：RECEIVED+LAUNCHING 未消费、租约未过期，取消已落。
        insertRun("run-race-accept", "RECEIVED", """
            {"operationId":"run-race-accept:call-1:1","anchorState":"TERMINAL",
             "resumeState":"LAUNCHING","resumeToken":"tok-a","resumeLeaseVersion":4,
             "resumeLauncherOwnerId":"owner-live",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":false,
             "autoResume":false,"runDisposition":"CANCELED"}""");
        String staleAccept = """
            {"operationId":"run-race-accept:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-a","resumeLeaseVersion":4,
             "resumeLauncherOwnerId":"owner-live","resultConsumed":true,
             "autoResume":true}""";
        assertThat(mapper.acceptResumeHandoff(
                "run-race-accept", staleAccept, "tok-a", 4L, "owner-live", 30L)).isZero();
        ToolJobAnchor afterAccept = ToolJobAnchor.fromJson(
                mapper.findById("run-race-accept").getToolJobAnchorJson());
        assertThat(afterAccept.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(afterAccept.isAutoResume()).isFalse();
    }

    @Test
    void resumeReadyScanExcludesCanceledAnchorButKeepsNormalExpiredHandoff() throws Exception {
        // 正常 autoResume 的过期 ACCEPTED handoff 仍要被恢复补扫发现（恢复服务接管重试）
        insertRun("run-scan-normal", "EXECUTING", """
            {"operationId":"run-scan-normal:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-n","resumeLeaseVersion":9,
             "resumeLauncherOwnerId":"owner-n",
             "resumeLauncherLeaseUntil":"2000-01-01T00:00:00Z","resultConsumed":true}""");
        // 取消锚点（autoResume=false）不进恢复补扫——由终态处理路径负责
        insertRun("run-scan-cancel", "EXECUTING", """
            {"operationId":"run-scan-cancel:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-c","resumeLeaseVersion":10,
             "resumeLauncherOwnerId":"owner-c",
             "resumeLauncherLeaseUntil":"2000-01-01T00:00:00Z","resultConsumed":true,
             "autoResume":false,"runDisposition":"CANCELED"}""");

        AgentRunMapper mapper = newMapper();
        assertThat(mapper.listResumeReadyAnchors(10))
                .extracting(AgentRun::getId)
                .contains("run-scan-normal")
                .doesNotContain("run-scan-cancel");
    }

    /**
     * 260818（grace round-3）：取消先落库后，身份完全匹配的旧恢复线程在
     * 第二长工具接管 / 恢复终态写入 / 恢失败回滚三组入口都必须被数据库
     * autoResume 栅栏拒绝，取消字段保持原样。
     */
    @Test
    void cancelDispositionBlocksSecondPreparingTakeoverTerminalWriteAndRollback() throws Exception {
        AgentRunMapper mapper = newMapper();
        updateUserId("run-r3", "user-1");

        // ① 第二长工具接管：EXECUTING+ACCEPTED+consumed，取消已落，身份字段与旧对象一致
        insertRun("run-r3", "EXECUTING", """
            {"operationId":"run-r3:call-1:1","anchorState":"TERMINAL",
             "resumeState":"ACCEPTED","resumeToken":"tok-3","resumeLeaseVersion":9,
             "resumeLauncherOwnerId":"owner-3",
             "resumeLauncherLeaseUntil":"2999-01-01T00:00:00Z","resultConsumed":true,
             "autoResume":false,"runDisposition":"CANCELED"}""");
        String secondPreparing = """
            {"operationId":"run-r3:call-2:1","anchorState":"PREPARING"}""";
        assertThat(mapper.claimPreparingToolJobAnchorFromResume(
                "run-r3", secondPreparing, "tok-3", 9L)).isZero();

        // ② 恢复终态写入：同锚点（仍 EXECUTING、租约未过期），旧工作流尝试写 COMPLETED
        assertThat(mapper.updateResumedTerminal(
                "run-r3", "user-1", AgentRunStatus.COMPLETED,
                "{\"plan\":\"new\"}", "{\"snapshot\":\"done\"}", true, null,
                "tok-3", 9L, "owner-3")).isZero();
        assertThat(mapper.findById("run-r3").getStatus()).isEqualTo(AgentRunStatus.EXECUTING);

        // ③ 恢失败回滚：takeover 成功后的旧对象尝试退回 READY / ACCEPTED
        //    （cancel 落在 claim/takeover 与回滚之间）
        String rollbackJson = """
            {"operationId":"run-r3:call-1:1","anchorState":"TERMINAL",
             "resumeState":"READY","resumeToken":"tok-3","resumeLeaseVersion":10,
             "autoResume":true}""";
        assertThat(mapper.casUpdateAnchorResumeState(
                "run-r3", rollbackJson, AgentRunStatus.EXECUTING,
                "ACCEPTED", "tok-3", 9L)).isZero();
        String rollbackWithStatus = """
            {"operationId":"run-r3:call-1:1","anchorState":"TERMINAL",
             "resumeState":"READY","resumeToken":"tok-3","resumeLeaseVersion":10,
             "autoResume":true}""";
        assertThat(mapper.casUpdateAnchorResumeStateAndStatus(
                "run-r3", rollbackWithStatus, AgentRunStatus.RECEIVED, AgentRunStatus.EXECUTING,
                "ACCEPTED", "tok-3", 9L)).isZero();

        // 复读数据库：三组入口全部被拒，取消字段原样
        ToolJobAnchor after = ToolJobAnchor.fromJson(
                mapper.findById("run-r3").getToolJobAnchorJson());
        assertThat(after.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(after.isAutoResume()).isFalse();
        assertThat(after.getResumeState()).isEqualTo("ACCEPTED");
        assertThat(mapper.findById("run-r3").getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
    }

    // ========== Production service chain: version race ==========

    private ToolJobCheckpointService newServiceChain() throws Exception {
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        return new ToolJobCheckpointService(mapper, anchorService);
    }

    @Test
    void staleCheckpointRequestCannotBorrowNewerDbVersion() throws Exception {
        insertRun("run-s1", "EXECUTING", """
            {"operationId":"run-s1:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        ToolJobCheckpointService svc = newServiceChain();

        var req1 = ToolJobCheckpointRequest.builder("run-s1")
                .operationId("run-s1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_A").sequence(1).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("[]").toolCallsUsed(1).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(svc.captureAndSave(req1)).isTrue();

        // DB version now 1; stale request still expects 0 → rejected
        var req2 = ToolJobCheckpointRequest.builder("run-s1")
                .operationId("run-s1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0) // stale: captured before first write
                .todoId("todo_B").sequence(2).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("[]").toolCallsUsed(2).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(svc.captureAndSave(req2)).isFalse();

        ToolJobAnchor a = ToolJobAnchor.fromJson(newMapper().findById("run-s1").getToolJobAnchorJson());
        assertThat(a.getTodoId()).isEqualTo("todo_A");
        assertThat(a.getCheckpointVersion()).isEqualTo(1);
    }

    @Test
    void twoSameVersionServiceRequestsOnlyFirstSucceeds() throws Exception {
        insertRun("run-s2", "EXECUTING", """
            {"operationId":"run-s2:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        var req = ToolJobCheckpointRequest.builder("run-s2")
                .operationId("run-s2:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_1").sequence(1).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("[]").toolCallsUsed(1).estimateJson(VALID_ESTIMATE_JSON)
                .build();

        ToolJobCheckpointService svc1 = newServiceChain();
        assertThat(svc1.captureAndSave(req)).isTrue();

        ToolJobCheckpointService svc2 = newServiceChain();
        assertThat(svc2.captureAndSave(req)).isFalse();
    }
}
