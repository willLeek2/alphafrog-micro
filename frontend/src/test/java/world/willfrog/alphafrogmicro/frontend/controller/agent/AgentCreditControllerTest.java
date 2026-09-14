package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentAuthSupport;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCreditGateway;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCreditControllerTest {

    @Test
    void creditsReportsGatewayAuthorityInsteadOfLocalUserCredit() {
        AuthService authService = mock(AuthService.class);
        AgentCreditGateway creditGateway = mock(AgentCreditGateway.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user");
        User user = new User();
        user.setUserId(99L);
        user.setUserType(1);
        user.setCredit(BigDecimal.valueOf(999));
        when(authService.getUserByUsername("user")).thenReturn(user);
        when(creditGateway.getCredits("99")).thenReturn(GetAgentCreditsResponse.newBuilder()
                .setTotalCredits(10)
                .setRemainingCredits(0)
                .setUsedCredits(10)
                .setResetCycle("monthly")
                .build());
        AgentCreditController controller = new AgentCreditController(
                new AgentAuthSupport(authService), creditGateway);

        var response = controller.credits(authentication);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(0, response.getData().remainingCredits());
        assertEquals(10, response.getData().usedCredits());
    }
}
