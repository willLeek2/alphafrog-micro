package world.willfrog.agentlangchain.support.pool;

/**
 * 一次可控短节点的执行结果。
 *
 * @param nodeId        节点编号
 * @param payloadJson   提交进工作项的分段结果载荷
 * @param ending        结束方式
 * @param elapsedMillis 这一段实际跑了多久
 */
public record ShortNodeRun(String nodeId, String payloadJson, SegmentEnding ending, long elapsedMillis) {

    public boolean normal() {
        return ending == SegmentEnding.NORMAL_RESULT;
    }
}
