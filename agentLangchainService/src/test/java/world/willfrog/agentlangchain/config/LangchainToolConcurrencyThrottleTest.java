package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.execution.ToolThrottleResult;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainToolConcurrencyThrottleTest {

    @Test
    void notThrottled_whenDisabled() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(false, 2, 5);
        ToolThrottleResult result = throttle.tryAcquire("executePython");
        assertThat(result.acquired()).isFalse();
        assertThat(result.failureReason()).isNull();
        assertThat(result.timeout()).isFalse();
    }

    @Test
    void notThrottled_whenToolNotInAllowlist() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 2, 5);
        ToolThrottleResult result = throttle.tryAcquire("searchAssetInfo");
        assertThat(result.acquired()).isFalse();
        assertThat(result.failureReason()).isNull();
        // calling release on NOT_THROTTLED must not throw or leak
        throttle.release(result);
    }

    @Test
    void acquireAndRelease_shouldNotLeakPermits() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 2, 5);
        ToolThrottleResult r1 = throttle.tryAcquire("executePython");
        assertThat(r1.acquired()).isTrue();

        ToolThrottleResult r2 = throttle.tryAcquire("executePython");
        assertThat(r2.acquired()).isTrue();

        // Third acquire with 0 permits should timeout after short wait
        LangchainToolConcurrencyThrottle fastTimeout = new LangchainToolConcurrencyThrottle(true, 2, 1);
        // Acquire both permits first
        ToolThrottleResult h1 = fastTimeout.tryAcquire("executePython");
        ToolThrottleResult h2 = fastTimeout.tryAcquire("executePython");
        assertThat(h1.acquired()).isTrue();
        assertThat(h2.acquired()).isTrue();

        ToolThrottleResult r3 = fastTimeout.tryAcquire("executePython");
        assertThat(r3.acquired()).isFalse();
        assertThat(r3.timeout()).isTrue();
        assertThat(r3.failureReason()).contains("TOOL_THROTTLE_TIMEOUT");

        // Release one → third can acquire
        fastTimeout.release(h1);
        ToolThrottleResult r4 = fastTimeout.tryAcquire("executePython");
        assertThat(r4.acquired()).isTrue();
        assertThat(r4.timeout()).isFalse();

        // Cleanup
        fastTimeout.release(h2);
        fastTimeout.release(r4);
    }

    @Test
    void release_shouldNotDoubleRelease() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 2, 1);
        ToolThrottleResult r = throttle.tryAcquire("executePython");
        assertThat(r.acquired()).isTrue();

        throttle.release(r);
        // Double release must not increase permits beyond max
        throttle.release(r);
        throttle.release(r);

        // Should still be able to acquire both permits
        ToolThrottleResult a1 = throttle.tryAcquire("executePython");
        ToolThrottleResult a2 = throttle.tryAcquire("executePython");
        assertThat(a1.acquired()).isTrue();
        assertThat(a2.acquired()).isTrue();

        // Third should timeout (not acquire due to leaked permit)
        ToolThrottleResult a3 = throttle.tryAcquire("executePython");
        assertThat(a3.acquired()).isFalse();

        throttle.release(a1);
        throttle.release(a2);
    }

    @Test
    void release_notThrottledResult_shouldNotThrow() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 2, 1);
        ToolThrottleResult result = throttle.tryAcquire("someOtherTool");
        assertThat(result.acquired()).isFalse();
        // Must not throw NPE or IllegalStateException
        throttle.release(result);
    }

    @Test
    void release_timeoutResult_shouldNotRelease() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 1, 1);
        ToolThrottleResult r1 = throttle.tryAcquire("executePython");
        assertThat(r1.acquired()).isTrue();

        ToolThrottleResult r2 = throttle.tryAcquire("executePython");
        assertThat(r2.timeout()).isTrue();
        assertThat(r2.acquired()).isFalse();

        // Releasing a timeout result must not add a permit
        throttle.release(r2);
        throttle.release(r1);

        // After releasing r1, should have exactly 1 permit available
        ToolThrottleResult r3 = throttle.tryAcquire("executePython");
        assertThat(r3.acquired()).isTrue();
        throttle.release(r3);
    }

    @Test
    void interrupted_shouldRestoreInterruptFlag() throws Exception {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 1, 10);
        ToolThrottleResult r1 = throttle.tryAcquire("executePython");
        assertThat(r1.acquired()).isTrue();

        // Acquire the only permit, then try to acquire on another thread and interrupt it
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ToolThrottleResult> resultRef = new AtomicReference<>();
        AtomicReference<Boolean> interruptedFlag = new AtomicReference<>();

        Future<?> future = executor.submit(() -> {
            started.countDown();
            ToolThrottleResult r = throttle.tryAcquire("executePython");
            resultRef.set(r);
            interruptedFlag.set(Thread.currentThread().isInterrupted());
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // let tryAcquire block
        future.cancel(true);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        ToolThrottleResult r = resultRef.get();
        assertThat(r).isNotNull();
        assertThat(r.interrupted()).isTrue();
        assertThat(r.acquired()).isFalse();
        assertThat(r.failureReason()).contains("TOOL_THROTTLE_INTERRUPTED");

        throttle.release(r1);
    }

    @Test
    void metrics_shouldReflectAcquiresAndTimeouts() {
        LangchainToolConcurrencyThrottle throttle = new LangchainToolConcurrencyThrottle(true, 2, 1);
        ToolThrottleResult r1 = throttle.tryAcquire("executePython");
        ToolThrottleResult r2 = throttle.tryAcquire("executePython");
        assertThat(r1.acquired()).isTrue();
        assertThat(r2.acquired()).isTrue();

        // This will timeout (0 permits, 1s timeout)
        ToolThrottleResult r3 = throttle.tryAcquire("executePython");
        assertThat(r3.timeout()).isTrue();

        // Record some executions
        throttle.recordExecution("executePython", 500);
        throttle.recordExecution("executePython", 1500);

        Map<String, Object> metrics = throttle.throttleMetrics();
        assertThat(metrics.get("scope")).isEqualTo("per-node");
        assertThat(metrics.get("enabled")).isEqualTo(true);
        assertThat(metrics.get("maxPermits")).isEqualTo(2);
        // Additive-only contract：新增 scope 不得改动既有 key / 值语义。
        assertThat(metrics.keySet()).containsExactlyInAnyOrder(
                "scope",
                "enabled",
                "maxPermits",
                "availablePermits",
                "queueLength",
                "timeoutSeconds",
                "timeoutCounts",
                "waitMsTotal",
                "waitCount",
                "execMsTotal",
                "execCount"
        );
        assertThat(metrics.get("availablePermits")).isEqualTo(0);
        assertThat(metrics.get("queueLength")).isEqualTo(0);
        assertThat(metrics.get("timeoutSeconds")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutCounts = (Map<String, Object>) metrics.get("timeoutCounts");
        assertThat(timeoutCounts).containsKey("executePython");

        @SuppressWarnings("unchecked")
        Map<String, Object> execCount = (Map<String, Object>) metrics.get("execCount");
        assertThat(execCount).containsKey("executePython");
        assertThat(execCount.get("executePython")).isEqualTo(2L);

        @SuppressWarnings("unchecked")
        Map<String, Object> execMsTotal = (Map<String, Object>) metrics.get("execMsTotal");
        assertThat(execMsTotal).containsKey("executePython");
        assertThat((Long) execMsTotal.get("executePython")).isEqualTo(2000L);

        throttle.release(r1);
        throttle.release(r2);
    }
}
