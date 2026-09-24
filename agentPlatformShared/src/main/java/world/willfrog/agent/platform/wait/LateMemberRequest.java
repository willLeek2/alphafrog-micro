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
 * @param externalOperationId 外部作业身份；要求与库里的值逐字一致（两边都是空也算一致）
 * @param runControlVersion   上报时看到的 Run 控制版本：只有它仍然一致，这次上报才有权停掉这条链；
 *                            版本已经变了的旧链只留审计
 */
public record LateMemberRequest(
        long groupId,
        String memberIdentity,
        String resultRefJson,
        String externalOperationId,
        long runControlVersion) {

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
        if (runControlVersion < 0) {
            throw new IllegalArgumentException("控制版本不能是负数：" + runControlVersion);
        }
    }
}
