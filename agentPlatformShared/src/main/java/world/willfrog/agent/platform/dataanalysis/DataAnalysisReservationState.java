package world.willfrog.agent.platform.dataanalysis;

public enum DataAnalysisReservationState {
    PREPARING,
    TASK_ATTACHED,
    PENDING_TRANSFERRED,
    TERMINAL_CONFIRMED,
    RELEASED;

    public boolean canTransitionTo(DataAnalysisReservationState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case PREPARING -> next == TASK_ATTACHED || next == RELEASED;
            case TASK_ATTACHED -> next == PENDING_TRANSFERRED
                    || next == TERMINAL_CONFIRMED;
            case PENDING_TRANSFERRED -> next == TERMINAL_CONFIRMED;
            case TERMINAL_CONFIRMED -> next == RELEASED;
            case RELEASED -> false;
        };
    }
}
