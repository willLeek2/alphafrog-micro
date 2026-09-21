package world.willfrog.agentlangchain.control.dualpool;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 一条待处理的事实这一轮还取不走时，下一次什么时候再来（恢复通知与等待成员结果共用这一套）。
 *
 * <p>规则两条。第一，等得越久隔得越远：一条通知从创建到现在已经等了几个「起步间隔」，就往后推
 * 2 的那么多次方个间隔，封顶到上限。刚写出来的通知马上就会再试一次；一直取不走的稀疏下来，
 * 不让它每一轮都占掉一个候选名额。</p>
 *
 * <p>第二，同一刻积压的多条通知不能整批同时重试：抖动由通知编号与当前步数决定，同样的输入算出来
 * 一样（可复现、可测），不同通知之间错开四分之一档。</p>
 *
 * <p>上限决定的最坏恢复延迟：周期补扫这一路，一条通知最多等「上限加抖动」再被重新看到。</p>
 */
public final class RecoveryBackoff {

    /** 指数退避最多翻这么多步，再往上只受上限约束。 */
    private static final int MAX_STEP = 6;

    private final Duration base;
    private final Duration max;

    public RecoveryBackoff(Duration base, Duration max) {
        if (base == null || base.isZero() || base.isNegative()) {
            throw new IllegalArgumentException("退避的起步间隔必须为正数");
        }
        if (max == null || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("退避上限不能小于起步间隔");
        }
        this.base = base;
        this.max = max;
    }

    /** 已经等了这么久时，这一次退避的间隔（不含抖动）。 */
    public Duration delayFor(Duration waited) {
        long baseMillis = base.toMillis();
        long waitedMillis = waited == null ? 0L : Math.max(0L, waited.toMillis());
        int step = (int) Math.min(waitedMillis / baseMillis, MAX_STEP);
        long delayMillis = baseMillis << step;
        if (delayMillis <= 0) {
            delayMillis = max.toMillis();
        }
        return Duration.ofMillis(Math.min(delayMillis, max.toMillis()));
    }

    /** 抖动：同一个通知在同一个间隔上算出来一样，不同通知之间错开。 */
    public Duration jitterFor(long notificationId, Duration delay) {
        long span = Math.max(1L, delay.toMillis() / 4);
        long mixed = mix(notificationId * 1_000_003L + delay.toMillis());
        return Duration.ofMillis(Math.floorMod(mixed, span));
    }

    /** 下一次可见时刻：现在加上退避间隔，再加抖动。 */
    public OffsetDateTime nextVisibleAt(OffsetDateTime now, long notificationId, OffsetDateTime createdAt) {
        Duration waited = createdAt == null ? Duration.ZERO : Duration.between(createdAt, now);
        Duration delay = delayFor(waited);
        return now.plus(delay).plus(jitterFor(notificationId, delay));
    }

    /** 按上限与起步间隔描述自己，供日志与快照用。 */
    public String describe() {
        return "base=" + base.toMillis() + "ms max=" + max.toMillis() + "ms";
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
