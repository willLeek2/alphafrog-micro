package world.willfrog.agent.tools.python;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data analysis capacity ledger tuning. Bound to the {@code alphafrog.data-analysis.capacity}
 * configuration prefix so it stays independent from the multi-owner {@code AgentLlmProperties}.
 */
@Data
@ConfigurationProperties(prefix = "alphafrog.data-analysis.capacity")
public class DataAnalysisCapacityProperties {

    /** Maximum total capacity units a single host will hold concurrently. */
    private int maxUnits = 4;

    /** Maximum number of active reservations regardless of resource class. */
    private int maxActive = 2;

    /** Maximum number of active HEAVY reservations. */
    private int maxHeavyActive = 1;

    /** Hard row ceiling per task; rows above this are rejected as DATA_ANALYSIS_TASK_TOO_LARGE. */
    private long maxRowsPerTask = 600_000L;

    /** Hard byte ceiling per task; bytes above this are rejected as DATA_ANALYSIS_TASK_TOO_LARGE. */
    private long maxBytesPerTask = 512L * 1024L * 1024L;

    /** Row threshold that promotes a task from STANDARD to HEAVY when combined with bytes / hints. */
    private long standardRowsMax = 200_000L;

    /** Byte threshold that promotes a task from STANDARD to HEAVY when combined with rows / hints. */
    private long standardBytesMax = 32L * 1024L * 1024L;

    /** Memory cap for STANDARD tasks, in bytes. */
    private long standardMemoryLimitBytes = 512L * 1024L * 1024L;

    /** Memory cap for HEAVY tasks, in bytes. */
    private long heavyMemoryLimitBytes = 1536L * 1024L * 1024L;

    /**
     * Classify a task by its estimated row count, byte budget, and heavy-operation hints.
     *
     * <p>The classification rules are deterministic per the §8.1 contract:
     * rows &le; {@code standardRowsMax} <em>and</em> bytes &le; {@code standardBytesMax}
     * <em>and</em> no heavy operation hints resolves to {@link world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass#STANDARD}.
     * Anything that still fits under the per-task hard ceilings ({@code maxRowsPerTask} / {@code maxBytesPerTask})
     * but does not meet all three standard thresholds resolves to
     * {@link world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass#HEAVY}.
     */
    public DataAnalysisResourceClassDecision classify(
            long estimatedRows, long estimatedBytes, java.util.List<String> heavyOperationHints) {
        if (estimatedRows > maxRowsPerTask || estimatedBytes > maxBytesPerTask) {
            return DataAnalysisResourceClassDecision.rejected(estimatedRows, estimatedBytes,
                    maxRowsPerTask, maxBytesPerTask);
        }
        boolean standardByRows = estimatedRows <= standardRowsMax;
        boolean standardByBytes = estimatedBytes <= standardBytesMax;
        boolean standardByHints = heavyOperationHints == null || heavyOperationHints.isEmpty();
        if (standardByRows && standardByBytes && standardByHints) {
            return DataAnalysisResourceClassDecision.standard(
                    standardMemoryLimitBytes, maxRowsPerTask, maxBytesPerTask);
        }
        return DataAnalysisResourceClassDecision.heavy(
                heavyMemoryLimitBytes, maxRowsPerTask, maxBytesPerTask);
    }

    /** Decision returned by {@link #classify}. */
    public record DataAnalysisResourceClassDecision(
            Outcome outcome,
            world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass resourceClass,
            int capacityUnits,
            long memoryLimitBytes,
            long rowsLimit,
            long bytesLimit) {

        public enum Outcome { ACCEPTED, REJECTED }

        public static DataAnalysisResourceClassDecision standard(
                long memoryLimitBytes, long rowsLimit, long bytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.ACCEPTED,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.STANDARD,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.STANDARD.defaultCapacityUnits(),
                    memoryLimitBytes,
                    rowsLimit,
                    bytesLimit);
        }

        public static DataAnalysisResourceClassDecision heavy(
                long memoryLimitBytes, long rowsLimit, long bytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.ACCEPTED,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.HEAVY,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.HEAVY.defaultCapacityUnits(),
                    memoryLimitBytes,
                    rowsLimit,
                    bytesLimit);
        }

        public static DataAnalysisResourceClassDecision rejected(
                long rowsLimit, long bytesLimit, long configuredRowsLimit, long configuredBytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.REJECTED,
                    null,
                    0,
                    0,
                    configuredRowsLimit,
                    configuredBytesLimit);
        }
    }
}