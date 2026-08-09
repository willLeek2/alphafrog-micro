package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;

import java.util.List;
import java.util.Map;
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
        scheduler = new LangchainRunConcurrencyScheduler(executor, resolver, "test-app");
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

    @Test
    void maxExpansion_shouldUseEveryElasticSlotAboveCore() throws Exception {
        currentLimits.set(new LangchainRunExecutorLimits(1, 3, 1, "scheduler-test-"));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CountDownLatch fourthStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        CountDownLatch thirdRelease = new CountDownLatch(1);
        CountDownLatch fourthRelease = new CountDownLatch(1);

        submitBlocking("run-1", firstStarted, firstRelease);
        submitBlocking("run-2", secondStarted, secondRelease);
        submitBlocking("run-3", thirdStarted, thirdRelease);
        submitBlocking("run-4", fourthStarted, fourthRelease);

        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(thirdStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.runningCount()).isEqualTo(3);
        assertThat(fourthStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();

        firstRelease.countDown();
        secondRelease.countDown();
        thirdRelease.countDown();
        assertThat(fourthStarted.await(1, TimeUnit.SECONDS)).isTrue();
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

    @Test
    void schedulerSnapshot_shouldContainCoreMetrics() {
        Map<String, Object> snap = scheduler.schedulerSnapshot();
        assertThat(snap).containsKeys(
                "instanceId",
                "running", "queued", "rejectedTotal",
                "corePoolSize", "maxPoolSize", "queueCapacity",
                "hardCorePoolSize", "hardMaxPoolSize", "hardQueueCapacity",
                "oldestQueuedAgeMs");
        assertThat(((Number) snap.get("running")).intValue()).isZero();
        assertThat(((Number) snap.get("queued")).intValue()).isZero();
        assertThat(((Number) snap.get("rejectedTotal")).longValue()).isZero();
        assertThat(((Number) snap.get("corePoolSize")).intValue()).isEqualTo(2);
        assertThat(((Number) snap.get("maxPoolSize")).intValue()).isEqualTo(3);
        assertThat(((Number) snap.get("queueCapacity")).intValue()).isEqualTo(2);
    }

    @Test
    void schedulerSnapshot_instanceIdShouldBeStableAndContainApplicationComponent() {
        Map<String, Object> snap1 = scheduler.schedulerSnapshot();
        Map<String, Object> snap2 = scheduler.schedulerSnapshot();

        Object instanceId1 = snap1.get("instanceId");
        Object instanceId2 = snap2.get("instanceId");

        assertThat(instanceId1).isInstanceOf(String.class);
        assertThat((String) instanceId1).isNotBlank();
        assertThat(instanceId1).isEqualTo(instanceId2);
        assertThat((String) instanceId1).startsWith("test-app@");
    }

    @Test
    void schedulerSnapshot_shouldReflectQueuedRun() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release1 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);

        submitBlocking("run-a", started, release1);
        submitBlocking("run-b", started, release2);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch queuedRelease = new CountDownLatch(1);
        releases.add(queuedRelease);
        submitBlocking("run-c", queuedStarted, queuedRelease);

        Map<String, Object> snap = scheduler.schedulerSnapshot();
        assertThat(snap.get("running")).isEqualTo(2);
        assertThat(snap.get("queued")).isEqualTo(1);
        assertThat(((Number) snap.get("oldestQueuedAgeMs")).longValue()).isGreaterThanOrEqualTo(0);

        release1.countDown();
        release2.countDown();
        queuedRelease.countDown();
    }

    @Test
    void schedulerSnapshot_oldestQueuedAgeMsShouldResetWhenQueueDrains() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release1 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);

        submitBlocking("run-a", started, release1);
        submitBlocking("run-b", started, release2);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch queuedRelease = new CountDownLatch(1);
        releases.add(queuedRelease);
        submitBlocking("run-c", queuedStarted, queuedRelease);

        // Release all: queued run should be drained and oldestQueuedAgeMs reset
        release1.countDown();
        release2.countDown();
        queuedRelease.countDown();

        // Wait for drain
        Thread.sleep(100);
        Map<String, Object> snap = scheduler.schedulerSnapshot();
        assertThat(((Number) snap.get("queued")).intValue()).isZero();
        assertThat(((Number) snap.get("oldestQueuedAgeMs")).longValue()).isZero();
    }

    @Test
    void latestSnapshot_shouldBeUpdatedByDiagLog() {
        // Initially empty map
        Map<String, Object> initial = scheduler.latestSnapshot();
        assertThat(initial).isNotNull();

        // Trigger diagLog which updates latestSnapshot
        scheduler.diagLog();

        Map<String, Object> updated = scheduler.latestSnapshot();
        assertThat(updated).containsKeys("running", "queued", "rejectedTotal");
        // Should now have real values from the current state
        assertThat(((Number) updated.get("running")).intValue()).isZero();
    }
}
