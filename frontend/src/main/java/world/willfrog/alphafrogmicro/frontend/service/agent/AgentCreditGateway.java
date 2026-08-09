package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;

/**
 * Authoritative frontend boundary for Agent credit admission and reporting.
 *
 * <p>Both create-Run admission and {@code /api/agent/credits} use the same
 * langchain response. Local {@code User.credit} is deliberately not consulted
 * here because it does not include the service-side usage calculation.</p>
 */
@Service
public class AgentCreditGateway {

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboServiceLangchain;

    public GetAgentCreditsResponse getCredits(String userId) {
        return resolveService().getCredits(
                GetAgentCreditsRequest.newBuilder().setUserId(userId).build()
        );
    }

    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        return resolveService().applyCredits(request);
    }

    public boolean hasPositiveRemainingCredit(String userId) {
        return getCredits(userId).getRemainingCredits() > 0;
    }

    private AgentDubboService resolveService() {
        return agentDubboServiceLangchain;
    }
}
