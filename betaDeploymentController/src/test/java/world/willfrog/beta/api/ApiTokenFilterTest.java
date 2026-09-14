package world.willfrog.beta.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import world.willfrog.beta.config.BetaControllerProperties;

class ApiTokenFilterTest {
    @TempDir Path temporary;

    @Test
    void failsStartupForAnUnsafeTokenAndAcceptsOnlyTheExactBearerValue() throws Exception {
        Path token = temporary.resolve("api-token");
        Files.writeString(token, "t".repeat(48));
        try { Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--")); }
        catch (UnsupportedOperationException ignored) { return; }
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setApiTokenFile(token);
        ApiTokenFilter filter = new ApiTokenFilter(properties);
        assertThrows(IllegalStateException.class, filter::verifyTokenAtStartup);

        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        filter.verifyTokenAtStartup();
        MockHttpServletRequest denied = new MockHttpServletRequest("GET", "/internal/beta/routes/main/service");
        denied.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, new MockFilterChain());
        assertEquals(401, deniedResponse.getStatus());

        MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/internal/beta/routes/main/service");
        allowed.addHeader("Authorization", "Bearer " + "t".repeat(48));
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());
        assertEquals(200, allowedResponse.getStatus());
    }

    @Test
    void runtimeTokenFailureNeverTurnsAnEmptyOrOldBearerIntoAuthorization() throws Exception {
        Path token = temporary.resolve("api-token");
        String validToken = "t".repeat(48);
        Files.writeString(token, validToken);
        try { Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { return; }
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setApiTokenFile(token);
        ApiTokenFilter filter = new ApiTokenFilter(properties);
        filter.verifyTokenAtStartup();

        Files.delete(token);
        assertUnavailable(filter, null);
        assertUnavailable(filter, validToken);

        Files.writeString(token, validToken);
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--"));
        assertUnavailable(filter, null);
        assertUnavailable(filter, validToken);

        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(token, "short");
        assertUnavailable(filter, null);
        assertUnavailable(filter, validToken);

        Files.writeString(token, validToken + "\n");
        assertUnavailable(filter, null);
        assertUnavailable(filter, validToken);
    }

    private void assertUnavailable(ApiTokenFilter filter, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/beta/status/main/service-a");
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(503, response.getStatus());
        assertNull(chain.getRequest());
    }
}
