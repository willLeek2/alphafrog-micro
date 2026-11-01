package world.willfrog.alphafrogmicro.frontend.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.util.AuthCookieHelper;

import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityManager;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthFilterContextSaveTest {

    private static final String TEST_SECRET = "01234567890123456789012345678901";

    private SecretKey secretKey;
    private JwtConfig jwtConfig;
    private AuthService authService;
    private AuthCookieHelper authCookieHelper;
    private AuthObservabilityManager authObservabilityManager;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        jwtConfig = new JwtConfig();
        jwtConfig.setHeader("Authorization");
        jwtConfig.setTokenPrefix("Bearer");
        authService = mock(AuthService.class);
        authCookieHelper = mock(AuthCookieHelper.class);
        authObservabilityManager = mock(AuthObservabilityManager.class);
        filter = new JwtAuthFilter(jwtConfig, secretKey, authService, authCookieHelper, authObservabilityManager);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }



    private String signToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(secretKey)
                .compact();
    }
}
