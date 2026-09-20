package world.willfrog.agentlangchain.support.pool;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可暂停的 Worker：能停在分段的中间，并且能拿出「它确实已经停了」的凭据。
 *
 * <p>「旧持有者迟到提交」这一组验收要求：先把原执行者停下来，确认它已经不在跑，再由测试控制面显式转交。
 * 这里的暂停不是「请求了就算停了」：Worker 必须在安全点调用 {@link #reportStopped()} 报告自己停了，
 * {@link #awaitStopped} 等到这份报告才算数。</p>
 */
public class PausableWorker {

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile CountDownLatch stoppedLatch = new CountDownLatch(1);
    private volatile CountDownLatch resumeLatch = new CountDownLatch(1);

    /**
     * 请求暂停：Worker 会在下一个安全点停下来。
     *
     * <p>两个闩都换成新的：这样 {@link #awaitStopped} 等的一定是「这一次暂停之后」的停止报告，
     * 不会把上一轮的停止当成这一轮的凭据。</p>
     */
    public void pause() {
        stoppedLatch = new CountDownLatch(1);
        resumeLatch = new CountDownLatch(1);
        stopped.set(false);
        paused.set(true);
    }

    /** Worker 在安全点报告「我停了」。 */
    public void reportStopped() {
        stopped.set(true);
        stoppedLatch.countDown();
    }

    /** 在安全点等恢复；恢复前一直阻塞。 */
    public boolean awaitResume(Duration timeout) throws InterruptedException {
        return resumeLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 等 Worker 报告「已经停了」；超时返回 false，调用方不得在 false 的情况下继续做转交。 */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return stoppedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 恢复：Worker 从安全点继续跑。 */
    public void resume() {
        paused.set(false);
        resumeLatch.countDown();
    }

    public boolean isPaused() {
        return paused.get();
    }

    /** 已经报告过停止。 */
    public boolean hasStopped() {
        return stopped.get();
    }
}
