package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

public record DataAnalysisEstimate(
        long estimatedRows,
        long estimatedBytes,
        int fileCount,
        double selectedColumnRatio,
        int manifestMemberCount,
        List<String> heavyOperationHints,
        DataAnalysisResourceClass resourceClass,
        int capacityUnits) {

    public DataAnalysisEstimate {
        DataAnalysisContractSupport.requireNonNegative(estimatedRows, "estimatedRows");
        DataAnalysisContractSupport.requireNonNegative(estimatedBytes, "estimatedBytes");
        DataAnalysisContractSupport.requireNonNegative(fileCount, "fileCount");
        DataAnalysisContractSupport.requireNonNegative(manifestMemberCount, "manifestMemberCount");
        if (!Double.isFinite(selectedColumnRatio)
                || selectedColumnRatio < 0.0d
                || selectedColumnRatio > 1.0d) {
            throw new IllegalArgumentException("selectedColumnRatio must be between 0 and 1");
        }
        heavyOperationHints = heavyOperationHints == null
                ? List.of()
                : heavyOperationHints.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        if (resourceClass == null) {
            throw new IllegalArgumentException("resourceClass must not be null");
        }
        if (capacityUnits <= 0) {
            throw new IllegalArgumentException("capacityUnits must be positive");
        }
    }
}
