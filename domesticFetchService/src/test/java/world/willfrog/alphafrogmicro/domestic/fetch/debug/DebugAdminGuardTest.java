package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebugAdminGuardTest {

    @Test
    void requireAdminShouldReturnServiceUnavailableWhenTokenIsBlank() {
        DebugAdminGuard guard = new DebugAdminGuard(" ");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> guard.requireAdmin("Bearer secret", null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void requireAdminShouldAcceptBearerTokenAndHeaderToken() {
        DebugAdminGuard guard = new DebugAdminGuard("secret");

        assertDoesNotThrow(() -> guard.requireAdmin("Bearer secret", null));
        assertDoesNotThrow(() -> guard.requireAdmin(null, "secret"));
    }

    @Test
    void requireAdminShouldRejectMissingOrWrongToken() {
        DebugAdminGuard guard = new DebugAdminGuard("secret");

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> guard.requireAdmin(null, null));
        ResponseStatusException wrong = assertThrows(ResponseStatusException.class,
                () -> guard.requireAdmin("Bearer wrong", "wrong"));

        assertEquals(HttpStatus.FORBIDDEN, missing.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, wrong.getStatusCode());
    }
}
