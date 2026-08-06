package world.willfrog.agent.platform.dataanalysis;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record DataAnalysisTerminalEnvelope(
        String runId,
        String toolCallId,
        int attempt,
        String operationId,
        String taskId,
        String terminalStatus,
        boolean success,
        String resultPreview,
        String rawRef,
        String errorCode,
        String errorMessage,
        boolean retryable,
        DataAnalysisEstimate estimate,
        DataAnalysisReservation reservation,
        DataAnalysisResourceUsage resourceUsage,
        Instant terminalAt,
        boolean background) {

    public static final int MAX_RESULT_PREVIEW_BYTES = 16 * 1024;

    public DataAnalysisTerminalEnvelope {
        runId = DataAnalysisContractSupport.requireText(runId, "runId");
        toolCallId = DataAnalysisContractSupport.requireText(toolCallId, "toolCallId");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        operationId = DataAnalysisContractSupport.requireText(operationId, "operationId");
        taskId = DataAnalysisContractSupport.requireText(taskId, "taskId");
        terminalStatus = DataAnalysisContractSupport.requireText(terminalStatus, "terminalStatus");
        if (terminalAt == null) {
            throw new IllegalArgumentException("terminalAt must not be null");
        }
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                runId,
                toolCallId,
                attempt);
        if (!identity.operationId().equals(operationId)) {
            throw new IllegalArgumentException("operationId must match runId/toolCallId/attempt");
        }
        resultPreview = normalizeOptional(resultPreview);
        if (resultPreview != null
                && resultPreview.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_PREVIEW_BYTES) {
            throw new IllegalArgumentException("resultPreview exceeds bounded preview limit");
        }
        rawRef = normalizeOptional(rawRef);
        errorCode = normalizeOptional(errorCode);
        errorMessage = normalizeOptional(errorMessage);
        if (!success && errorCode == null && errorMessage == null) {
            throw new IllegalArgumentException("failed terminal envelope requires errorCode or errorMessage");
        }
        if (success && resultPreview == null && rawRef == null) {
            throw new IllegalArgumentException("successful terminal envelope requires resultPreview or rawRef");
        }
        if (estimate == null) {
            throw new IllegalArgumentException("estimate must not be null");
        }
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }
        if (!reservation.identity().equals(identity)
                || !reservation.operationId().equals(operationId)) {
            throw new IllegalArgumentException("reservation identity must match terminal identity");
        }
        if (!taskId.equals(reservation.taskId())) {
            throw new IllegalArgumentException("reservation taskId must match terminal taskId");
        }
        if (reservation.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
            throw new IllegalArgumentException("terminal envelope requires terminal-confirmed reservation");
        }
        if (estimate.resourceClass() != reservation.resourceClass()
                || estimate.capacityUnits() != reservation.capacityUnits()) {
            throw new IllegalArgumentException("estimate must match reservation resource class and capacity units");
        }
        if (resourceUsage == null) {
            throw new IllegalArgumentException("resourceUsage must not be null; use explicit missing usage");
        }
        if (resourceUsage.resourceClass() != reservation.resourceClass()) {
            throw new IllegalArgumentException("resourceUsage class must match reservation");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
