package world.willfrog.agentlangchain.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;
import world.willfrog.agentlangchain.control.scheduler.DefaultRunAdmissionPolicy;
import world.willfrog.agentlangchain.control.scheduler.DefaultRunPriorityPolicy;
import world.willfrog.agentlangchain.control.scheduler.DefaultRunWeightPolicy;
import world.willfrog.agentlangchain.control.scheduler.InProcessRunCapacityLedger;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainRunConcurrencySchedulerTest {

    private ThreadPoolTaskExecutor executor;
    private AtomicReference<LangchainRunExecutorLimits> currentLimits;
    private LangchainRunConcurrencyScheduler scheduler;
    private final List<CountDownLatch> releases = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("scheduler-test-");
        executor.initialize();

        currentLimits = new AtomicReference<>(new LangchainRunExecutorLimits(2, 3, 2, "scheduler-test-"));
        LangchainRunExecutorLimitsResolver resolver = mock(LangchainRunExecutorLimitsResolver.class);
        when(resolver.currentLimits()).thenAnswer(invocation -> currentLimits.get());
        when(resolver.hardLimits()).thenReturn(new LangchainRunExecutorLimits(3, 3, 2, "scheduler-test-"));
        // 默认权重 1 × 最大并发 3 < 容量 4：容量维度不改变旧有伸缩语义的断言。
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);
        scheduler = new LangchainRunConcurrencyScheduler(
                executor,
                resolver,
                new DefaultRunAdmissionPolicy(),
                new DefaultRunPriorityPolicy(),
                new DefaultRunWeightPolicy(),
                ledger,
                new LangchainSchedulerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                "test-app",
                true);
    }

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while ((scheduler.runningCount() > 0 || scheduler.queuedCount() > 0) && System.nanoTime() < deadline) {
            releases.forEach(CountDownLatch::countDown);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        executor.shutdown();
    }






    private void submitBlocking(String runId, CountDownLatch started, CountDownLatch release) {
        releases.add(release);
        LangchainRunConcurrencyScheduler.Reservation reservation = scheduler.reserve();
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        scheduler.submit(reservation, run, () -> {
            started.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }





}
