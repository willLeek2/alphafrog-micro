package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

public record DataAnalysisResourceUsage(
        DataAnalysisResourceClass resourceClass,
        Long cpuMillis,
        Long memoryPeakBytes,
        Long memoryByteMillis,
        Long logicalBytesScanned,
        Long artifactBytesWritten,
        Long temporaryBytesWritten,
        Long queueWaitMillis,
        Long prepareMillis,
        Long executionWallMillis,
        Long cleanupMillis,
        Integer datasetOpenCount,
        String exitReason,
        boolean oomKilled,
        boolean timedOut,
        boolean attributionComplete,
        Long samplingIntervalMillis,
        List<String> missingFields) {

    public DataAnalysisResourceUsage {
        if (resourceClass == null) {
            throw new IllegalArgumentException("resourceClass must not be null");
        }
        DataAnalysisContractSupport.requireNullableNonNegative(cpuMillis, "cpuMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(memoryPeakBytes, "memoryPeakBytes");
        DataAnalysisContractSupport.requireNullableNonNegative(memoryByteMillis, "memoryByteMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(logicalBytesScanned, "logicalBytesScanned");
        DataAnalysisContractSupport.requireNullableNonNegative(artifactBytesWritten, "artifactBytesWritten");
        DataAnalysisContractSupport.requireNullableNonNegative(temporaryBytesWritten, "temporaryBytesWritten");
        DataAnalysisContractSupport.requireNullableNonNegative(queueWaitMillis, "queueWaitMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(prepareMillis, "prepareMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(executionWallMillis, "executionWallMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(cleanupMillis, "cleanupMillis");
        DataAnalysisContractSupport.requireNullableNonNegative(datasetOpenCount, "datasetOpenCount");
        DataAnalysisContractSupport.requireNullableNonNegative(samplingIntervalMillis, "samplingIntervalMillis");
        exitReason = exitReason == null || exitReason.isBlank() ? null : exitReason.trim();
        missingFields = missingFields == null
                ? List.of()
                : missingFields.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        if (attributionComplete && !missingFields.isEmpty()) {
            throw new IllegalArgumentException("missingFields must be empty when attributionComplete is true");
        }
    }
}
