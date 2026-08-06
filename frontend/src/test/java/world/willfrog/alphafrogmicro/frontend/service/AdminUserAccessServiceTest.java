package world.willfrog.alphafrogmicro.frontend.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminUserAccessServiceTest {

    private final UserDao userDao = mock(UserDao.class);
    private final AdminUserAccessService service = new AdminUserAccessService(userDao);

    @Test
    void activeAdminIsAccepted() {
        Authentication authentication = authentication("admin");
        when(userDao.getUserByUsername("admin")).thenReturn(List.of(user(1127, "ACTIVE")));

        assertTrue(service.isActiveAdmin(authentication));
    }

    @Test
    void missingNormalOrInactiveUserIsRejected() {
        assertFalse(service.isActiveAdmin(null));

        Authentication normal = authentication("normal");
        when(userDao.getUserByUsername("normal")).thenReturn(List.of(user(1, "ACTIVE")));
        assertFalse(service.isActiveAdmin(normal));

        Authentication inactive = authentication("inactive");
        when(userDao.getUserByUsername("inactive")).thenReturn(List.of(user(1127, "DISABLED")));
        assertFalse(service.isActiveAdmin(inactive));
    }

    private static Authentication authentication(String username) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(username);
        return authentication;
    }

    private static User user(int userType, String status) {
        User user = new User();
        user.setUserType(userType);
        user.setStatus(status);
        return user;
    }
}
