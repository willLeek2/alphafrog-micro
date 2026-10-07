package world.willfrog.agent.platform.dataanalysis;

public enum DataAnalysisReleaseReason {
    SANDBOX_TERMINAL_CONFIRMED,
    SANDBOX_CANCEL_CONFIRMED,
    CREATE_NOT_STARTED,
    PREPARING_ABORTED,
    WORKSPACE_CREATE_REFUSED,
    HARD_DEADLINE_CONFIRMED,
    FINALIZER_RETRY
}
