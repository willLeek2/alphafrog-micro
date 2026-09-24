package world.willfrog.agent.platform.lease;

import java.time.OffsetDateTime;

/**
 * 一条 Run 的服务所有权：谁拿着、第几代、什么时候到期。
 *
 * @param runId           这条 Run
 * @param ownerInstanceId 持有者标识（应用名 + 主机 + 进程号 + 本次启动的短标识）
 * @param fencingToken    代际号：换主人时加一，同一位主人重复领取不变。迟到的写入拿旧号进来会写不动
 * @param acquiredAt      这一行第一次被建出来的时间
 * @param renewedAt       最近一次续期时间
 * @param expiresAt       到期时间：过了这一刻，别人可以按条件接走
 */
public record RunServiceLease(
        String runId,
        String ownerInstanceId,
        long fencingToken,
        OffsetDateTime acquiredAt,
        OffsetDateTime renewedAt,
        OffsetDateTime expiresAt) {

    /**
     * 生产会话把 BIGINT 读成装箱的 {@code Long}。MyBatis 按实参类型找构造器，
     * 找不到 {@code long} 那一档时，领取语句会在行已经写入之后把映射异常抛回调用方。
     */
    public RunServiceLease(String runId, String ownerInstanceId, Long fencingToken,
                           OffsetDateTime acquiredAt, OffsetDateTime renewedAt,
                           OffsetDateTime expiresAt) {
        this(runId, ownerInstanceId, fencingToken == null ? 0L : fencingToken.longValue(),
                acquiredAt, renewedAt, expiresAt);
    }

    /** 这一刻是不是已经过期：过期只说明「可以有人来接」，不等于已经没人在跑了。 */
    public boolean expiredAt(OffsetDateTime now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /** 这一刻是不是由这一位、这一代持有。 */
    public boolean heldBy(String owner, long token) {
        return ownerInstanceId != null && fencingToken == token
                && ownerInstanceId.equals(owner);
    }

    /** 给日志与读数用的一句话描述。 */
    public String describe() {
        return "run=" + runId + " owner=" + ownerInstanceId + " token=" + fencingToken
                + " expiresAt=" + expiresAt;
    }
}
