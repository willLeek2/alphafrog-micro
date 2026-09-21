package world.willfrog.agent.platform.wait;

/**
 * 一条迟到结果的上报。
 *
 * <p>迟到指结果到达时这条等待链已经不可能给出完整结果（例如运行图已经推进、控制版本已经变了）。
 * 它只留审计，不参与恢复；同一个成员已经落过终态时不会被改动。</p>
 *
 * @param groupId             等待组编号
 * @param memberIdentity      成员的稳定身份
 * @param resultRefJson       结果引用，留档用
 * @param externalOperationId 外部作业身份，可为空
 */
public record LateMemberRequest(
        long groupId,
        String memberIdentity,
        String resultRefJson,
        String externalOperationId) {

    public LateMemberRequest {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        if (memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("成员稳定身份不能为空");
        }
        if (resultRefJson == null || resultRefJson.isBlank()) {
            throw new IllegalArgumentException("迟到结果也要留下引用，否则审计看不到它");
        }
    }
}
