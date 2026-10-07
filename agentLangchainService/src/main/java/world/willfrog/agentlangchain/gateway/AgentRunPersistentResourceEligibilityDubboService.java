package world.willfrog.agentlangchain.gateway;

import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityRequest;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityResponse;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentRunPersistentResourceEligibilityServiceTriple;

/** 沙箱网关回查专用入口；与面向用户的 Run 读取隔离。 */
@DubboService(group = "langchain")
@ConditionalOnExpression("${agent.langchain.provider.enabled:false}")
@RequiredArgsConstructor
public class AgentRunPersistentResourceEligibilityDubboService
        extends DubboAgentRunPersistentResourceEligibilityServiceTriple
        .AgentRunPersistentResourceEligibilityServiceImplBase {

    private final RunPersistentResourceEligibilityService eligibilityService;

    @Override
    public CheckRunPersistentResourceEligibilityResponse checkEligibility(
            CheckRunPersistentResourceEligibilityRequest request) {
        return eligibilityService.check(request);
    }
}
