package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DebugAdminGuard {

    private final String adminToken;

    public DebugAdminGuard(@Value("${alphafrog.debug.admin-token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    public void requireAdmin(String authorization, String adminTokenHeader) {
        if (adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Debug admin token is not configured");
        }
        String bearerToken = resolveBearerToken(authorization);
        if (adminToken.equals(bearerToken) || adminToken.equals(adminTokenHeader)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin token is invalid");
    }

    private String resolveBearerToken(String authorization) {
        if (authorization == null) {
            return "";
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return "";
    }
}
