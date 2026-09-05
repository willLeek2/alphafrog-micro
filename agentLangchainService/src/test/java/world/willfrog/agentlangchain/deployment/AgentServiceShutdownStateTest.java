package world.willfrog.agentlangchain.deployment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

class AgentServiceShutdownStateTest {

    @Test
    void deadlineClosesOnlyTheCurrentGenerationAndIsIdempotent() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        LangchainRunConcurrencyScheduler scheduler = mock(LangchainRunConcurrencyScheduler.class);
        DeploymentIdentity identity = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        when(identityProvider.current()).thenReturn(identity);
        AgentServiceShutdownState state = new AgentServiceShutdownState(
                runMapper, identityProvider, scheduler, 0, 1);
        ContextClosedEvent event = mock(ContextClosedEvent.class);

        state.onApplicationEvent(event);
        state.onApplicationEvent(event);

        verify(runMapper).failNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId(),
                "deployment_generation_shutdown_deadline_exceeded");
        verify(scheduler, times(1)).stopAcceptingNewRuns();
    }

    @Test
    void anEarlyInterruptDoesNotShortenTheNaturalProcessingWindow() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        LangchainRunConcurrencyScheduler scheduler = mock(LangchainRunConcurrencyScheduler.class);
        DeploymentIdentity identity = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        CountDownLatch observing = new CountDownLatch(1);
        when(identityProvider.current()).thenReturn(identity);
        when(runMapper.countNonTerminalRunsForDeploymentGeneration(
                identity.deploymentId(), identity.generationId())).thenAnswer(ignored -> {
            observing.countDown();
            return 1;
        });
        AgentServiceShutdownState state = new AgentServiceShutdownState(
                runMapper, identityProvider, scheduler, 1, 5);
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
}
