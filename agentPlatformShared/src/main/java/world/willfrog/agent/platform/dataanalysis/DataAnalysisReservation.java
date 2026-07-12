package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;

public record DataAnalysisReservation(
        String reservationId,
        DataAnalysisResourceClass resourceClass,
        int capacityUnits,
        DataAnalysisReservationState state,
        String taskId,
        Instant acquiredAt) {

    public DataAnalysisReservation {
        reservationId = DataAnalysisContractSupport.requireText(reservationId, "reservationId");
        if (resourceClass == null) {
            throw new IllegalArgumentException("resourceClass must not be null");
        }
        if (capacityUnits <= 0) {
            throw new IllegalArgumentException("capacityUnits must be positive");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (acquiredAt == null) {
            throw new IllegalArgumentException("acquiredAt must not be null");
        }
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim();
        if ((state == DataAnalysisReservationState.ATTACHED
                || state == DataAnalysisReservationState.ACTIVE)
                && taskId == null) {
            throw new IllegalArgumentException("taskId is required for attached or active reservations");
        }
    }
}
