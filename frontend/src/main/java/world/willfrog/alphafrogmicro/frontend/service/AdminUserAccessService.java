package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

/** 校验当前 JWT 主体是否为启用状态的管理员用户。 */
@Service
@RequiredArgsConstructor
public class AdminUserAccessService {

    private static final int ADMIN_USER_TYPE = 1127;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserDao userDao;

    public boolean isActiveAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        List<User> users = userDao.getUserByUsername(authentication.getName());
        if (users == null || users.isEmpty()) {
            return false;
        }
        User user = users.get(0);
        Integer userType = user.getUserType();
        if (userType == null || userType != ADMIN_USER_TYPE) {
            return false;
        }
        String status = user.getStatus();
        return status != null && STATUS_ACTIVE.equalsIgnoreCase(status.trim());
    }
}
