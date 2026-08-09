package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

/**
 * 解析已认证 Agent API 调用方的单一入口。
 *
 * <p>所有权与管理员判断均以数据库用户记录为准，不采用认证令牌中可能过期的角色声明。
 * 控制器对每个请求只解析一次该上下文，避免角色变更导致同一响应混用普通用户与管理员视图。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAuthSupport {

    static final int ADMIN_USER_TYPE = 1127;

    private final AuthService authService;

    public AgentAuthContext resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return AgentAuthContext.unauthenticated();
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (user == null || user.getUserId() == null) {
            return AgentAuthContext.unauthenticated();
        }
        Integer userType = user.getUserType();
        return new AgentAuthContext(
                user,
                String.valueOf(user.getUserId()),
                userType != null && userType == ADMIN_USER_TYPE,
                authService.isUserActive(user)
        );
    }

    public record AgentAuthContext(User user, String userId, boolean admin, boolean active) {

        private static AgentAuthContext unauthenticated() {
            return new AgentAuthContext(null, null, false, false);
        }

        public boolean authenticated() {
            return userId != null;
        }
    }
}
