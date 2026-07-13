package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.debug.DomesticMarketSampleClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMarketSampleControllerTest {

    @Mock
    private UserDao userDao;
    @Mock
    private DomesticMarketSampleClient marketSampleClient;

    private AdminMarketSampleController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminMarketSampleController(userDao, marketSampleClient);
    }

    @Test
    void adminAccountCanFetchRandomIndexNames() {
        Authentication authentication = authentication("admin");
        User user = user(1127, "ACTIVE");
        when(userDao.getUserByUsername("admin")).thenReturn(List.of(user));
        when(marketSampleClient.randomIndexNamesByAmount(
                "20250101", "20251231", 100000, 2))
                .thenReturn(List.of(Map.of("tsCode", "000300.SH", "name", "沪深300")));

        ResponseEntity<?> response = controller.randomIndexNamesByAmount(
                authentication, "20250101", "20251231", 100000, 2);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(Map.of("tsCode", "000300.SH", "name", "沪深300")),
                response.getBody());
    }

    @Test
    void normalAccountIsRejectedBeforeCallingUpstream() {
        Authentication authentication = authentication("normal");
        when(userDao.getUserByUsername("normal")).thenReturn(List.of(user(1, "ACTIVE")));

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 2);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(2);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeCallingUpstream() {
        ResponseEntity<?> response = controller.randomSwL3Industries(null, 2);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(2);
    }

    @Test
    void disabledAdminAccountIsRejected() {
        Authentication authentication = authentication("disabled-admin");
        when(userDao.getUserByUsername("disabled-admin"))
                .thenReturn(List.of(user(1127, "DISABLED")));

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void adminAccountWithoutExplicitStatusIsRejected() {
        Authentication authentication = authentication("statusless-admin");
        when(userDao.getUserByUsername("statusless-admin"))
                .thenReturn(List.of(user(1127, null)));

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(1);
    }

    @Test
    void adminAccountWithBlankStatusIsRejected() {
        Authentication authentication = authentication("blank-status-admin");
        when(userDao.getUserByUsername("blank-status-admin"))
                .thenReturn(List.of(user(1127, "  ")));

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(1);
    }

    private Authentication authentication(String username) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(username);
        return authentication;
    }

    private User user(int userType, String status) {
        User user = new User();
        user.setUserType(userType);
        user.setStatus(status);
        return user;
    }
}
