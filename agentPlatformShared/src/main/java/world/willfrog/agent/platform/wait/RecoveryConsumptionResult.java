package world.willfrog.agent.platform.wait;

/**
 * 一次恢复消费的结果。
 *
 * <p>两个布尔值只有两种合法组合：取走通知并且放行下一段，或者什么都没做。出现「取走了通知但没放行
 * 下一段」说明库里的状态与这段代码的假设不一致，调用方必须当成故障报出来。</p>
 *
 * @param consumed            通知是不是被这一次调用取走
 * @param promoted            下一段是不是被这一次调用放成可恢复
 * @param groupId             这条通知所属的等待组
 * @param nextSegmentSequence 被放行的分段序号
 */
public record RecoveryConsumptionResult(
        boolean consumed,
        boolean promoted,
        Long groupId,
        Integer nextSegmentSequence) {

    public boolean succeeded() {
        return consumed && promoted;
    }

    /** 取走了通知却没有放行下一段：这是状态不一致，不是「这次没轮到我」。 */
    public boolean inconsistent() {
        return consumed != promoted;
    }
}
