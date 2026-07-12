package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ToolJobAnchorMapperIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static Connection conn;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    status VARCHAR(32) NOT NULL,
                    tool_job_anchor_json JSONB DEFAULT '{}'
                )""");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
    }

    private void insertRun(String id, String status, String anchorJson) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    private String getAnchorJson(String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tool_job_anchor_json::text FROM alphafrog_agent_run WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ========== updateToolJobCheckpoint: atomic merge CAS ==========

    @Test
    void checkpointShouldUpdateWhenAllIdentityMatch() throws Exception {
        insertRun("run-1", "EXECUTING", """
            {"operationId":"run-1:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r1"}""");

        int rows;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('sequence', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ?
                  AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_3");
            ps.setString(2, "3");
            ps.setString(3, "{\"digest\":\"abc\"}");
            ps.setString(4, "abc123");
            ps.setString(5, "2");
            ps.setString(6, "run-1");
            ps.setString(7, "EXECUTING");
            ps.setString(8, "run-1:tc-1:1");
            ps.setString(9, "tc-1");
            ps.setInt(10, 1);
            ps.setString(11, "task-123");
            ps.setInt(12, 0);
            rows = ps.executeUpdate();
        }

        assertThat(rows).isEqualTo(1);

        String json = getAnchorJson("run-1");
        assertThat(json).contains("\"checkpointVersion\": 1");
        assertThat(json).contains("\"todoId\": \"todo_3\"");
        assertThat(json).contains("\"datasetSnapshotDigest\": \"abc123\"");
        assertThat(json).contains("\"reservationJson\"");
        assertThat(json).contains("\"operationId\"");
    }

    @Test
    void checkpointShouldRejectWhenOperationIdMismatch() throws Exception {
        insertRun("run-2", "EXECUTING", """
            {"operationId":"run-2:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_1");
            ps.setString(2, "{}");
            ps.setString(3, "d1");
            ps.setString(4, "0");
            ps.setString(5, "run-2");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-2:tc-2:1");  // wrong operationId
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 0);
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    @Test
    void checkpointShouldRejectWhenVersionMismatch() throws Exception {
        insertRun("run-3", "EXECUTING", """
            {"operationId":"run-3:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":5}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_1");
            ps.setString(2, "{}");
            ps.setString(3, "d1");
            ps.setString(4, "0");
            ps.setString(5, "run-3");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-3:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 3);  // expected 3, DB has 5 → mismatch
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    @Test
    void checkpointShouldRejectWhenTaskIdMismatch() throws Exception {
        insertRun("run-4", "EXECUTING", """
            {"operationId":"run-4:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-999","checkpointVersion":0}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_1");
            ps.setString(2, "{}");
            ps.setString(3, "d1");
            ps.setString(4, "0");
            ps.setString(5, "run-4");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-4:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");  // wrong taskId
            ps.setInt(11, 0);
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    @Test
    void dualCheckpointWritersOnlyOneSucceeds() throws Exception {
        insertRun("run-5", "EXECUTING", """
            {"operationId":"run-5:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r5"}""");

        // First writer: version 0 → bumps to 1
        int rows1;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_A");
            ps.setString(2, "{\"w\":\"A\"}");
            ps.setString(3, "dA");
            ps.setString(4, "0");
            ps.setString(5, "run-5");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-5:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 0);
            rows1 = ps.executeUpdate();
        }
        assertThat(rows1).isEqualTo(1);

        // Second writer: also expects version 0 → FAILS (DB now has version 1)
        int rows2;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_B");
            ps.setString(2, "{\"w\":\"B\"}");
            ps.setString(3, "dB");
            ps.setString(4, "0");
            ps.setString(5, "run-5");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-5:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 0);  // expects version 0 → DB has 1 → 0 rows
            rows2 = ps.executeUpdate();
        }
        assertThat(rows2).isEqualTo(0);

        String json = getAnchorJson("run-5");
        assertThat(json).contains("\"todoId\": \"todo_A\"");
        assertThat(json).contains("\"checkpointVersion\": 1");
        assertThat(json).doesNotContain("todo_B");
        assertThat(json).contains("\"reservationJson\"");
    }

    @Test
    void checkpointMergePreservesReservationAndTerminalFields() throws Exception {
        insertRun("run-6", "EXECUTING", """
            {"operationId":"run-6:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123","checkpointVersion":0,"reservationJson":"r99","terminalStatus":"SUCCEEDED","finalizerStep":"ENVELOPE"}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_99");
            ps.setString(2, "{\"d\":\"data\"}");
            ps.setString(3, "digest99");
            ps.setString(4, "0");
            ps.setString(5, "run-6");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-6:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 0);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        String json = getAnchorJson("run-6");
        assertThat(json).contains("\"todoId\": \"todo_99\"");
        assertThat(json).contains("\"checkpointVersion\": 1");
        assertThat(json).contains("\"reservationJson\": \"r99\"");
        assertThat(json).contains("\"terminalStatus\": \"SUCCEEDED\"");
        assertThat(json).contains("\"finalizerStep\": \"ENVELOPE\"");
        assertThat(json).contains("\"operationId\"");
    }

    @Test
    void checkpointShouldHandleNullVersionAsZero() throws Exception {
        insertRun("run-7", "EXECUTING", """
            {"operationId":"run-7:tc-1:1","toolCallId":"tc-1","attempt":1,"taskId":"task-123"}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run SET tool_job_anchor_json = tool_job_anchor_json
                    || jsonb_build_object('checkpointVersion', (COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) + 1)::text::jsonb)
                    || jsonb_build_object('todoId', to_jsonb(?::text))
                    || jsonb_build_object('datasetSnapshotJson', CAST(? AS jsonb))
                    || jsonb_build_object('datasetSnapshotDigest', to_jsonb(?::text))
                    || jsonb_build_object('toolCallsUsed', to_jsonb(?::text))
                WHERE id = ? AND status = ? AND tool_job_anchor_json #>> '{operationId}' = ?
                  AND tool_job_anchor_json #>> '{toolCallId}' = ?
                  AND (tool_job_anchor_json #>> '{attempt}')::int = ?
                  AND tool_job_anchor_json #>> '{taskId}' = ?
                  AND COALESCE((tool_job_anchor_json #>> '{checkpointVersion}')::int, 0) = ?""")) {
            ps.setString(1, "todo_nullv");
            ps.setString(2, "{}");
            ps.setString(3, "d_nullv");
            ps.setString(4, "0");
            ps.setString(5, "run-7");
            ps.setString(6, "EXECUTING");
            ps.setString(7, "run-7:tc-1:1");
            ps.setString(8, "tc-1");
            ps.setInt(9, 1);
            ps.setString(10, "task-123");
            ps.setInt(11, 0);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertThat(getAnchorJson("run-7")).contains("\"checkpointVersion\": 1");
    }

    // ========== casUpdateAnchorResumeState: claim CAS ==========

    @Test
    void claimCasShouldSucceedOnMatchingTokenVersionState() throws Exception {
        insertRun("run-c1", "RECEIVED", """
            {"operationId":"run-c1:tc-1:1","resumeState":"READY","resumeToken":"tok-abc","resumeLeaseVersion":5}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST(? AS jsonb)
                WHERE id = ? AND status = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "{\"resumeState\":\"LAUNCHING\",\"resumeToken\":\"tok-abc\",\"resumeLeaseVersion\":6}");
            ps.setString(2, "run-c1");
            ps.setString(3, "RECEIVED");
            ps.setString(4, "READY");
            ps.setString(5, "tok-abc");
            ps.setLong(6, 5);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        String json = getAnchorJson("run-c1");
        assertThat(json).contains("\"resumeState\": \"LAUNCHING\"");
        assertThat(json).contains("\"resumeLeaseVersion\": 6");
    }

    @Test
    void claimCasShouldRejectWrongToken() throws Exception {
        insertRun("run-c2", "RECEIVED", """
            {"operationId":"run-c2:tc-1:1","resumeState":"READY","resumeToken":"tok-xyz","resumeLeaseVersion":3}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST(? AS jsonb)
                WHERE id = ? AND status = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "{\"resumeState\":\"LAUNCHING\",\"resumeToken\":\"tok-xyz\",\"resumeLeaseVersion\":4}");
            ps.setString(2, "run-c2");
            ps.setString(3, "RECEIVED");
            ps.setString(4, "READY");
            ps.setString(5, "tok-wrong");  // wrong token
            ps.setLong(6, 3);
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    @Test
    void claimCasShouldRejectWrongVersion() throws Exception {
        insertRun("run-c3", "RECEIVED", """
            {"operationId":"run-c3:tc-1:1","resumeState":"READY","resumeToken":"tok-v","resumeLeaseVersion":10}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST(? AS jsonb)
                WHERE id = ? AND status = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "{\"resumeState\":\"LAUNCHING\",\"resumeToken\":\"tok-v\",\"resumeLeaseVersion\":11}");
            ps.setString(2, "run-c3");
            ps.setString(3, "RECEIVED");
            ps.setString(4, "READY");
            ps.setString(5, "tok-v");
            ps.setLong(6, 7);  // wrong version: DB has 10, expected 7
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    // ========== clearToolJobAnchorWithToken ==========

    @Test
    void consumedClearShouldSucceedOnFullMatch() throws Exception {
        insertRun("run-cl1", "RECEIVED", """
            {"resumeState":"CONSUMED","resumeToken":"clear-tok","resumeLeaseVersion":8}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST('{}' AS jsonb)
                WHERE id = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "run-cl1");
            ps.setString(2, "CONSUMED");
            ps.setString(3, "clear-tok");
            ps.setLong(4, 8);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        assertThat(getAnchorJson("run-cl1")).isEqualTo("{}");
    }

    @Test
    void consumedClearShouldRejectWhenStateNotConsumed() throws Exception {
        insertRun("run-cl2", "RECEIVED", """
            {"resumeState":"READY","resumeToken":"old-tok","resumeLeaseVersion":9}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST('{}' AS jsonb)
                WHERE id = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "run-cl2");
            ps.setString(2, "CONSUMED");  // expects CONSUMED, DB has READY
            ps.setString(3, "old-tok");
            ps.setLong(4, 9);
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }

    @Test
    void consumedClearShouldRejectWrongToken() throws Exception {
        insertRun("run-cl3", "RECEIVED", """
            {"resumeState":"CONSUMED","resumeToken":"real-tok","resumeLeaseVersion":5}""");

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE alphafrog_agent_run
                SET tool_job_anchor_json = CAST('{}' AS jsonb)
                WHERE id = ?
                  AND tool_job_anchor_json #>> '{resumeState}' = ?
                  AND tool_job_anchor_json #>> '{resumeToken}' = ?
                  AND (tool_job_anchor_json #>> '{resumeLeaseVersion}')::bigint = ?""")) {
            ps.setString(1, "run-cl3");
            ps.setString(2, "CONSUMED");
            ps.setString(3, "stale-tok");  // wrong token
            ps.setLong(4, 5);
            assertThat(ps.executeUpdate()).isEqualTo(0);
        }
    }
}
