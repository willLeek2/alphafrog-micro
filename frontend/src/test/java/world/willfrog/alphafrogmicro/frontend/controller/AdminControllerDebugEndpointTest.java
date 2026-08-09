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

    @Test
    void enable_shouldReturn403ForNonAdmin() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("normal-user");
        User user = new User();
        user.setUserType(1);
        when(userDao.getUserByUsername("normal-user")).thenReturn(java.util.List.of(user));

        ResponseEntity<?> response = controller.enableAuthObservability(authentication,
                Map.of("reason", "debug"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void enable_shouldRejectMissingReason() {
        Authentication authentication = adminAuthentication();

        ResponseEntity<?> response = controller.enableAuthObservability(authentication, Map.of());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void enable_shouldReturn409OnConflict() {
        Authentication authentication = adminAuthentication();
        AuthObservabilitySession existing = session("existing");
        when(authObservabilityManager.enable(any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(AuthObservabilityManager.EnableResult.conflict(existing));

        ResponseEntity<?> response = controller.enableAuthObservability(authentication,
                Map.of("reason", "debug"));

        assertEquals(409, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("existing", body.get("existingSessionId"));
    }

    @Test
    void enable_shouldReturn400WhenManagerRejectsEmptyScope() {
        Authentication authentication = adminAuthentication();
        when(authObservabilityManager.enable(any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(AuthObservabilityManager.EnableResult.error(
                        "Scope is empty or equivalent to all users; set forceAllUsers=true to enable."));

        ResponseEntity<?> response = controller.enableAuthObservability(authentication,
                Map.of("reason", "debug"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void enable_shouldReturn200WithSessionDetails() {
        Authentication authentication = adminAuthentication();
        AuthObservabilitySession session = session("abc12345");
        when(authObservabilityManager.enable(any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(AuthObservabilityManager.EnableResult.success(session));

        ResponseEntity<?> response = controller.enableAuthObservability(authentication,
                Map.of("reason", "debug", "scope", Map.of("sampleUsers", List.of("alice"))));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("abc12345", body.get("debugSessionId"));
        assertEquals("/tmp/abc12345", body.get("outputDir"));
    }

    @Test
    void disable_shouldReturn403ForNonAdmin() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("normal-user");
        User user = new User();
        user.setUserType(1);
        when(userDao.getUserByUsername("normal-user")).thenReturn(java.util.List.of(user));

        ResponseEntity<?> response = controller.disableAuthObservability(authentication, Map.of());

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void disable_shouldReturnDisabledFlag() {
        Authentication authentication = adminAuthentication();
        when(authObservabilityManager.disable("", "ADMIN_DISABLED")).thenReturn(true);

        ResponseEntity<?> response = controller.disableAuthObservability(authentication, Map.of());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("disabled"));
    }

    @Test
    void status_shouldReturn403ForNonAdmin() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("normal-user");
        User user = new User();
        user.setUserType(1);
        when(userDao.getUserByUsername("normal-user")).thenReturn(java.util.List.of(user));

        ResponseEntity<?> response = controller.authObservabilityStatus(authentication);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void status_shouldReturnManagerStatus() {
        Authentication authentication = adminAuthentication();
        when(authObservabilityManager.buildStatus()).thenReturn(Map.of("active", false));

        ResponseEntity<?> response = controller.authObservabilityStatus(authentication);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("active"));
    }

    @Test
    void getAgentRun_shouldScrubAdminSnapshotAndNeverEchoMalformedJson() {
        Authentication authentication = adminAuthentication();
        when(adminService.getAgentRun(any(GetAdminAgentRunRequest.class))).thenReturn(
                GetAdminAgentRunResponse.newBuilder()
                        .setSuccess(true)
                        .setRun(AdminAgentRun.newBuilder().setRunId("run-1").build())
                        .setPlanJson("{broken raw-plan-secret")
                        .setSnapshotJson("{\"observability\":{\"items\":[{\"httpRequest\":{\"Authorization\":\"Bearer admin-secret-value\"}}],\"Cookie\":\"sid=admin-cookie-secret\"}}")
                        .setLastError("api_key=admin-error-secret")
                        .build());

        ResponseEntity<?> response = controller.getAgentRun(authentication, "run-1");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(null, body.get("planJson"));
        String combined = String.valueOf(body.get("snapshotJson")) + body.get("lastError");
        assertFalse(combined.contains("admin-secret-value"));
        assertFalse(combined.contains("admin-error-secret"));
        assertFalse(combined.contains("admin-cookie-secret"));
        assertTrue(combined.contains("REDACTED"));
    }

    @Test
    void getAgentRunRouteRemainsCompatible() throws Exception {
        GetMapping mapping = AdminController.class
                .getDeclaredMethod("getAgentRun", Authentication.class, String.class)
                .getAnnotation(GetMapping.class);

        assertNotNull(mapping);
        assertTrue(java.util.Arrays.asList(mapping.value()).contains("/agent-runs/{runId}"));
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
