package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

/** Run 级 data-analysis 聚合；任一 attempt 缺失的实际指标在 summary 中保持 null。 */
public record DataAnalysisObservabilitySummary(
        int toolCallCount,
        int attemptCount,
        long estimatedRows,
        long estimatedBytes,
        long fileCount,
        long capacityUnits,
        Long cpuMillis,
        Long memoryPeakBytes,
        Long logicalBytesScanned,
        Long queueWaitMillis,
        Long prepareMillis,
        Long executionWallMillis,
        Long cleanupMillis,
        Long datasetOpenCount,
        int oomCount,
        int timeoutCount,
        boolean attributionComplete,
        List<String> missingFields) {

    public DataAnalysisObservabilitySummary {
        DataAnalysisContractSupport.requireNonNegative(toolCallCount, "toolCallCount");
        DataAnalysisContractSupport.requireNonNegative(attemptCount, "attemptCount");
        DataAnalysisContractSupport.requireNonNegative(estimatedRows, "estimatedRows");
        DataAnalysisContractSupport.requireNonNegative(estimatedBytes, "estimatedBytes");
        DataAnalysisContractSupport.requireNonNegative(fileCount, "fileCount");
        DataAnalysisContractSupport.requireNonNegative(capacityUnits, "capacityUnits");
        DataAnalysisContractSupport.requireNullableNonNegative(cpuMillis, "cpuMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(memoryPeakBytes, "memoryPeakBytes");
        DataAnalysisContractSupport.requireNullableNonNegative(logicalBytesScanned, "logicalBytesScanned");
        DataAnalysisContractSupport.requireNullableNonNegative(queueWaitMillis, "queueWaitMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(prepareMillis, "prepareMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(executionWallMillis, "executionWallMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(cleanupMillis, "cleanupMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(datasetOpenCount, "datasetOpenCount");
        DataAnalysisContractSupport.requireNonNegative(oomCount, "oomCount");
        DataAnalysisContractSupport.requireNonNegative(timeoutCount, "timeoutCount");
        if (toolCallCount > attemptCount) {
            throw new IllegalArgumentException("toolCallCount cannot exceed attemptCount");
        }
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        if (attributionComplete && !missingFields.isEmpty()) {
            throw new IllegalArgumentException("complete summary cannot declare missing fields");
        }
    }
}
