package world.willfrog.agent.platform.wait;

/**
 * 整组挂起的结果。
 *
 * @param outcome              这一次做成了什么
 * @param groupId              等待组编号；只有第一、二种结果有值
 * @param nextSegmentSequence  这次挂起之后继续执行的分段序号；只有第一、二种结果有值
 */
public record WaitSuspensionResult(WaitSuspensionOutcome outcome, Long groupId, Integer nextSegmentSequence) {

    public boolean suspended() {
        return outcome != WaitSuspensionOutcome.SEGMENT_NOT_MATCHED;
    }
}
