package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;
import java.util.List;

/** 跨模块 mapper/serializer 测试共用的稳定 v1 fixture，不作为生产数据源。 */
public final class DataAnalysisObservabilityContractFixtures {

    public static final String RUN_ID = "fixture-run-1";

    private DataAnalysisObservabilityContractFixtures() {
    }

    public static DataAnalysisObservabilitySnapshot canonicalV1() {
        return DataAnalysisObservabilityBuilder.build(RUN_ID, List.of(
                envelope("call-a", "task-a", 100L, 1_000L, 1, false, false, false, 10L, 100L),
                envelope("call-b", "task-b", 200L, 2_000L, 2, true, true, false, 20L, 200L)));
    }

    private static DataAnalysisTerminalEnvelope envelope(
            String toolCallId,
            String taskId,
            long rows,
            long bytes,
            int fileCount,
            boolean heavy,
            boolean oomKilled,
            boolean timedOut,
            long cpuMillis,
            long memoryPeakBytes) {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(RUN_ID, toolCallId, 1);
        DataAnalysisResourceClass resourceClass = heavy
                ? DataAnalysisResourceClass.HEAVY
                : DataAnalysisResourceClass.STANDARD;
        int capacityUnits = resourceClass.defaultCapacityUnits();
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                rows,
                bytes,
                fileCount,
                1.0d,
                fileCount,
                List.of(),
                resourceClass,
                capacityUnits);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                resourceClass,
                capacityUnits,
                DataAnalysisReservationState.TERMINAL_CONFIRMED,
                taskId,
                Instant.parse("2026-07-12T00:00:00Z"));
        DataAnalysisResourceUsage usage = new DataAnalysisResourceUsage(
                resourceClass,
                cpuMillis,
                memoryPeakBytes,
                null,
                bytes,
                null,
                null,
                5L + fileCount,
                10L + fileCount,
                100L * fileCount,
                3L + fileCount,
                fileCount + 1,
                oomKilled ? "SANDBOX_OOM" : "SUCCESS",
                oomKilled,
                timedOut,
                true,
                null,
                List.of());
        return new DataAnalysisTerminalEnvelope(
                RUN_ID,
                toolCallId,
                1,
                identity.operationId(),
                taskId,
                oomKilled ? "FAILED" : "COMPLETED",
                !oomKilled && !timedOut,
                oomKilled ? null : "{\"ok\":true}",
                oomKilled ? "artifact:sha256:" + "a".repeat(64) : null,
                oomKilled ? "SANDBOX_OOM" : null,
                oomKilled ? "sandbox memory limit exceeded" : null,
                oomKilled,
                estimate,
                reservation,
                usage,
                Instant.parse("2026-07-12T00:01:00Z"),
                heavy);
    }
}
