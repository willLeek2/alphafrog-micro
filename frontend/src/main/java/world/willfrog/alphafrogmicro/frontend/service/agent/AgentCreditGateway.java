package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;

/**
 * Agent 积分准入与查询的前端权威边界。
 *
 * <p>创建 Run 的准入检查与 {@code /api/agent/credits} 使用同一份 langchain 响应。
 * 此处不读取本地 {@code User.credit}，因为该字段不包含服务端用量计算结果。</p>
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
