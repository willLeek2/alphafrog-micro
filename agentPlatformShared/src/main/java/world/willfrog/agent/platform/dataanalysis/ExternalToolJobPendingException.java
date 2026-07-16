package world.willfrog.agent.platform.dataanalysis;

/**
 * 外部工具超过同步等待窗口后抛出的“主动让出执行权”信号。
 *
 * <p>它不是工具失败：Sandbox 任务仍在后台运行。异常只负责把稳定的
 * {@code runId + toolCallId + attempt} 沿调用栈送回工作流，使上层停止占用
 * Agent worker，并把同一身份写入持久化 checkpoint。</p>
 *
 * <p>返回值不是通过普通工具结果传递，是为了避免误写“工具已完成”事件、
 * 结果压缩和缓存。恢复端必须等终态结果持久化后，凭这三个字段找到原调用。</p>
 */
public class ExternalToolJobPendingException extends RuntimeException {

    // runId 把后台任务绑定到唯一 Agent Run，防止跨 run 误恢复。
    private final String runId;
    // toolCallId 标识本轮 Todo 内的逻辑工具调用，也是恢复时的身份主键之一。
    private final String toolCallId;
    // attempt 区分同一 toolCallId 的重试轮次，旧轮次结果不能唤醒新轮次。
    private final int attempt;

    public ExternalToolJobPendingException(String runId, String toolCallId, int attempt, String message) {
        // message 仅用于日志诊断；调度正确性只依赖下面三个结构化字段。
        super(message);
        // 保存不可变身份，确保异常跨多层捕获/重抛后不会丢失定位信息。
        this.runId = runId;
        // 保留原工具调用标识，后续 checkpoint 与终态 envelope 必须完全一致。
        this.toolCallId = toolCallId;
        // 固化当前尝试次数，恢复端据此拒绝过期结果。
        this.attempt = attempt;
    }

    public String getRunId() { return runId; }
    public String getToolCallId() { return toolCallId; }
    public int getAttempt() { return attempt; }
}
