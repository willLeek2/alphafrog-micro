package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentDubboServiceTriple;
import world.willfrog.alphafrogmicro.agent.idl.RetireAgentDeploymentGenerationRequest;
import world.willfrog.agentlangchain.deployment.DeploymentGenerationRetirementService;

/** 人工补写退役结果时使用的隔离 RPC，只实现代际退役，不开放普通 Agent 操作。 */
@DubboService(group = "langchain")
@ConditionalOnExpression("${agent.langchain.provider.enabled:false}"
        + " && ${agent.deployment.retirement-only:false}")
@RequiredArgsConstructor
public class RetirementOnlyAgentDubboService
        extends DubboAgentDubboServiceTriple.AgentDubboServiceImplBase {

    private final DeploymentGenerationRetirementService retirementService;

    @Override
    public AgentEmpty retireDeploymentGeneration(
            RetireAgentDeploymentGenerationRequest request) {
        retirementService.retire(
                request.getDeploymentId(),
                request.getDeploymentGenerationId(),
                request.getRetirementToken());
        return AgentEmpty.getDefaultInstance();
    }
}
