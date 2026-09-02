package world.willfrog.beta.infra;

import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.RetireAgentDeploymentGenerationRequest;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.RetirementGateway;

@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class DubboRetirementGateway implements RetirementGateway {
    @Override
    public void retire(String address, int port, String deploymentId, String generationId, String token) {
        ReferenceConfig<AgentDubboService> reference = new ReferenceConfig<>();
        reference.setInterface(AgentDubboService.class);
        reference.setGroup("langchain");
        reference.setProtocol("tri");
        reference.setUrl("tri://" + bracketIpv6(address) + ':' + port);
        reference.setCheck(false);
        reference.setTimeout(30_000);
        try {
            AgentDubboService service = reference.get();
            service.retireDeploymentGeneration(RetireAgentDeploymentGenerationRequest.newBuilder()
                    .setDeploymentId(deploymentId)
                    .setDeploymentGenerationId(generationId)
                    .setRetirementToken(token)
                    .build());
        } catch (RuntimeException exception) {
            throw new ControllerException("AGENT_RETIREMENT_FAILED", "Agent generation retirement was not confirmed", exception);
        } finally {
            reference.destroy();
        }
    }

    private String bracketIpv6(String address) { return address.contains(":") ? '[' + address + ']' : address; }
}
