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
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        jwtConfig = new JwtConfig();
        jwtConfig.setHeader("Authorization");
        jwtConfig.setTokenPrefix("Bearer");
        authService = mock(AuthService.class);
        authCookieHelper = mock(AuthCookieHelper.class);
        filter = new JwtAuthFilter(jwtConfig, secretKey, authService, authCookieHelper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSaveSecurityContextToRequestWhenJwtValidAndUserActive() throws Exception {
        String token = signToken("stress_test_1");
        when(authService.checkIfLoggedIn("stress_test_1")).thenReturn(true);
        when(authService.isUserActive("stress_test_1")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/agent/runs/run-1/stream");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        SecurityContext holderContext = SecurityContextHolder.getContext();
        assertNotNull(holderContext.getAuthentication());
        assertEquals("stress_test_1", holderContext.getAuthentication().getName());

        Object saved = request.getAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME);
        assertNotNull(saved);
        SecurityContext requestContext = (SecurityContext) saved;
        assertEquals("stress_test_1", requestContext.getAuthentication().getName());
    }

    @Test
    void shouldNotSaveSecurityContextWhenLoginStatusMissing() throws Exception {
        String token = signToken("offline_user");
        when(authService.checkIfLoggedIn("offline_user")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/agent/runs/run-1/stream");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME));
        verify(authService).checkIfLoggedIn("offline_user");
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
