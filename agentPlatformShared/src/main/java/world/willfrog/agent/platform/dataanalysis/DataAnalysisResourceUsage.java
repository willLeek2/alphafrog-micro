package world.willfrog.agent.platform.dataanalysis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    public static final Set<String> P0_REQUIRED_MEASURED_FIELDS = Set.of(
            "cpuMillis",
            "memoryPeakBytes",
            "logicalBytesScanned",
            "queueWaitMillis",
            "prepareMillis",
            "executionWallMillis",
            "cleanupMillis",
            "datasetOpenCount",
            "exitReason");

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
        Map<String, Object> requiredValues = Map.of(
                "cpuMillis", nullableSentinel(cpuMillis),
                "memoryPeakBytes", nullableSentinel(memoryPeakBytes),
                "logicalBytesScanned", nullableSentinel(logicalBytesScanned),
                "queueWaitMillis", nullableSentinel(queueWaitMillis),
                "prepareMillis", nullableSentinel(prepareMillis),
                "executionWallMillis", nullableSentinel(executionWallMillis),
                "cleanupMillis", nullableSentinel(cleanupMillis),
                "datasetOpenCount", nullableSentinel(datasetOpenCount),
                "exitReason", nullableSentinel(exitReason));
        Set<String> actuallyMissing = new TreeSet<>();
        requiredValues.forEach((name, value) -> {
            if (value == MissingValue.INSTANCE) {
                actuallyMissing.add(name);
            }
        });
        Set<String> declaredMissing = new TreeSet<>(missingFields);
        if (!P0_REQUIRED_MEASURED_FIELDS.containsAll(declaredMissing)) {
            throw new IllegalArgumentException("missingFields contains an unknown or non-P0 field");
        }
        if (!declaredMissing.equals(actuallyMissing)) {
            throw new IllegalArgumentException(
                    "missingFields must exactly match null P0 required measured fields");
        }
        if (attributionComplete && !actuallyMissing.isEmpty()) {
            throw new IllegalArgumentException("complete attribution requires every P0 measured field");
        }
        if (!attributionComplete && actuallyMissing.isEmpty()) {
            throw new IllegalArgumentException("partial attribution must declare at least one missing P0 field");
        }
    }

    public static DataAnalysisResourceUsage missing(DataAnalysisResourceClass resourceClass) {
        return new DataAnalysisResourceUsage(
                resourceClass,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                null,
                P0_REQUIRED_MEASURED_FIELDS.stream().sorted().toList());
    }

    private static Object nullableSentinel(Object value) {
        return value == null ? MissingValue.INSTANCE : value;
    }

    private enum MissingValue {
        INSTANCE
    }
}
