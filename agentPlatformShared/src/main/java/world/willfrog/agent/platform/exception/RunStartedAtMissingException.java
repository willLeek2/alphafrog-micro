package world.willfrog.agent.platform.exception;

/**
 * 读取 Run 剩余墙钟时，{@code alphafrog_agent_run.started_at} 不存在或无法读取。
 * 这是数据问题，不是稍后重试能补上的瞬时失败。
 */
public class RunStartedAtMissingException extends RuntimeException {

    private final String runId;

    public RunStartedAtMissingException(String runId) {
        super("alphafrog_agent_run.started_at is missing; cannot compute remaining wall clock"
                + (runId == null || runId.isBlank() ? "" : " for run " + runId));
        this.runId = runId == null ? "" : runId;
    }

    public String runId() {
        return runId;
    }
}
