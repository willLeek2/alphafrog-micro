package world.willfrog.agent.platform.dataanalysis;

/**
 * Proof-bearing release request. Capacity implementations must not release a task-bound
 * reservation from an id alone: the durable terminal envelope is the release gate.
 */
public record DataAnalysisReleaseRequest(
        DataAnalysisReservation reservation,
        DataAnalysisReleaseProof proof,
        DataAnalysisReleaseReason reason) {

    public DataAnalysisReleaseRequest {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }
        if (proof == null) {
            throw new IllegalArgumentException("proof must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (proof instanceof DataAnalysisReleaseProof.Terminal terminal) {
            if (reservation.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
                throw new IllegalArgumentException("reservation must be terminal-confirmed before terminal release");
            }
            if (!reservation.equals(terminal.envelope().reservation())) {
                throw new IllegalArgumentException("terminal proof must belong to the released reservation");
            }
        } else if (proof instanceof DataAnalysisReleaseProof.PreDispatchAbort abort) {
            if (reservation.state() != DataAnalysisReservationState.PREPARING) {
                throw new IllegalArgumentException("pre-dispatch proof can release only preparing reservations");
            }
            if (!reservation.identity().equals(abort.identity())) {
                throw new IllegalArgumentException("pre-dispatch proof must belong to the released reservation");
            }
            if (reason != DataAnalysisReleaseReason.CREATE_NOT_STARTED
                    && reason != DataAnalysisReleaseReason.PREPARING_ABORTED) {
                throw new IllegalArgumentException("pre-dispatch proof requires a pre-dispatch release reason");
            }
        }
    }
}
