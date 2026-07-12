package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;

public record DataAnalysisReservation(
        String reservationId,
        DataAnalysisOperationIdentity identity,
        DataAnalysisResourceClass resourceClass,
        int capacityUnits,
        DataAnalysisReservationState state,
        String taskId,
        Instant acquiredAt) {

    public DataAnalysisReservation {
        reservationId = DataAnalysisContractSupport.requireText(reservationId, "reservationId");
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (!identity.reservationId().equals(reservationId)) {
            throw new IllegalArgumentException("reservationId must match identity reservationId");
        }
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
        if (state == DataAnalysisReservationState.PREPARING && taskId != null) {
            throw new IllegalArgumentException("taskId must be absent for preparing reservations");
        }
        if (state != DataAnalysisReservationState.PREPARING
                && state != DataAnalysisReservationState.RELEASED
                && taskId == null) {
            throw new IllegalArgumentException("taskId is required after task attachment");
        }
    }

    public String operationId() {
        return identity.operationId();
    }
}
