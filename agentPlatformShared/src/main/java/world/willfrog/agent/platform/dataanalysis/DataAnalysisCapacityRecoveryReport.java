package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

public record DataAnalysisCapacityRecoveryReport(
        int restoredReservations,
        int activeCount,
        int heavyActiveCount,
        int usedUnits,
        int configuredMaxUnits,
        int configuredMaxHeavyActive,
        boolean overConfigured,
        boolean heavyOverConfigured,
        List<String> conflicts,
        DataAnalysisAdmissionState admissionState) {

    public DataAnalysisCapacityRecoveryReport {
        DataAnalysisContractSupport.requireNonNegative(restoredReservations, "restoredReservations");
        DataAnalysisContractSupport.requireNonNegative(activeCount, "activeCount");
        DataAnalysisContractSupport.requireNonNegative(heavyActiveCount, "heavyActiveCount");
        DataAnalysisContractSupport.requireNonNegative(usedUnits, "usedUnits");
        DataAnalysisContractSupport.requireNonNegative(configuredMaxUnits, "configuredMaxUnits");
        DataAnalysisContractSupport.requireNonNegative(configuredMaxHeavyActive, "configuredMaxHeavyActive");
        if (activeCount > restoredReservations) {
            throw new IllegalArgumentException("activeCount cannot exceed restoredReservations");
        }
        if (heavyActiveCount > activeCount) {
            throw new IllegalArgumentException("heavyActiveCount cannot exceed activeCount");
        }
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        if (admissionState == null) {
            throw new IllegalArgumentException("admissionState must not be null");
        }
        if (overConfigured != (usedUnits > configuredMaxUnits)) {
            throw new IllegalArgumentException("overConfigured must match usedUnits > configuredMaxUnits");
        }
        if (heavyOverConfigured != (heavyActiveCount > configuredMaxHeavyActive)) {
            throw new IllegalArgumentException(
                    "heavyOverConfigured must match heavyActiveCount > configuredMaxHeavyActive");
        }
        if ((!conflicts.isEmpty() || overConfigured || heavyOverConfigured)
                && admissionState == DataAnalysisAdmissionState.OPEN) {
            throw new IllegalArgumentException("admissionState cannot be OPEN with conflicts or over-configuration");
        }
    }
}
