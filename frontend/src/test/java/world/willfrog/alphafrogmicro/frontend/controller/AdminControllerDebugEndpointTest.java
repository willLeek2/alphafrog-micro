package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import world.willfrog.alphafrogmicro.admin.idl.AdminAgentRun;
import world.willfrog.alphafrogmicro.admin.idl.AdminService;
import world.willfrog.alphafrogmicro.admin.idl.GetAdminAgentRunRequest;
import world.willfrog.alphafrogmicro.admin.idl.GetAdminAgentRunResponse;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityManager;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityScope;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilitySession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerDebugEndpointTest {

    @Mock
    private AuthService authService;
    @Mock
    private RateLimitingService rateLimitingService;
    @Mock
    private UserDao userDao;
    @Mock
    private AuthObservabilityManager authObservabilityManager;
    @Mock
    private AdminService adminService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(authService, rateLimitingService, userDao, authObservabilityManager);
        ReflectionTestUtils.setField(controller, "adminService", adminService);
    }












    private Authentication adminAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin-user");
        User user = new User();
        user.setUserType(1127);
        when(userDao.getUserByUsername("admin-user")).thenReturn(java.util.List.of(user));
        return authentication;
    }

    private AuthObservabilitySession session(String id) {
        return new AuthObservabilitySession(
                id, System.currentTimeMillis(),
                System.currentTimeMillis() + 3600_000L,
                "/tmp/" + id, "operator", "reason", new AuthObservabilityScope());
    }
}
