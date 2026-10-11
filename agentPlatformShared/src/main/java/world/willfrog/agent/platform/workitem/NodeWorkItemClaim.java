package world.willfrog.agent.platform.workitem;

import java.time.OffsetDateTime;

/**
 * 一次领取成功的结果。
 *
 * <p>{@code claimEpoch} 是领取成功后原子加一得到的执行权令牌：后面开始执行、提交分段结果、报执行失败、
 * 被取消都要带着它做条件更新，匹配不上的提交一律被拒。</p>
 *
 * <p>领取成功还会占用一个节点执行许可，由调用方（节点 Worker）在提交结果或被拒之后交还。</p>
 */
public record NodeWorkItemClaim(
        NodeWorkItemIdentity identity,
        String claimedBy,
        int claimEpoch,
        OffsetDateTime leaseExpiresAt) {

    public String describe() {
        return identity.describe() + " 由 " + claimedBy + " 领取，代际 " + claimEpoch;
    }
}
