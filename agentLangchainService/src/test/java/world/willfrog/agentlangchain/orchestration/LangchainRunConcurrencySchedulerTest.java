package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;

import java.util.List;
import java.util.concurrent.CountDownLatch;
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
        scheduler = new LangchainRunConcurrencyScheduler(executor, resolver);
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

    @Test
    void shrinkCore_shouldNotStartQueuedRunsUntilRunningDropsBelowCurrentCore() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        CountDownLatch queuedStarted = new CountDownLatch(1);

        submitBlocking("run-1", firstStarted, firstRelease);
        submitBlocking("run-2", secondStarted, secondRelease);
        CountDownLatch queuedRelease = new CountDownLatch(1);
        releases.add(queuedRelease);
        submitBlocking("run-3", queuedStarted, queuedRelease);

        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(queuedStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();

        currentLimits.set(new LangchainRunExecutorLimits(1, 3, 2, "scheduler-test-"));
        CountDownLatch queuedAfterShrinkStarted = new CountDownLatch(1);
        CountDownLatch queuedAfterShrinkRelease = new CountDownLatch(1);
        releases.add(queuedAfterShrinkRelease);
        submitBlocking("run-4", queuedAfterShrinkStarted, queuedAfterShrinkRelease);

        firstRelease.countDown();
        assertThat(queuedStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queuedAfterShrinkStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();

        secondRelease.countDown();
        assertThat(queuedStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(queuedAfterShrinkStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void maxExpansion_shouldStartOldestQueuedRunBeforeAcceptingNewQueuedRun() throws Exception {
        currentLimits.set(new LangchainRunExecutorLimits(1, 2, 1, "scheduler-test-"));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);

        submitBlocking("run-1", firstStarted, firstRelease);
        submitBlocking("run-2", secondStarted, secondRelease);

        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(secondStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();

        CountDownLatch thirdRelease = new CountDownLatch(1);
        releases.add(thirdRelease);
        submitBlocking("run-3", thirdStarted, thirdRelease);

        assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(thirdStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();

        firstRelease.countDown();
        assertThat(thirdStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();

        secondRelease.countDown();
        assertThat(thirdStarted.await(1, TimeUnit.SECONDS)).isTrue();
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
