package world.willfrog.agentlangchain.deployment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agentlangchain.config.LangchainRunAsyncConfig;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

class AgentServiceShutdownStateTest {

    @AfterEach
    void resetDubboShutdownDeadline() {
        org.apache.dubbo.common.config.ConfigurationUtils.setExpectedShutdownTime(Long.MAX_VALUE);
    }

    @Test
    void deadlineClosesOnlyTheCurrentGenerationAndIsIdempotent() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        LangchainRunConcurrencyScheduler scheduler = mock(LangchainRunConcurrencyScheduler.class);
        ThreadPoolTaskExecutor runExecutor = mock(ThreadPoolTaskExecutor.class);
        DeploymentIdentity identity = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        when(identityProvider.current()).thenReturn(identity);
        AgentServiceShutdownState state = new AgentServiceShutdownState(
                runMapper, identityProvider, scheduler, runExecutor, 0, 1);
        ContextClosedEvent event = mock(ContextClosedEvent.class);

        state.onApplicationEvent(event);
        state.onApplicationEvent(event);

        verify(runMapper).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");
        verify(scheduler, times(1)).stopAcceptingNewRuns();
        verify(runExecutor, times(1)).shutdown();
    }

    @Test
    void anEarlyInterruptDoesNotShortenTheNaturalProcessingWindow() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        LangchainRunConcurrencyScheduler scheduler = mock(LangchainRunConcurrencyScheduler.class);
        ThreadPoolTaskExecutor runExecutor = mock(ThreadPoolTaskExecutor.class);
        DeploymentIdentity identity = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        CountDownLatch observing = new CountDownLatch(1);
        when(identityProvider.current()).thenReturn(identity);
        when(runMapper.countNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId())).thenAnswer(ignored -> {
            observing.countDown();
            return 1;
        });
        AgentServiceShutdownState state = new AgentServiceShutdownState(
                runMapper, identityProvider, scheduler, runExecutor, 1, 5);
        Thread shutdown = new Thread(() -> state.onApplicationEvent(mock(ContextClosedEvent.class)));

        shutdown.start();
        org.assertj.core.api.Assertions.assertThat(observing.await(500, TimeUnit.MILLISECONDS)).isTrue();
        shutdown.interrupt();
        Thread.sleep(100);

        verify(runMapper, never()).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");
        shutdown.join(2000);
        org.assertj.core.api.Assertions.assertThat(shutdown.isAlive()).isFalse();
        verify(runMapper).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");
    }

    @Test
    void realSpringCloseUsesOneAbsoluteDeadlineInsteadOfAddingExecutorAndLifecycleWaits() throws Exception {
        assertRealContextClosesWithinOneDeadline(Duration.ofMillis(900), Duration.ofMillis(300));
    }

    @Test
    void realSpringCloseAlsoHonorsANonDefaultDeadline() throws Exception {
        assertRealContextClosesWithinOneDeadline(Duration.ofMillis(1400), Duration.ofMillis(500));
    }

    private void assertRealContextClosesWithinOneDeadline(Duration totalTimeout,
                                                           Duration finalizationMargin) throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        LangchainRunConcurrencyScheduler scheduler = mock(LangchainRunConcurrencyScheduler.class);
        DeploymentIdentity identity = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        when(identityProvider.current()).thenReturn(identity);
        when(runMapper.countNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId())).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(taskInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            return 1;
        }).when(runMapper).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");

        LangchainRunExecutorLimitsResolver limitsResolver = mock(LangchainRunExecutorLimitsResolver.class);
        when(limitsResolver.hardLimits()).thenReturn(
                new LangchainRunExecutorLimits(1, 1, 0, "shutdown-deadline-test-"));
        ThreadPoolTaskExecutor runExecutor = new LangchainRunAsyncConfig()
                .agentLangchainRunTaskExecutor(60, limitsResolver);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("agentLangchainRunTaskExecutor", ThreadPoolTaskExecutor.class, () -> runExecutor);
        context.registerBean("agentServiceShutdownState", AgentServiceShutdownState.class,
                () -> new AgentServiceShutdownState(runMapper, identityProvider, scheduler, runExecutor,
                        totalTimeout, finalizationMargin));
        context.registerBean("dubboLikeShutdownListener", DubboLikeShutdownListener.class,
                DubboLikeShutdownListener::new);
        context.registerBean("slowLifecycle", SlowLifecycle.class, SlowLifecycle::new);
        context.refresh();
        runExecutor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException expected) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        org.assertj.core.api.Assertions.assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        context.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        org.assertj.core.api.Assertions.assertThat(elapsedMillis)
                .isGreaterThanOrEqualTo(totalTimeout.minus(finalizationMargin).toMillis() - 100)
                .isLessThanOrEqualTo(totalTimeout.toMillis() + 350);
        org.assertj.core.api.Assertions.assertThat(taskInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(runMapper).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");
    }

    private static final class DubboLikeShutdownListener
            implements ApplicationListener<ContextClosedEvent>, Ordered {
        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            try {
                Thread.sleep(org.apache.dubbo.common.config.ConfigurationUtils.reCalShutdownTime(10_000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }
    }

    private static final class SlowLifecycle implements SmartLifecycle {
        private final AtomicBoolean running = new AtomicBoolean();

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public void stop(Runnable callback) {
            Thread delayedStop = new Thread(() -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.set(false);
                    callback.run();
                }
            }, "slow-lifecycle-test");
            delayedStop.setDaemon(true);
            delayedStop.start();
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }
    }
}
