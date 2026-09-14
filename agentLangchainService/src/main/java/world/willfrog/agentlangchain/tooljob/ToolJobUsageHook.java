package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * 把终态资源用量 upsert 到用量记录器的钩子。
 * 默认实现返回 false（阻止 finalizer 继续 CAS）；接入真实实现后，成功时返回 true。
 *
 * <p><b>幂等性：</b>finalizer 在成功 upsert 之后，若后续数据库步骤标记写入失败，可能重入本钩子。
 * 实现必须以 {@code anchor.getOperationId()}（即 runId:toolCallId:attempt）为稳定 key
 * 保证幂等：同一 key 重复调用只产生一条已确认写入的记录，不产生重复。</p>
 */
@FunctionalInterface
public interface ToolJobUsageHook {
    /**
     * @param runId  Agent Run 标识
     * @param anchor 数据库里的 anchor 记录（用 {@code anchor.getOperationId()}
     *               作为稳定幂等 key，即 runId:toolCallId:attempt）
     * @return 用量记录已确认写入成功时返回 true
     */
    boolean upsertUsage(String runId, ToolJobAnchor anchor);
}
