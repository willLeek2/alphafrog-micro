package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAuthSupportTest {

    @Test
    void resolveReadsCurrentDatabaseRoleOnEveryRequest() {
        AuthService authService = mock(AuthService.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user");
        User admin = user(99L, 1127);
        User nonAdmin = user(99L, 1);
        when(authService.getUserByUsername("user")).thenReturn(admin, nonAdmin);
        when(authService.isUserActive(admin)).thenReturn(true);
        when(authService.isUserActive(nonAdmin)).thenReturn(true);
        AgentAuthSupport support = new AgentAuthSupport(authService);

        AgentAuthSupport.AgentAuthContext first = support.resolve(authentication);
        AgentAuthSupport.AgentAuthContext second = support.resolve(authentication);

        assertTrue(first.admin());
        assertFalse(second.admin());
        assertTrue(first.active());
        assertTrue(second.active());
    }

    private User user(long id, int type) {
        User user = new User();
        user.setUserId(id);
        user.setUserType(type);
        return user;
    }
}
