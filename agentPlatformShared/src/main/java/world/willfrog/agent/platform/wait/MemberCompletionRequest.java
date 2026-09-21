package world.willfrog.agent.platform.wait;

/**
 * 一次成员结束的上报。
 *
 * @param groupId             等待组编号
 * @param memberIdentity      成员的稳定身份
 * @param memberState         落定的终态，只允许成功或失败
 * @param resultRefJson       结果引用
 * @param externalOperationId 外部作业身份；要求与库里的值逐字一致（两边都是空也算一致）
 * @param planGeneration      上报时看到的计划代际，与库里不一致就整条拒绝
 * @param contextVersion      这一段挂起时的上下文版本，与库里不一致就整条拒绝
 * @param runControlVersion   上报时看到的 Run 控制版本，与库里不一致就整条拒绝
 */
public record MemberCompletionRequest(
        long groupId,
        String memberIdentity,
        WaitMemberState memberState,
        String resultRefJson,
        String externalOperationId,
        int planGeneration,
        long contextVersion,
        long runControlVersion) {

    public MemberCompletionRequest {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        if (memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("成员稳定身份不能为空");
        }
        if (memberState == null || !memberState.countsAsCompleted()) {
            throw new IllegalArgumentException(
                    "正常结束只允许成功或失败，取消与迟到走另外两条路：" + memberState);
        }
        if (resultRefJson == null || resultRefJson.isBlank()) {
            throw new IllegalArgumentException("结束必须带结果引用，否则恢复时接不回结果");
        }
        if (planGeneration < 0) {
            throw new IllegalArgumentException("计划代际不能是负数：" + planGeneration);
        }
        if (contextVersion < 0) {
            throw new IllegalArgumentException("上下文版本不能是负数：" + contextVersion);
        }
        if (runControlVersion < 0) {
            throw new IllegalArgumentException("控制版本不能是负数：" + runControlVersion);
        }
    }
}
