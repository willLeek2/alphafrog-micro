package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolJobAnchorServiceDeploymentIdentityTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void allRecoveryScansUseTheLocalDeploymentIdentity() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobAnchorService service = new ToolJobAnchorService(
                mapper, () -> new DeploymentIdentity("beta-test", GENERATION));

        service.listActive(100);
        service.listResumeReady(50);
        service.listStuckAtCasStatus(20);

        verify(mapper).listActiveToolJobAnchorsForDeployment("beta-test", GENERATION, 100);
        verify(mapper).listResumeReadyAnchorsForDeployment("beta-test", GENERATION, 50);
        verify(mapper).listStuckAtCasStatusAnchorsForDeployment("beta-test", GENERATION, 20);
    }
}
