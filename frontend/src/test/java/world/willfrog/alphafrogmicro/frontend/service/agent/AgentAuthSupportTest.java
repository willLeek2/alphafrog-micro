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


    private User user(long id, int type) {
        User user = new User();
        user.setUserId(id);
        user.setUserType(type);
        return user;
    }
}
