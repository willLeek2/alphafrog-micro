package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsResponse;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentAuthSupport;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCreditGateway;

@RestController
@Slf4j
public class AgentCreditController {

    private final AgentAuthSupport authSupport;
    private final AgentCreditGateway creditGateway;

    public AgentCreditController(AgentAuthSupport authSupport, AgentCreditGateway creditGateway) {
        this.authSupport = authSupport;
        this.creditGateway = creditGateway;
    }

    @GetMapping("/api/agent/credits")
    public ResponseWrapper<AgentCreditsResponse> credits(Authentication authentication) {
        return creditsInternal(authentication);
    }

    @PostMapping("/api/agent/credits/apply")
    public ResponseWrapper<AgentCreditsApplyResponse> applyCredits(Authentication authentication,
                                                                   @RequestBody(required = false) AgentCreditsApplyRequest request) {
        return applyCreditsInternal(authentication, request);
    }

    private ResponseWrapper<AgentCreditsResponse> creditsInternal(Authentication authentication) {
        AgentAuthSupport.AgentAuthContext caller = authSupport.resolve(authentication);
        if (!caller.authenticated()) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = creditGateway.getCredits(caller.userId());
            return ResponseWrapper.success(new AgentCreditsResponse(
                    resp.getTotalCredits(),
                    resp.getRemainingCredits(),
                    resp.getUsedCredits(),
                    resp.getResetCycle(),
                    resp.getNextResetAt()
            ));
        } catch (RpcException e) {
            log.error("查询 credit 失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询 credit 失败");
        } catch (Exception e) {
            log.error("查询 credit 失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询 credit 失败");
        }
    }

    private ResponseWrapper<AgentCreditsApplyResponse> applyCreditsInternal(Authentication authentication,
                                                                            AgentCreditsApplyRequest request) {
        AgentAuthSupport.AgentAuthContext caller = authSupport.resolve(authentication);
        if (!caller.authenticated()) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        int amount = request == null || request.amount() == null ? 0 : request.amount();
        if (amount <= 0) {
            return ResponseWrapper.paramError("amount 必须大于 0");
        }
        String reason = request == null || request.reason() == null ? "" : request.reason();
        String contact = request == null || request.contact() == null ? "" : request.contact();
        try {
            var resp = creditGateway.applyCredits(
                    ApplyAgentCreditsRequest.newBuilder()
                            .setUserId(caller.userId())
                            .setAmount(amount)
                            .setReason(reason)
                            .setContact(contact)
                            .build()
            );
            return ResponseWrapper.success(new AgentCreditsApplyResponse(
                    resp.getApplicationId(),
                    resp.getTotalCredits(),
                    resp.getRemainingCredits(),
                    resp.getUsedCredits(),
                    resp.getStatus(),
                    resp.getAppliedAt()
            ));
        } catch (RpcException e) {
            log.error("申请额度失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "申请额度失败");
        } catch (Exception e) {
            log.error("申请额度失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "申请额度失败");
        }
    }

}
