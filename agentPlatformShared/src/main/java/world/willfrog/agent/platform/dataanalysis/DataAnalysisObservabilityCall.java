package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;

/** 单次 data-analysis attempt 的可观测记录。 */
public record DataAnalysisObservabilityCall(
        String toolCallId,
        int attempt,
        String operationId,
        String taskId,
        DataAnalysisEstimate estimate,
        DataAnalysisReservation reservation,
        DataAnalysisResourceUsage resourceUsage,
        String terminalStatus,
        boolean success,
        boolean retryable,
        Instant terminalAt,
        boolean background) {

    public DataAnalysisObservabilityCall {
        toolCallId = DataAnalysisContractSupport.requireText(toolCallId, "toolCallId");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        operationId = DataAnalysisContractSupport.requireText(operationId, "operationId");
        taskId = DataAnalysisContractSupport.requireText(taskId, "taskId");
        terminalStatus = DataAnalysisContractSupport.requireText(terminalStatus, "terminalStatus");
        if (estimate == null || reservation == null || resourceUsage == null) {
            throw new IllegalArgumentException("estimate, reservation and resourceUsage must not be null");
        }
        if (terminalAt == null) {
            throw new IllegalArgumentException("terminalAt must not be null");
        }
        DataAnalysisOperationIdentity identity = reservation.identity();
        if (!identity.toolCallId().equals(toolCallId)
                || identity.attempt() != attempt
                || !identity.operationId().equals(operationId)) {
            throw new IllegalArgumentException("call identity must match reservation identity");
        }
        if (!taskId.equals(reservation.taskId())) {
            throw new IllegalArgumentException("taskId must match reservation taskId");
        }
        if (reservation.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
            throw new IllegalArgumentException("observability call requires terminal-confirmed reservation");
        }
        if (estimate.resourceClass() != reservation.resourceClass()
                || estimate.capacityUnits() != reservation.capacityUnits()
                || resourceUsage.resourceClass() != reservation.resourceClass()) {
            throw new IllegalArgumentException("estimate, reservation and usage resource contract must match");
        }
    }

    public static DataAnalysisObservabilityCall fromEnvelope(DataAnalysisTerminalEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        return new DataAnalysisObservabilityCall(
                envelope.toolCallId(),
                envelope.attempt(),
                envelope.operationId(),
                envelope.taskId(),
                envelope.estimate(),
                envelope.reservation(),
                envelope.resourceUsage(),
                envelope.terminalStatus(),
                envelope.success(),
                envelope.retryable(),
                envelope.terminalAt(),
                envelope.background());
    }
}
