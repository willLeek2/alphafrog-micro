package world.willfrog.agent.platform.dataanalysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolJobAnchorSerializationTest {

    @Test
    void writesLeaseAsExactIsoStringAndRoundTrips() {
        Instant lease = Instant.parse("2026-07-30T07:45:47.335223Z");
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setBlockingLeaseUntil(lease);

        String json = anchor.toJson();

        assertThat(json).contains(
                "\"blockingLeaseUntil\":\"2026-07-30T07:45:47.335223Z\"");
        assertThat(ToolJobAnchor.fromJson(json).getBlockingLeaseUntil())
                .isEqualTo(lease);
    }

    @Test
    void readsHistoricalNumericAndIsoLeaseRepresentations() {
        ToolJobAnchor numeric = ToolJobAnchor.fromJson(
                "{\"blockingLeaseUntil\":1785397547.335223000}");
        ToolJobAnchor textual = ToolJobAnchor.fromJson(
                "{\"blockingLeaseUntil\":\"2026-07-30T07:45:47.335223Z\"}");

        assertThat(numeric.getBlockingLeaseUntil())
                .isEqualTo(Instant.parse("2026-07-30T07:45:47.335223Z"));
        assertThat(textual.getBlockingLeaseUntil())
                .isEqualTo(Instant.parse("2026-07-30T07:45:47.335223Z"));
    }

    @Test
    void roundTripsFrozenRedisCleanupSourceIdentity() {
        Instant sourceLease =
                Instant.parse("2026-07-30T07:40:00.123456Z");
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setCleanupSourceOwnerId("worker-a");
        anchor.setCleanupSourceLeaseUntil(sourceLease);

        ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());

        assertThat(restored.getCleanupSourceOwnerId()).isEqualTo("worker-a");
        assertThat(restored.getCleanupSourceLeaseUntil())
                .isEqualTo(sourceLease);
    }

    @Test
    void roundTripsDurablePythonRepairStateAndTerminalDiagnostics() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setPythonRequestFingerprint("sha256:" + "a".repeat(64));
        anchor.setPythonRepairAttempt(2);
        anchor.setPythonRepairPending(true);
        anchor.setPythonRepairExhausted(true);
        anchor.setPythonFailedRequestFingerprints(List.of(
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "sha256:" + "a".repeat(64)));
        anchor.setTerminalStderrPreview("Traceback: bad date");
        anchor.setTerminalExitReason("NON_ZERO_EXIT");

        ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());

        assertThat(restored.getPythonRequestFingerprint())
                .isEqualTo("sha256:" + "a".repeat(64));
        assertThat(restored.getPythonRepairAttempt()).isEqualTo(2);
        assertThat(restored.isPythonRepairPending()).isTrue();
        assertThat(restored.isPythonRepairExhausted()).isTrue();
        assertThat(restored.getPythonFailedRequestFingerprints()).containsExactly(
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64));
        assertThat(restored.getTerminalStderrPreview()).isEqualTo("Traceback: bad date");
        assertThat(restored.getTerminalExitReason()).isEqualTo("NON_ZERO_EXIT");
    }

    @Test
    void roundTripsFrozenFinanceRecordLimits() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setFinanceRecordLimitsJson("{\"enabled\":false,\"recordCountMax\":128}");

        ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());

        assertThat(restored.getFinanceRecordLimitsJson())
                .isEqualTo("{\"enabled\":false,\"recordCountMax\":128}");
    }
}
