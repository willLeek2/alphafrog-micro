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
