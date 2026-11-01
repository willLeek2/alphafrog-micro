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








}
