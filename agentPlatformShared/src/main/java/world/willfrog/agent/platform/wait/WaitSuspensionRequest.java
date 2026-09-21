package world.willfrog.agent.platform.wait;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 整组挂起的请求：一次模型回合里并列发出的整组工具请求，连同「当前分段是哪一段」一起提交。
 *
 * <p>{@code versions} 是当前执行分段上的四类版本（计划代际在身份里、上下文版本、控制版本、领取代际），
 * 四项都要匹配才允许交出这一段；不匹配说明这一段已经不归调用方所有。</p>
 *
 * <p>成员的稳定身份在这里一次算好（见 {@link WaitMemberIdentity}），跟成员一起落库；重试时读库里的值，
 * 不再重新生成。</p>
 *
 * @param segment                当前执行分段的身份
 * @param versions               当前执行分段上的版本与领取代际
 * @param claimant               当前分段的领取者，必须与库里的值一致
 * @param modelTurn              这是这个分段里的第几次模型回复
 * @param schedulerVersion       调度器版本，冗余写进等待组便于按版本过滤
 * @param members                整组工具请求，原始顺序
 * @param suspensionPayloadJson  并入当前分段载荷的挂起标记
 * @param nextSegmentPayloadJson 下一段的初始载荷，含恢复时要用的检查点引用
 */
public record WaitSuspensionRequest(
        NodeWorkItemIdentity segment,
        NodeWorkItemVersions versions,
        String claimant,
        int modelTurn,
        SchedulerVersion schedulerVersion,
        List<WaitMemberDraft> members,
        String suspensionPayloadJson,
        String nextSegmentPayloadJson) {

    /** 成员数量上限的粗界；生效上限由配置决定，这里只拦明显不对的输入。 */
    public static final int MAX_MEMBERS = 128;

    public WaitSuspensionRequest {
        if (segment == null || versions == null) {
            throw new IllegalArgumentException("整组挂起必须带当前分段的身份与版本");
        }
        if (claimant == null || claimant.isBlank()) {
            throw new IllegalArgumentException("整组挂起必须带当前分段的领取者");
        }
        if (schedulerVersion == null) {
            throw new IllegalArgumentException("整组挂起必须带调度器版本");
        }
        if (modelTurn < 0) {
            throw new IllegalArgumentException("模型回合不能是负数：" + modelTurn);
        }
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("整组挂起至少要有一个成员，没有成员的等待组没有意义");
        }
        if (members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("成员数超过上限 " + MAX_MEMBERS + "：" + members.size());
        }
        Set<Integer> sequences = new HashSet<>();
        for (WaitMemberDraft member : members) {
            if (member == null) {
                throw new IllegalArgumentException("整组挂起里出现了空成员");
            }
            if (!sequences.add(member.getMemberSeq())) {
                throw new IllegalArgumentException("成员原始序号重复：" + member.getMemberSeq());
            }
        }
        if (suspensionPayloadJson == null || suspensionPayloadJson.isBlank()) {
            throw new IllegalArgumentException("整组挂起必须带挂起标记，否则恢复时分不清这一段是怎么停下的");
        }
        if (nextSegmentPayloadJson == null || nextSegmentPayloadJson.isBlank()) {
            throw new IllegalArgumentException("整组挂起必须给下一段留下检查点引用");
        }
    }
}
