package world.willfrog.beta.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.beta.config.BetaControllerProperties;

@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class ApiTokenFilter extends OncePerRequestFilter {
    private final Path tokenFile;

    public ApiTokenFilter(BetaControllerProperties properties) { this.tokenFile = properties.getApiTokenFile(); }

    @PostConstruct
    void verifyTokenAtStartup() {
        try {
            if (readToken().isEmpty()) throw new IllegalStateException("Controller API token file is missing or unsafe");
        } catch (IOException exception) {
            throw new IllegalStateException("Controller API token file cannot be read", exception);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/internal/beta/")) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        String expected = readToken();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"CONTROLLER_UNAUTHORIZED\",\"message\":\"Authentication failed\"}\n");
            return;
        }
        chain.doFilter(request, response);
    }

    private String readToken() throws IOException {
        if (!Files.isRegularFile(tokenFile) || Files.isSymbolicLink(tokenFile)) return "";
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
            if (permissions.stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                    && permission != PosixFilePermission.OWNER_WRITE)) return "";
        } catch (UnsupportedOperationException ignored) { }
        String value = Files.readString(tokenFile);
        return value.length() >= 32 && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl)
                ? value : "";
    }
}
