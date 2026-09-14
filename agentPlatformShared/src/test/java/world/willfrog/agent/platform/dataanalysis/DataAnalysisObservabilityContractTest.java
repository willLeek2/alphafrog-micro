package world.willfrog.agent.platform.dataanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataAnalysisObservabilityContractTest {

    @Test
    void canonicalFixtureFreezesV1SummaryAndOrdering() {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();

        assertEquals(DataAnalysisObservabilitySnapshot.CURRENT_VERSION, snapshot.version());
        assertEquals(DataAnalysisObservabilitySnapshot.ROOT_FIELD, "data_analysis_observability");
        assertEquals(List.of("call-a", "call-b"), snapshot.calls().stream()
                .map(DataAnalysisObservabilityCall::toolCallId)
                .toList());
        assertEquals(2, snapshot.summary().toolCallCount());
        assertEquals(2, snapshot.summary().attemptCount());
        assertEquals(300L, snapshot.summary().estimatedRows());
        assertEquals(3_000L, snapshot.summary().estimatedBytes());
        assertEquals(3L, snapshot.summary().fileCount());
        assertEquals(4L, snapshot.summary().capacityUnits());
        assertEquals(30L, snapshot.summary().cpuMillis());
        assertEquals(200L, snapshot.summary().memoryPeakBytes());
        assertEquals(3_000L, snapshot.summary().logicalBytesScanned());
        assertEquals(13L, snapshot.summary().queueWaitMillis());
        assertEquals(23L, snapshot.summary().prepareMillis());
        assertEquals(300L, snapshot.summary().executionWallMillis());
        assertEquals(9L, snapshot.summary().cleanupMillis());
        assertEquals(5L, snapshot.summary().datasetOpenCount());
        assertEquals(1, snapshot.summary().oomCount());
        assertEquals(0, snapshot.summary().timeoutCount());
        assertEquals(true, snapshot.summary().attributionComplete());
        assertEquals(List.of(), snapshot.summary().missingFields());
    }

    @Test
    void partialCallMakesAggregateMetricExplicitlyMissing() {
        DataAnalysisTerminalEnvelope complete = envelope("call-a", completeUsage());
        DataAnalysisTerminalEnvelope missing = envelope(
                "call-b",
                DataAnalysisResourceUsage.missing(DataAnalysisResourceClass.STANDARD));

        DataAnalysisObservabilitySummary summary = DataAnalysisObservabilityBuilder
                .build("run-1", List.of(complete, missing))
                .summary();

        assertEquals(false, summary.attributionComplete());
        assertNull(summary.cpuMillis());
        assertNull(summary.executionWallMillis());
        assertEquals(
                DataAnalysisResourceUsage.P0_REQUIRED_MEASURED_FIELDS.stream().sorted().toList(),
                summary.missingFields());
    }

    @Test
    void builderRejectsDuplicateAttemptAndCrossRunEnvelope() {
        DataAnalysisTerminalEnvelope envelope = envelope("call-a", completeUsage());

        assertThrows(
                IllegalArgumentException.class,
                () -> DataAnalysisObservabilityBuilder.build("run-1", List.of(envelope, envelope)));
        assertThrows(
                IllegalArgumentException.class,
                () -> DataAnalysisObservabilityBuilder.build("another-run", List.of(envelope)));
    }

    @Test
    void snapshotRejectsSummaryThatDoesNotMatchCalls() {
        DataAnalysisObservabilitySnapshot fixture = DataAnalysisObservabilityContractFixtures.canonicalV1();
        DataAnalysisObservabilitySummary wrong = new DataAnalysisObservabilitySummary(
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                true,
                List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisObservabilitySnapshot(
                        DataAnalysisObservabilitySnapshot.CURRENT_VERSION,
                        fixture.runId(),
                        wrong,
                        fixture.calls()));
    }

    private DataAnalysisTerminalEnvelope envelope(
            String toolCallId,
            DataAnalysisResourceUsage usage) {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", toolCallId, 1);
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                1,
                1,
                1,
                1.0d,
                1,
                List.of(),
                DataAnalysisResourceClass.STANDARD,
                1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.TERMINAL_CONFIRMED,
                "task-" + toolCallId,
                Instant.parse("2026-07-12T00:00:00Z"));
        return new DataAnalysisTerminalEnvelope(
                "run-1",
                toolCallId,
                1,
                identity.operationId(),
                reservation.taskId(),
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
                Instant.parse("2026-07-12T00:01:00Z"),
                false);
    }

    private DataAnalysisResourceUsage completeUsage() {
        return new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                1L,
                1L,
                null,
                1L,
                null,
                null,
                1L,
                1L,
                1L,
                1L,
                1,
                "SUCCESS",
                false,
                false,
                true,
                null,
                List.of());
    }
}
