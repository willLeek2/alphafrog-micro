package world.willfrog.agent.platform.wait;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

/**
 * 一次恢复消费的结果。
 *
 * <p>两个布尔值只有两种合法组合：取走通知并且放行下一段，或者什么都没做。出现「取走了通知但没放行
 * 下一段」说明库里的状态与这段代码的假设不一致，调用方必须当成故障报出来。</p>
 *
 * @param consumed    通知是不是被这一次调用取走
 * @param promoted    下一段是不是被这一次调用放成可恢复
 * @param groupId         这条通知所属的等待组
 * @param nextSegment     被放行的那一段的完整身份；没放行时为 {@code null}
 * @param rejection       没消费成的原因，由消费语句自己给出；消费成功时为 {@code null}
 * @param rejectionDetail 与原因同一行读到的补充（Run 或下一段的实际状态），可空
 */
public record RecoveryConsumptionResult(
        boolean consumed,
        boolean promoted,
        Long groupId,
        NodeWorkItemIdentity nextSegment,
        RecoveryRejection rejection,
        String rejectionDetail) {

    public boolean succeeded() {
        // 成功必须带回完整的下一段身份：只有五个字段齐全才算真的放行了下一段，
        // 缺字段时通知已经不可逆地取走，调用方却不知道该投哪一段——那种「成功」比失败更糟。
        return consumed && promoted && nextSegment != null;
    }

    /** 取走了通知却没有放行下一段：这是状态不一致，不是「这次没轮到我」。 */
    public boolean inconsistent() {
        return consumed != promoted;
    }

    /** 被放行那一段的分段序号；没放行时为 {@code null}。 */
    public Integer nextSegmentSequence() {
        return nextSegment == null ? null : nextSegment.segmentSequence();
    }

    /**
     * 这次没消费成的原因是不是「永久不会再被服务」。
     *
     * <p>为真时调用方该把通知收口成关闭态并写明原因；为假时按原因推后下次可见时间。</p>
     */
    public boolean permanent() {
        return !succeeded() && rejection != null && rejection.permanent();
    }

    /** 这次是不是「连取走都没取成、也没有明确原因」的意外组合。 */
    public boolean unexplained() {
        return !succeeded() && rejection == null;
    }
}
