package world.willfrog.agent.platform.dataanalysis;

public record DataAnalysisOperationIdentity(String runId, String toolCallId, int attempt) {

    public DataAnalysisOperationIdentity {
        runId = DataAnalysisContractSupport.requireText(runId, "runId");
        toolCallId = DataAnalysisContractSupport.requireText(toolCallId, "toolCallId");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (runId.indexOf(':') >= 0 || toolCallId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("runId and toolCallId must not contain ':'");
        }
    }

    public String operationId() {
        return runId + ":" + toolCallId + ":" + attempt;
    }

    public String reservationId() {
        return operationId();
    }
}
