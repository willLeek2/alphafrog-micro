package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.RetireAgentDeploymentGenerationRequest;
import world.willfrog.agentlangchain.deployment.DeploymentGenerationRetirementService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetirementOnlyAgentDubboServiceTest {

    @Test
    void forwardsOnlyTheAuthenticatedGenerationRetirementRequest() {
        DeploymentGenerationRetirementService retirementService =
                mock(DeploymentGenerationRetirementService.class);
        RetirementOnlyAgentDubboService service =
                new RetirementOnlyAgentDubboService(retirementService);

        service.retireDeploymentGeneration(RetireAgentDeploymentGenerationRequest.newBuilder()
                .setDeploymentId("beta-a")
                .setDeploymentGenerationId("gen-" + "a".repeat(64))
                .setRetirementToken("secret")
                .build());

        verify(retirementService).retire(
                "beta-a", "gen-" + "a".repeat(64), "secret");
    }

    @Test
    void ordinaryAgentMethodsRemainUnimplemented() {
        RetirementOnlyAgentDubboService service =
                new RetirementOnlyAgentDubboService(mock(DeploymentGenerationRetirementService.class));

        assertThatThrownBy(() -> service.createRun(CreateAgentRunRequest.getDefaultInstance()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unimplemented");
    }
}
