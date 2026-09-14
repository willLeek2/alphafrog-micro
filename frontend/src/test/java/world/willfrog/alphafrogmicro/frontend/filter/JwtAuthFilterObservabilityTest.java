package world.willfrog.alphafrogmicro.frontend.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityManager;
import world.willfrog.alphafrogmicro.frontend.util.AuthCookieHelper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterObservabilityTest {

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

    @Test
    void shouldEmitNoTokenEventWhenTokenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/foo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        verify(authObservabilityManager).emitAuthContextRejected(
                anyString(), eq("/api/foo"), eq("GET"), isNull(), eq(false),
                isNull(), isNull(), eq(false), isNull(),
                eq("NO_TOKEN"), isNull(), isNull(), isNull());
    }

    @Test
    void shouldEmitExpiredEventForExpiredToken() throws Exception {
        String token = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3600_000L))
                .signWith(secretKey)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/foo");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        verify(authObservabilityManager).emitAuthContextRejected(
                anyString(), eq("/api/foo"), eq("GET"), isNull(), eq(true),
                eq("HEADER"), anyString(), eq(false),
                argThat(delta -> delta != null && delta < 0),
                eq("TOKEN_EXPIRED"), isNull(), isNull(), isNull());
    }

    @Test
    void shouldEmitLoginStatusMissingEvent() throws Exception {
        String token = signToken("offline_user");
        when(authService.checkIfLoggedIn("offline_user")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/foo");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        verify(authObservabilityManager).emitAuthContextRejected(
                anyString(), eq("/api/foo"), eq("GET"), eq("offline_user"),
                eq(true), eq("HEADER"), anyString(), eq(true), isNull(),
                eq("LOGIN_STATUS_MISSING"), eq(false), isNull(), isNull());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldEmitAccountDisabledEvent() throws Exception {
        String token = signToken("disabled_user");
        when(authService.checkIfLoggedIn("disabled_user")).thenReturn(true);
        when(authService.isUserActive("disabled_user")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/foo");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        verify(authObservabilityManager).emitAuthContextRejected(
                anyString(), eq("/api/foo"), eq("GET"), eq("disabled_user"),
                eq(true), eq("HEADER"), anyString(), eq(true), isNull(),
                eq("ACCOUNT_DISABLED"), isNull(), isNull(), isNull());
    }

    @Test
    void shouldNotEmitEventWhenAuthenticationSucceeds() throws Exception {
        String token = signToken("active_user");
        when(authService.checkIfLoggedIn("active_user")).thenReturn(true);
        when(authService.isUserActive("active_user")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/agent/runs/run-1/stream");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        verify(authObservabilityManager, never()).emitAuthContextRejected(
                anyString(), anyString(), anyString(), any(), anyBoolean(),
                any(), any(), anyBoolean(), any(), any(), any(), any(), any());
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
