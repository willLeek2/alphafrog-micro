package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * 把后台工具的逻辑终态写成一条事件的钩子（走 appendOnce）。
 * 默认实现返回 false（阻止 finalizer 继续 CAS）；接入真实实现后，成功时返回 true。
 *
 * <p><b>幂等性：</b>finalizer 在事件发出之后，若后续数据库步骤标记写入失败，可能重入本钩子。
 * 实现必须以 {@code runId + ":" + anchor.getToolCallId() + ":logical_terminal"} 为稳定 key
 * 保证幂等：同一 key 重复调用只产生一条已确认写入的事件，不产生重复。</p>
 */
@FunctionalInterface
public interface ToolJobEventHook {
    /**
     * @param runId  Agent Run 标识
     * @param anchor 数据库里的 anchor 记录（用 {@code runId + ":" + anchor.getToolCallId()
     *               + ":logical_terminal"} 作为稳定的幂等 key）
     * @return 终态事件已确认发出时返回 true
     */
    boolean emitTerminalEvent(String runId, ToolJobAnchor anchor);
}
