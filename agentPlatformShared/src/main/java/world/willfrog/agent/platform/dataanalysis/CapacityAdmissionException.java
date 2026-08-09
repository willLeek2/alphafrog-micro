package world.willfrog.agent.platform.dataanalysis;

/**
 * Capacity-layer admission error. The {@link Reason} mirrors the §6.8 error-code vocabulary
 * so the tool layer can translate to {@code DATA_ANALYSIS_SERVER_BUSY} or
 * {@code DATA_ANALYSIS_TASK_TOO_LARGE} without leaking the in-memory details.
 *
 * <p>Lives in the interface package so consumers depend on the seam, not on
 * {@code DataAnalysisCapacityServiceImpl}.</p>
 */
public class CapacityAdmissionException extends RuntimeException {

    public enum Reason { RECOVERING, SERVER_BUSY, TASK_TOO_LARGE, ALREADY_RESERVED, ILLEGAL_RESTORE }

    private final Reason reason;

    public CapacityAdmissionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
