package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-12 production integration test: collector failure round-trip through PostgreSQL.
 *
 * <p>Verifies that when {@code cpuMillis=null} (simulating a Docker stats
 * collector failure), the {@link DataAnalysisTerminalEnvelope} is serialized into
 * the PostgreSQL {@code snapshot_json} JSONB column under the
 * {@link DataAnalysisObservabilitySnapshot#ROOT_FIELD} key and can be queried back
 * via the equivalent of {@code TERMINAL_DB_ONLY}, yielding
 * {@code attributionComplete=false}, {@code missingFields=["cpuMillis"]}, and
 * all 8 remaining P0 fields intact.
 *
 * <p>This test supplements {@link DataAnalysisResourceUsageP012Test} (pure record
 * construction) with an end-to-end persistence round-trip: envelope construction,
 * {@link DataAnalysisObservabilityBuilder#build} snapshot assembly,
 * PostgreSQL JSONB write/read, and deserialization verification.
 */
@Testcontainers
class DataAnalysisObservabilityP012IntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "p012-int-run";
    private static final String TC_ID = "p012-call";
    private static final int ATTEMPT = 1;
    private static final String TASK_ID = "p012-task";

    private static final long MB100 = 1024L * 1024 * 100;
    private static final long MB10 = 1024L * 1024 * 10;

    // ---- Infrastructure ----

    @BeforeAll
    static void createTable() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    status VARCHAR(32),
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

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status) VALUES (?, 'COMPLETED') "
                             + "ON CONFLICT (id) DO NOTHING")) {
            ps.setString(1, RUN_ID);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run WHERE id = '" + RUN_ID + "'");
        }
    }

    // =====================================================================
    // P0-12: collector failure (cpuMillis=null) round-trip through PG
    // =====================================================================

    @Test
    void collectorFailureEnvelopePersistedAndQueriedFromPostgres() throws Exception {
        // ── 1. Build envelope with cpuMillis=null (simulated Docker stats failure) ──
        DataAnalysisResourceUsage usage = resourceUsageMissingCpu();
        DataAnalysisTerminalEnvelope envelope = buildEnvelope(usage);

        // Pre-flush sanity: contract is correct before any serialization
        assertThat(usage.attributionComplete()).isFalse();
        assertThat(usage.missingFields()).containsExactly("cpuMillis");
        assertThat(usage.cpuMillis()).isNull();

        // ── 2. Build snapshot via production code path ──
        DataAnalysisObservabilitySnapshot snapshot =
                DataAnalysisObservabilityBuilder.build(RUN_ID, List.of(envelope));
        assertThat(snapshot.calls()).hasSize(1);
        assertThat(snapshot.version()).isEqualTo(DataAnalysisObservabilitySnapshot.CURRENT_VERSION);

        // ── 3. Persist snapshot to PostgreSQL JSONB ──
        upsertObservability(RUN_ID, om.writeValueAsString(snapshot));

        // ── 4. Query back full snapshot (TERMINAL_DB_ONLY equivalent) ──
        String persisted = queryObservabilityJson(RUN_ID);
        assertThat(persisted).isNotNull().isNotBlank();
        DataAnalysisObservabilitySnapshot roundTripped =
                om.readValue(persisted, DataAnalysisObservabilitySnapshot.class);

        // ── 5. ROOT_FIELD contract ──
        assertThat(DataAnalysisObservabilitySnapshot.ROOT_FIELD)
                .isEqualTo("data_analysis_observability");

        // ── 6. Summary: attributionComplete=false, cpuMillis=null, missingFields=["cpuMillis"] ──
        DataAnalysisObservabilitySummary summary = roundTripped.summary();
        assertThat(summary.attributionComplete()).isFalse();
        assertThat(summary.missingFields()).containsExactly("cpuMillis");
        assertThat(summary.cpuMillis()).isNull(); // aggregation forced null by missing metric
        assertThat(summary.toolCallCount()).isEqualTo(1);
        assertThat(summary.attemptCount()).isEqualTo(1);
        assertThat(summary.oomCount()).isEqualTo(0);
        assertThat(summary.timeoutCount()).isEqualTo(0);

        // All 8 other P0 required fields survive the round-trip
        assertThat(summary.memoryPeakBytes()).isEqualTo(MB100);
        assertThat(summary.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(summary.queueWaitMillis()).isEqualTo(150L);
        assertThat(summary.prepareMillis()).isEqualTo(200L);
        assertThat(summary.executionWallMillis()).isEqualTo(5000L);
        assertThat(summary.cleanupMillis()).isEqualTo(100L);
        assertThat(summary.datasetOpenCount()).isEqualTo(3L);

        // ── 7. Call-level identity preserved ──
        DataAnalysisObservabilityCall call = roundTripped.calls().get(0);
        assertThat(call.toolCallId()).isEqualTo(TC_ID);
        assertThat(call.attempt()).isEqualTo(ATTEMPT);
        assertThat(call.operationId())
                .isEqualTo(RUN_ID + ":" + TC_ID + ":" + ATTEMPT);
        assertThat(call.taskId()).isEqualTo(TASK_ID);
        assertThat(call.terminalStatus()).isEqualTo("COMPLETED");
        assertThat(call.success()).isTrue();

        // Call resourceUsage mirrors the original envelope
        DataAnalysisResourceUsage callUsage = call.resourceUsage();
        assertThat(callUsage.attributionComplete()).isFalse();
        assertThat(callUsage.missingFields()).containsExactly("cpuMillis");
        assertThat(callUsage.cpuMillis()).isNull();
        assertThat(callUsage.memoryPeakBytes()).isEqualTo(MB100);
        assertThat(callUsage.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(callUsage.queueWaitMillis()).isEqualTo(150L);
        assertThat(callUsage.prepareMillis()).isEqualTo(200L);
        assertThat(callUsage.executionWallMillis()).isEqualTo(5000L);
        assertThat(callUsage.cleanupMillis()).isEqualTo(100L);
        assertThat(callUsage.datasetOpenCount()).isEqualTo(3);
        assertThat(callUsage.exitReason()).isEqualTo("SUCCEEDED");
        assertThat(callUsage.resourceClass()).isEqualTo(DataAnalysisResourceClass.STANDARD);

        // ── 8. Summary-only sub-tree query (findSummaryByRunId equivalent) ──
        String summaryJson = queryObservabilitySummaryJson(RUN_ID);
        assertThat(summaryJson).isNotNull().isNotBlank();
        DataAnalysisObservabilitySummary summaryDirect =
                om.readValue(summaryJson, DataAnalysisObservabilitySummary.class);
        assertThat(summaryDirect.attributionComplete()).isFalse();
        assertThat(summaryDirect.missingFields()).containsExactly("cpuMillis");
        assertThat(summaryDirect.cpuMillis()).isNull();
    }

    // =====================================================================
    // Complementary positive case: all P0 fields present
    // =====================================================================

    @Test
    void completeAttributionRoundTripThroughPostgres() throws Exception {
        DataAnalysisResourceUsage usage = completeResourceUsage();
        DataAnalysisTerminalEnvelope envelope = buildEnvelope(usage);
        assertThat(usage.attributionComplete()).isTrue();
        assertThat(usage.missingFields()).isEmpty();

        DataAnalysisObservabilitySnapshot snapshot =
                DataAnalysisObservabilityBuilder.build(RUN_ID, List.of(envelope));
        upsertObservability(RUN_ID, om.writeValueAsString(snapshot));

        String persisted = queryObservabilityJson(RUN_ID);
        DataAnalysisObservabilitySnapshot roundTripped =
                om.readValue(persisted, DataAnalysisObservabilitySnapshot.class);

        DataAnalysisObservabilitySummary summary = roundTripped.summary();
        assertThat(summary.attributionComplete()).isTrue();
        assertThat(summary.missingFields()).isEmpty();
        assertThat(summary.cpuMillis()).isEqualTo(1200L);

        // All 8 other P0 fields carry through
        assertThat(summary.memoryPeakBytes()).isEqualTo(MB100);
        assertThat(summary.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(summary.queueWaitMillis()).isEqualTo(150L);
        assertThat(summary.prepareMillis()).isEqualTo(200L);
        assertThat(summary.executionWallMillis()).isEqualTo(5000L);
        assertThat(summary.cleanupMillis()).isEqualTo(100L);
        assertThat(summary.datasetOpenCount()).isEqualTo(3L);

        // Call identity is intact
        DataAnalysisObservabilityCall call = roundTripped.calls().get(0);
        assertThat(call.toolCallId()).isEqualTo(TC_ID);
        assertThat(call.attempt()).isEqualTo(ATTEMPT);
        assertThat(call.taskId()).isEqualTo(TASK_ID);
        assertThat(call.resourceUsage().attributionComplete()).isTrue();
    }

    // =====================================================================
    // Envelope part of snapshot: multiple-call aggregation with and without
    // cpuMillis verifies partial attribution at summary level
    // =====================================================================

    @Test
    void mixedCallsPartialAttributionAggregatesMissingFieldsInSummary() throws Exception {
        // One call with cpuMillis, one without -- distinct toolCallIds and attempts
        DataAnalysisTerminalEnvelope complete = buildEnvelope(
                "tc-complete", 1, completeResourceUsageWithTcId("tc-complete"));
        DataAnalysisTerminalEnvelope missing = buildEnvelope(
                "tc-missing", 1, resourceUsageMissingCpuWithTcId("tc-missing"));

        DataAnalysisObservabilitySnapshot snapshot =
                DataAnalysisObservabilityBuilder.build(RUN_ID, List.of(complete, missing));
        assertThat(snapshot.calls()).hasSize(2);

        DataAnalysisObservabilitySummary summary = snapshot.summary();
        assertThat(summary.attributionComplete()).isFalse();
        assertThat(summary.missingFields()).containsExactly("cpuMillis");
        // cpuMillis aggregation is null because one call is missing it
        assertThat(summary.cpuMillis()).isNull();
        assertThat(summary.attemptCount()).isEqualTo(2);
    }

    // =====================================================================
    // Helpers: resource usage factories
    // =====================================================================

    private DataAnalysisResourceUsage resourceUsageMissingCpu() {
        return resourceUsageMissingCpuWithTcId(TC_ID);
    }

    private DataAnalysisResourceUsage resourceUsageMissingCpuWithTcId(String toolCallId) {
        return new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                null,        // cpuMillis -- MISSING (simulated Docker stats failure)
                MB100,       // memoryPeakBytes
                null,        // memoryByteMillis
                MB10,        // logicalBytesScanned
                null,        // artifactBytesWritten
                null,        // temporaryBytesWritten
                150L,        // queueWaitMillis
                200L,        // prepareMillis
                5000L,       // executionWallMillis
                100L,        // cleanupMillis
                3,           // datasetOpenCount
                "SUCCEEDED", // exitReason
                false,       // oomKilled
                false,       // timedOut
                false,       // attributionComplete
                null,        // samplingIntervalMillis
                List.of("cpuMillis"));
    }

    private DataAnalysisResourceUsage completeResourceUsage() {
        return completeResourceUsageWithTcId(TC_ID);
    }

    private DataAnalysisResourceUsage completeResourceUsageWithTcId(String toolCallId) {
        return new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                1200L,       // cpuMillis
                MB100,       // memoryPeakBytes
                null,        // memoryByteMillis
                MB10,        // logicalBytesScanned
                null,        // artifactBytesWritten
                null,        // temporaryBytesWritten
                150L,        // queueWaitMillis
                200L,        // prepareMillis
                5000L,       // executionWallMillis
                100L,        // cleanupMillis
                3,           // datasetOpenCount
                "SUCCEEDED", // exitReason
                false,       // oomKilled
                false,       // timedOut
                true,        // attributionComplete
                null,        // samplingIntervalMillis
                List.of());
    }

    // =====================================================================
    // Helpers: envelope construction
    // =====================================================================

    private DataAnalysisTerminalEnvelope buildEnvelope(DataAnalysisResourceUsage usage) {
        return buildEnvelope(TC_ID, ATTEMPT, usage);
    }

    private DataAnalysisTerminalEnvelope buildEnvelope(
            String toolCallId, int attempt, DataAnalysisResourceUsage usage) {
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, toolCallId, attempt);
        DataAnalysisResourceClass rc = usage.resourceClass();
        int cu = rc.defaultCapacityUnits();

        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                1000, 5000, 1, 1.0d, 1, List.of(), rc, cu);

        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(), identity, rc, cu,
                DataAnalysisReservationState.TERMINAL_CONFIRMED,
                TASK_ID,
                Instant.parse("2026-07-13T00:00:00Z"));

        return new DataAnalysisTerminalEnvelope(
                RUN_ID,
                toolCallId,
                attempt,
                identity.operationId(),
                TASK_ID,
                "COMPLETED",
                true,
                "ok",
                null,
                null,
                null,
                false,
                estimate,
                reservation,
                usage,
                Instant.parse("2026-07-13T00:01:00Z"),
                false);
    }

    // =====================================================================
    // Helpers: direct PostgreSQL JSONB round-trip (production-equivalent SQL)
    // =====================================================================
    //
    // These use the same jsonb_set and -> accessors as AgentRunMapper.xml,
    // giving us a faithful integration test of the DB storage contract
    // without needing MyBatis/Redis wiring.

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private void upsertObservability(String runId, String snapshotJson)
            throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 UPDATE alphafrog_agent_run
                 SET snapshot_json = jsonb_set(
                     COALESCE(snapshot_json, '{}'::jsonb),
                     '{data_analysis_observability}',
                     CAST(? AS jsonb),
                     true),
                     updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                 """)) {
            ps.setString(1, snapshotJson);
            ps.setString(2, runId);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    private String queryObservabilityJson(String runId) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT (snapshot_json -> 'data_analysis_observability')::text "
                             + "FROM alphafrog_agent_run WHERE id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String queryObservabilitySummaryJson(String runId) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT (snapshot_json -> 'data_analysis_observability' -> 'summary')::text "
                             + "FROM alphafrog_agent_run WHERE id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
