package world.willfrog.agent.platform.event;

/**
 * run 终态收敛事件。
 *
 * <p>由 {@link world.willfrog.agent.platform.event.AgentRunFinalizationService} 在 run DB 终态写入成功后发布；
 * 当前监听端在 agentLangchainService 侧订阅后调 WorkspaceDumpScheduler 异步 dump workspace 文件。
 *
 * <p>注意：不要直接订阅 AgentRunEvent 行的 WORKFLOW_COMPLETED —— 失败路径是 WORKFLOW_FAILED，
 * cancel/expire 也有独立分支；只盯 WORKFLOW_COMPLETED 会漏 dump。
 *
 * <h3>事件边界</h3>
 * <p>Spring 事件只在发布方 JVM 内广播。agentLangchainService 发布的事件由同 JVM workspace listener 消费；
 * 为防进程重启、漏事件或历史终态 run 未补 dump，仍保留 WorkspacePollingObserver 做 DB reconciliation。
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
