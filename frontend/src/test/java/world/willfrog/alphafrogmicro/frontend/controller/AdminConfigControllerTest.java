package world.willfrog.alphafrogmicro.frontend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigPublishException;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.common.service.config.ConfigProfileService;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminConfigControllerTest {

    @Mock
    private ConfigProfileService configProfileService;

    @Mock
    private AuthService authService;

    private AdminConfigController adminConfigController;

    @BeforeEach
    void setUp() {
        adminConfigController = new AdminConfigController(configProfileService, authService, new ObjectMapper());
    }








    private Authentication adminAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        User user = new User();
        user.setUserId(7L);
        user.setUserType(1127);
        when(authService.getUserByUsername("admin")).thenReturn(user);
        return authentication;
    }
}
