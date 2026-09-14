package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLangchainRunMessageMapperDeploymentIdentityTest {

    @Test
    void responseCarriesPersistedDeploymentIdentity() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setDeploymentId("beta-a");
        run.setDeploymentGenerationId("gen-" + "a".repeat(64));

        AgentRunMessage message = AgentLangchainRunMessageMapper.toRunMessage(run);

        assertEquals(run.getDeploymentId(), message.getDeploymentId());
        assertEquals(run.getDeploymentGenerationId(), message.getDeploymentGenerationId());
    }
}
