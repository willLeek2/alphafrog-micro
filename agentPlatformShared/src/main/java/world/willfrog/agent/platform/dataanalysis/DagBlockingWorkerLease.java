package world.willfrog.agent.platform.dataanalysis;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DAG 同步等待 worker 的共享租约契约。
 *
 * <p>owner id 在当前 JVM 生命周期内保持稳定，不使用 hostname 或其他可能被多个实例
 * 共享的外部标识。等待线程每次续租都必须通过 operationId、ownerId、run disposition
 * 和当前 Run 状态共同做数据库 CAS；本类只统一 owner/时间语义，不替代持久化 fencing。</p>
 */
public final class DagBlockingWorkerLease {

    /**
     * 允许短暂调度抖动，同时让失联 worker 在有界时间内进入 cleanup-only 恢复。
     */
    public static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private static final String PROCESS_OWNER_ID = "dag-blocking-" + UUID.randomUUID();

    private DagBlockingWorkerLease() {
    }

    /**
     * 返回当前 Java 进程稳定且跨进程唯一的 DAG blocking owner id。
     */
    public static String processOwnerId() {
        return PROCESS_OWNER_ID;
    }

    /**
     * 计算一次标准续租的到期时间。
     */
    public static Instant renewedUntil(Instant now) {
        return renewedUntil(now, LEASE_DURATION);
    }

    static Instant renewedUntil(Instant now, Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return now.plus(leaseDuration);
    }

    /**
     * 空租约兼容升级前的 NO_RESUME 锚点，按已过期处理；到期边界也允许接管。
     */
    public static boolean isExpired(Instant leaseUntil, Instant now) {
        Objects.requireNonNull(now, "now");
        return leaseUntil == null || !leaseUntil.isAfter(now);
    }
}
