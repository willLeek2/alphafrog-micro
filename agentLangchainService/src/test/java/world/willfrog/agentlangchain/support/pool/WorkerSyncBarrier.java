package world.willfrog.agentlangchain.support.pool;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 两个 Worker 的同步屏障：让两个 Worker 在同一个时间点上一起冲同一个工作项。
 *
 * <p>「首次领取竞争」要复现的是「同一行被两个 Worker 同时领」：先让两个 Worker 都走到屏障前，
 * 一起放行，再分别去调条件领取。没有这个屏障，第二个 Worker 常常在第一个已经领完之后才动手，
 * 条件更新的影响行数就只会是 `[1]`，看不出竞争。</p>
 *
 * <p>放行后再次使用要先 {@link #reset()}。</p>
 */
public class WorkerSyncBarrier {

    private final int parties;
    private volatile CountDownLatch latch;

    public WorkerSyncBarrier(int parties) {
        if (parties < 2) {
            throw new IllegalArgumentException("屏障至少要有两个参与者：" + parties);
        }
        this.parties = parties;
        this.latch = new CountDownLatch(parties);
    }

    /** 在屏障前等其它 Worker；到齐返回 true，超时返回 false。 */
    public boolean await(Duration timeout) throws InterruptedException {
        CountDownLatch current = latch;
        current.countDown();
        return current.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void reset() {
        latch = new CountDownLatch(parties);
    }

    public int parties() {
        return parties;
    }

    public long waiting() {
        return latch.getCount();
    }
}
