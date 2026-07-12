package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

public record DataAnalysisCapacityRecoveryReport(
        int restoredReservations,
        int usedUnits,
        int configuredMaxUnits,
        boolean overConfigured,
        List<String> conflicts,
        DataAnalysisAdmissionState admissionState) {

    public DataAnalysisCapacityRecoveryReport {
        DataAnalysisContractSupport.requireNonNegative(restoredReservations, "restoredReservations");
        DataAnalysisContractSupport.requireNonNegative(usedUnits, "usedUnits");
        DataAnalysisContractSupport.requireNonNegative(configuredMaxUnits, "configuredMaxUnits");
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        if (admissionState == null) {
            throw new IllegalArgumentException("admissionState must not be null");
        }
        if (overConfigured != (usedUnits > configuredMaxUnits)) {
            throw new IllegalArgumentException("overConfigured must match usedUnits > configuredMaxUnits");
        }
        if ((!conflicts.isEmpty() || overConfigured)
                && admissionState == DataAnalysisAdmissionState.OPEN) {
            throw new IllegalArgumentException("admissionState cannot be OPEN with conflicts or over-configuration");
        }
    }
}
