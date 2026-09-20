package world.willfrog.agent.platform.workitem;

/**
 * 节点工作项的稳定身份，五个字段一组。
 *
 * <p>{@code planGeneration} 同时是四类版本里的「计划代际」：它既参与身份，也参与版本比较，
 * 因为这张图重新规划过几次本身就是一个不可变坐标。{@code nodeAttempt} 是编排器重做整个节点的次数，
 * {@code segmentSequence} 是同一次节点尝试内部的执行分段序号；工具调用自己的重试次数不属于这两个字段。</p>
 *
 * <p>五个字段一组上有数据库唯一约束：领取时的条件更新只能解决同一行的竞争，替代不了这条约束。</p>
 */
public record NodeWorkItemIdentity(
        String runId,
        int planGeneration,
        String nodeId,
        int nodeAttempt,
        int segmentSequence) {

    public NodeWorkItemIdentity {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        if (planGeneration < 0 || nodeAttempt < 0 || segmentSequence < 0) {
            throw new IllegalArgumentException(
                    "计划代际、第几次尝试与第几段执行都不能是负数：" + planGeneration + "/" + nodeAttempt + "/" + segmentSequence);
        }
    }

    /** 由一行工作项取身份。 */
    public static NodeWorkItemIdentity of(NodeWorkItem item) {
        return new NodeWorkItemIdentity(
                item.getRunId(), item.getPlanGeneration(), item.getNodeId(),
                item.getNodeAttempt(), item.getSegmentSequence());
    }

    /** 日志与拒绝事实里的紧凑写法：run/g代际/node/a尝试/s分段。 */
    public String describe() {
        return runId + "/g" + planGeneration + "/" + nodeId + "/a" + nodeAttempt + "/s" + segmentSequence;
    }
}
