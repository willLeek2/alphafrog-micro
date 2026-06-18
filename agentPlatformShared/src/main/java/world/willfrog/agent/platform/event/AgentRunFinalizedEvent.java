package world.willfrog.agent.platform.event;

/**
 * run 终态收敛事件。
 *
 * <p>由 {@link world.willfrog.agent.platform.event.AgentRunFinalizationService} 在 run DB 终态写入成功后发布；
 * 监听端在 agentService 侧订阅后调 WorkspaceDumpScheduler 异步 dump workspace 文件。
 *
 * <p>注意：不要直接订阅 AgentRunEvent 行的 WORKFLOW_COMPLETED —— 失败路径是 WORKFLOW_FAILED，
 * cancel/expire 也有独立分支；只盯 WORKFLOW_COMPLETED 会漏 dump。
 *
 * <h3>v0 跨 JVM 限制</h3>
 * <p>agentService 与 agentLangchainService 是两个独立 Spring Boot JVM。事件 publish 在调用方 JVM 内部，
 * 因此 agentLangchainService 发布的 {@code AgentRunFinalizedEvent} 不会在 agentService 触发 dump。
 * v0 接受这个 gap（agentService 路径全部覆盖，agentLangchainService 路径需 v1 走 Dubbo 回调或 DB polling）。
 *
 * @param runId           run 主键
 * @param userId          run 所属用户
 * @param terminalStatus  终态：COMPLETED / PARTIAL / FAILED / CANCELED / EXPIRED
 * @param conservative    true = EXPIRED 保守分支（缺消息/event 时只写状态 + 有限 meta）
 */
public record AgentRunFinalizedEvent(
        String runId,
        long userId,
        String terminalStatus,
        boolean conservative
) {
}
