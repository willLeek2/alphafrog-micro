package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;

public record DataAnalysisTerminalEnvelope(
        String runId,
        String toolCallId,
        int attempt,
        String operationId,
        String taskId,
        String terminalStatus,
        boolean success,
        String resultJson,
        String rawRef,
        String errorCode,
        String errorMessage,
        DataAnalysisResourceUsage resourceUsage,
        Instant terminalAt,
        boolean background) {

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
        resultJson = normalizeOptional(resultJson);
        rawRef = normalizeOptional(rawRef);
        errorCode = normalizeOptional(errorCode);
        errorMessage = normalizeOptional(errorMessage);
        if (!success && errorCode == null && errorMessage == null) {
            throw new IllegalArgumentException("failed terminal envelope requires errorCode or errorMessage");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
