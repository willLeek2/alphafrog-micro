package world.willfrog.agent.platform.finance;

/** Complete backend-only input shared by synchronous and asynchronous terminal paths. */
public record FinanceRecordExtractionRequest(
        String runId,
        String userId,
        String todoId,
        String executePythonToolCallId,
        String entryPoint,
        String taskId,
        String terminalStatus,
        int exitCode,
        String stdout,
        String stderr,
        FinanceRecordChannelMetadata channelMetadata,
        FinanceEnvironmentFact executionEnvironment,
        FinanceEnvironmentFact targetEnvironment,
        FinanceRecordChannelLimits limits) {

    public FinanceRecordExtractionRequest {
        runId = normalize(runId);
        userId = normalize(userId);
        todoId = normalize(todoId);
        executePythonToolCallId = normalize(executePythonToolCallId);
        entryPoint = normalize(entryPoint);
        taskId = normalize(taskId);
        terminalStatus = normalize(terminalStatus);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
