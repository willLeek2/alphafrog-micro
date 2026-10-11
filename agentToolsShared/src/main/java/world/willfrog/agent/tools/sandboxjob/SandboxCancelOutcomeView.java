package world.willfrog.agent.tools.sandboxjob;

/**
 * 取消墓碑的确认视图（工具中立）。
 *
 * @param taskId      取消操作确认的任务号
 * @param outcomeName 沙箱取消结果枚举名（仅用于日志）
 */
public record SandboxCancelOutcomeView(String taskId, String outcomeName) {
}
