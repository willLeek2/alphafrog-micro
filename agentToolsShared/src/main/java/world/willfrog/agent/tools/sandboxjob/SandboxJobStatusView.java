package world.willfrog.agent.tools.sandboxjob;

/**
 * 一次状态轮询的规范化视图（工具中立）。
 *
 * @param statusName 沙箱返回的原始状态名（SUCCEEDED / FAILED / CANCELED / NOT_FOUND / RUNNING 等）
 * @param error      状态响应里附带的错误文本；没有为空串
 */
public record SandboxJobStatusView(String statusName, String error) {
}
