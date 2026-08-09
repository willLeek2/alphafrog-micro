package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

/**
 * Single source for resolving the authenticated Agent API caller.
 *
 * <p>The database user record, rather than a stale role claim carried by the
 * authentication token, is authoritative for both ownership and admin
 * decisions. A controller resolves this context once per request so a role
 * change cannot produce a mixed user/admin view inside one response.</p>
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
