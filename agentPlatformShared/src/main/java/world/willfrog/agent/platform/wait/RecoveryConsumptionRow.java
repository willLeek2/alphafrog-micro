package world.willfrog.agent.platform.wait;

import lombok.Data;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

/**
 * 恢复消费语句返回的原始计数行：通知取走了没有、下一段放行了没有，以及被放行那一段的身份。
 *
 * <p>身份随结果一起返回，是因为周期补扫那一路手上只有一条通知：没有身份就没法把下一段投给节点池，
 * 而为了拿身份再读一次库，中间给了别的写入改状态的机会。</p>
 */
@Data
public class RecoveryConsumptionRow {

    private Integer consumed;
    private Integer promoted;
    private Long groupId;
    private String nextRunId;
    private Integer nextPlanGeneration;
    private String nextNodeId;
    private Integer nextNodeAttempt;
    private Integer nextSegmentSequence;

    /** 放行出来的分段身份；没放行时为 {@code null}。 */
    public NodeWorkItemIdentity nextSegment() {
        if (nextRunId == null || nextPlanGeneration == null || nextNodeId == null
                || nextNodeAttempt == null || nextSegmentSequence == null) {
            return null;
        }
        return new NodeWorkItemIdentity(nextRunId, nextPlanGeneration, nextNodeId,
                nextNodeAttempt, nextSegmentSequence);
    }
}
