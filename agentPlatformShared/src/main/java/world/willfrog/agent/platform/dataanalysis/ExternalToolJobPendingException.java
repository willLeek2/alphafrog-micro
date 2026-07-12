package world.willfrog.agent.platform.dataanalysis;

/**
 * Thrown when an external tool job exceeds the fast-path timeout and transitions to
 * background pending. ToolRouter rethrows this type; ToolRouterToolExecutor rethrows
 * it to avoid normal result compaction/cache/finished-event; the pipeline suspends
 * the current todo and returns a suspended workflow result.
 */
public class ExternalToolJobPendingException extends RuntimeException {

    private final String runId;
    private final String toolCallId;
    private final int attempt;

    public ExternalToolJobPendingException(String runId, String toolCallId, int attempt, String message) {
        super(message);
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.attempt = attempt;
    }

    public String getRunId() { return runId; }
    public String getToolCallId() { return toolCallId; }
    public int getAttempt() { return attempt; }
}
