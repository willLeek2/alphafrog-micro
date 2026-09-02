package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityMismatchException;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentGenerationRetirementServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void explicitMatchingSignalRetiresCurrentGenerationOnce() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = () -> new DeploymentIdentity("beta-a", GENERATION);
        DeploymentRetirementAuthorizer authorizer = mock(DeploymentRetirementAuthorizer.class);
        when(mapper.closeNonTerminalRunsForDeployment("beta-a", GENERATION)).thenReturn(3);
        DeploymentGenerationRetirementService service =
                new DeploymentGenerationRetirementService(mapper, provider, authorizer, false);

        assertThat(service.isRetired()).isFalse();
        assertThat(service.retire("beta-a", GENERATION, "secret")).isEqualTo(3);
        assertThat(service.retire("beta-a", GENERATION, "secret")).isEqualTo(3);
        assertThat(service.isRetired()).isTrue();

        verify(mapper).closeNonTerminalRunsForDeployment("beta-a", GENERATION);
        verify(authorizer, org.mockito.Mockito.times(2)).authorize("secret");
    }

    @Test
    void mismatchedSignalCannotRetireAnotherGeneration() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = () -> new DeploymentIdentity("beta-a", GENERATION);
        DeploymentRetirementAuthorizer authorizer = mock(DeploymentRetirementAuthorizer.class);
        DeploymentGenerationRetirementService service =
                new DeploymentGenerationRetirementService(mapper, provider, authorizer, false);

        assertThatThrownBy(() -> service.retire(
                "beta-a", "gen-" + "b".repeat(64), "secret"))
                .isInstanceOf(DeploymentIdentityMismatchException.class);
        assertThat(service.isRetired()).isFalse();
        verify(mapper, never()).closeNonTerminalRunsForDeployment("beta-a", GENERATION);
    }

    @Test
    void retirementWaitsForAcceptedAdmissionThenRejectsEveryLaterAdmission() throws Exception {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = () -> new DeploymentIdentity("beta-a", GENERATION);
        DeploymentRetirementAuthorizer authorizer = mock(DeploymentRetirementAuthorizer.class);
        DeploymentGenerationRetirementService service =
                new DeploymentGenerationRetirementService(mapper, provider, authorizer, false);
        CountDownLatch admissionStarted = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> admission = executor.submit(() -> service.executeWhileActive(() -> {
                admissionStarted.countDown();
                try {
                    if (!releaseAdmission.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test admission timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "accepted";
            }));
            assertThat(admissionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<Integer> retirement = executor.submit(
                    () -> service.retire("beta-a", GENERATION, "secret"));
            assertThatThrownBy(() -> retirement.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseAdmission.countDown();
            assertThat(admission.get(1, TimeUnit.SECONDS)).isEqualTo("accepted");
            assertThat(retirement.get(1, TimeUnit.SECONDS)).isZero();
            assertThatThrownBy(() -> service.executeWhileActive(() -> "too-late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("deployment_generation_inactive");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retirementOnlyProcessStartsInactiveButWaitsForAuthenticatedRpcToPersistClosure() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = () -> new DeploymentIdentity("beta-a", GENERATION);
        DeploymentRetirementAuthorizer authorizer = mock(DeploymentRetirementAuthorizer.class);
        when(mapper.closeNonTerminalRunsForDeployment("beta-a", GENERATION)).thenReturn(2);
        DeploymentGenerationRetirementService service =
                new DeploymentGenerationRetirementService(mapper, provider, authorizer, true);

        assertThat(service.isRetired()).isTrue();
        assertThatThrownBy(() -> service.executeWhileActive(() -> "forbidden"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("deployment_generation_inactive");
        verify(mapper, never()).closeNonTerminalRunsForDeployment("beta-a", GENERATION);

        assertThat(service.retire("beta-a", GENERATION, "secret")).isEqualTo(2);
        verify(authorizer).authorize("secret");
        verify(mapper).closeNonTerminalRunsForDeployment("beta-a", GENERATION);
    }
}
