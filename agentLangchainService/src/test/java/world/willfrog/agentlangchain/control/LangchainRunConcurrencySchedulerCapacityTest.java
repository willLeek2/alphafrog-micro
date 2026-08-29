package world.willfrog.agentlangchain.control;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;
import world.willfrog.agentlangchain.control.scheduler.DefaultRunAdmissionPolicy;
import world.willfrog.agentlangchain.control.scheduler.DefaultRunPriorityPolicy;
import world.willfrog.agentlangchain.control.scheduler.InProcessRunCapacityLedger;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agentlangchain.control.scheduler.RunPriority;
import world.willfrog.agentlangchain.control.scheduler.RunPriorityPolicy;
import world.willfrog.agentlangchain.control.scheduler.RunWeightPolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 容量加权准入、队列满拒绝、FIFO 无饥饿与排队取消的端到端行为。
 * 每个 Run 的权重由测试策略统一给定（reserve 阶段尚无 runId）。
 */
class LangchainRunConcurrencySchedulerCapacityTest {

    private final List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> releases = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> started = new CopyOnWriteArrayList<>();

    private TestScheduler build(LangchainRunExecutorLimits limits,
                                RunWeightPolicy weightPolicy,
                                int maxCapacityUnits,
                                SimpleMeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(limits.getMaxPoolSize());
        executor.setMaxPoolSize(limits.getMaxPoolSize());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("capacity-test-");
        executor.initialize();
        executors.add(executor);

        LangchainRunExecutorLimitsResolver resolver = mock(LangchainRunExecutorLimitsResolver.class);
        when(resolver.currentLimits()).thenReturn(limits);
        when(resolver.hardLimits()).thenReturn(limits);

        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(maxCapacityUnits);
        LangchainSchedulerMetrics metrics = new LangchainSchedulerMetrics(registry);
        LangchainRunConcurrencyScheduler scheduler = new LangchainRunConcurrencyScheduler(
                executor,
                resolver,
                new DefaultRunAdmissionPolicy(),
                new DefaultRunPriorityPolicy(),
                weightPolicy,
                ledger,
                metrics,
                "capacity-test-app",
                false);
        return new TestScheduler(scheduler, ledger);
    }

    record TestScheduler(LangchainRunConcurrencyScheduler scheduler,
                         InProcessRunCapacityLedger ledger) {
    }

    private LangchainRunConcurrencyScheduler.Reservation submitBlocking(
            LangchainRunConcurrencyScheduler scheduler, String runId) {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        started.add(start);
        releases.add(release);
        LangchainRunConcurrencyScheduler.Reservation reservation = scheduler.reserve();
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        scheduler.submit(reservation, run, () -> {
            start.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return reservation;
    }

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void heavyWeightsNeverExceedCapacityEvenWithFreeCoreSlots() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // core=2 有空位，但容量 4 只能容纳一个权重 3 的 Run。
        TestScheduler ts = build(new LangchainRunExecutorLimits(2, 2, 2, "c"),
                runId -> RunWeightPolicy.HEAVY_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();

        submitBlocking(scheduler, "run-2");
        // 3 + 3 > 4：第二个 Run 只能排队，容量账本绝不超卖。
        assertThat(scheduler.runningCount()).isEqualTo(1);
        assertThat(scheduler.queuedCount()).isEqualTo(1);
        assertThat(ts.ledger().usedUnits()).isEqualTo(3);
        assertThat(started.get(1).await(200, TimeUnit.MILLISECONDS)).isFalse();

        // 第一个 Run 结束后容量归还，第二个才能提升。
        releases.get(0).countDown();
        assertThat(started.get(1).await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.runningCount()).isEqualTo(1);
        assertThat(scheduler.queuedCount()).isZero();
        assertThat(ts.ledger().usedUnits()).isEqualTo(3);
    }

    @Test
    void queueFullRejectsWithQueueFullReasonAndMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // core=1、max=1、queue=1：第三个 Run 无处可去。
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 1, 1, "c"),
                runId -> RunWeightPolicy.STANDARD_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();
        submitBlocking(scheduler, "run-2");
        assertThat(scheduler.queuedCount()).isEqualTo(1);

        assertThatThrownBy(() -> submitBlocking(scheduler, "run-3"))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(((LangchainRunRejectedException) e).getReason())
                        .isEqualTo("queue_full"));

        assertThat(scheduler.queuedCount()).isEqualTo(1);
        assertThat(registry.counter("alphafrog.scheduler.rejected.total", "reason", "queue_full")
                .count()).isEqualTo(1.0);
    }

    @Test
    void headOfQueueCapacityBlockRejectsNewRequestWithCapacityFull() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 队首权重 3 放不下（3+3>4），弹性入口必须拒绝而不是跳队。
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 2, 1, "c"),
                runId -> RunWeightPolicy.HEAVY_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();
        submitBlocking(scheduler, "run-2");
        assertThat(scheduler.queuedCount()).isEqualTo(1);

        assertThatThrownBy(() -> submitBlocking(scheduler, "run-3"))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(((LangchainRunRejectedException) e).getReason())
                        .isEqualTo("capacity_full"));

        assertThat(registry.counter("alphafrog.scheduler.rejected.total", "reason", "capacity_full")
                .count()).isEqualTo(1.0);
    }

    @Test
    void queuedRunsExecuteInFifoOrderWithoutStarvation() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 1, 4, "c"),
                runId -> RunWeightPolicy.STANDARD_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        List<String> executionOrder = new CopyOnWriteArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String runId = "run-" + i;
            CountDownLatch release = new CountDownLatch(1);
            releases.add(release);
            LangchainRunConcurrencyScheduler.Reservation reservation = scheduler.reserve();
            AgentRun run = new AgentRun();
            run.setId(runId);
            run.setUserId("user-1");
            scheduler.submit(reservation, run, () -> {
                executionOrder.add(runId);
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertThat(scheduler.queuedCount()).isEqualTo(2);
        // 依序释放，验证排队 Run 严格按提交顺序执行（先来先服务，无饥饿）。
        releases.get(0).countDown();
        releases.get(1).countDown();
        releases.get(2).countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executionOrder.size() < 3 && System.nanoTime() < deadline) {
            // 单元测试没有 Spring 定时器：显式触发 drain（生产由 @Scheduled drain 驱动）。
            scheduler.drain();
            Thread.sleep(20);
        }
        assertThat(executionOrder).containsExactly("run-1", "run-2", "run-3");
    }

    @Test
    void cancelQueuedRemovesRunReclaimsSlotAndCountsMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 1, 2, "c"),
                runId -> RunWeightPolicy.STANDARD_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();
        submitBlocking(scheduler, "run-2");
        assertThat(scheduler.queuedCount()).isEqualTo(1);

        // 排队中的 Run 可被精确取消；运行中的 Run 与未知 Run 返回 false。
        assertThat(scheduler.cancelQueued("run-2")).isTrue();
        assertThat(scheduler.queuedCount()).isZero();
        assertThat(scheduler.cancelQueued("run-2")).isFalse();
        assertThat(scheduler.cancelQueued("run-1")).isFalse();
        assertThat(registry.counter("alphafrog.scheduler.cancelled.total", "stage", "queued")
                .count()).isEqualTo(1.0);

        // 取消的 Run 永远不会执行；队列名额已归还，新 Run 可以立即运行。
        releases.get(0).countDown();
        assertThat(started.get(1).await(300, TimeUnit.MILLISECONDS)).isFalse();

        submitBlocking(scheduler, "run-3");
        assertThat(started.get(2).await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.queuedCount()).isZero();
    }

    @Test
    void promotionRejectedBySaturatedPoolIsDeferredNotLost() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // core==max==1：上一个 Run 的 finally 里提升队首必然被物理池暂时拒绝。
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 1, 4, "c"),
                runId -> RunWeightPolicy.STANDARD_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();
        submitBlocking(scheduler, "run-2");
        assertThat(scheduler.queuedCount()).isEqualTo(1);

        releases.get(0).countDown();
        // 等待 run-1 完全退出：其 finally 内的提升会被拒绝并放回队首。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((scheduler.runningCount() > 0 || scheduler.queuedCount() != 1)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        // 旧实现在这里会把队首 Run 直接丢失；新实现必须保留在队列中等待 drain 重试。
        assertThat(scheduler.runningCount()).isZero();
        assertThat(scheduler.queuedCount()).isEqualTo(1);

        // 生产由 @Scheduled drain 周期重试；测试显式触发。
        scheduler.drain();
        assertThat(started.get(1).await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.queuedCount()).isZero();
    }

    @Test
    void overweightRunRejectedImmediatelyOnIdleScheduler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 单 Run 权重 5 > 容量 4：即使调度器完全空闲也必须立即拒绝，不能排队堵死队首。
        TestScheduler ts = build(new LangchainRunExecutorLimits(2, 3, 2, "c"),
                runId -> 5, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        assertThatThrownBy(() -> submitBlocking(scheduler, "run-overweight"))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(((LangchainRunRejectedException) e).getReason())
                        .isEqualTo("capacity_full"));

        assertThat(scheduler.runningCount()).isZero();
        assertThat(scheduler.queuedCount()).isZero();
        assertThat(registry.counter("alphafrog.scheduler.rejected.total", "reason", "capacity_full")
                .count()).isEqualTo(1.0);
    }

    @Test
    void rejectedReservationDoesNotLeakCapacity() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestScheduler ts = build(new LangchainRunExecutorLimits(1, 1, 0, "c"),
                runId -> RunWeightPolicy.STANDARD_WEIGHT, 4, registry);
        LangchainRunConcurrencyScheduler scheduler = ts.scheduler();

        submitBlocking(scheduler, "run-1");
        assertThat(started.get(0).await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> submitBlocking(scheduler, "run-2"))
                .isInstanceOf(LangchainRunRejectedException.class);

        assertThat(ts.ledger().usedUnits()).isEqualTo(1);
        assertThat(scheduler.runningCount()).isEqualTo(1);
        assertThat(scheduler.queuedCount()).isZero();
    }
}
