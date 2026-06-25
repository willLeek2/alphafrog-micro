package world.willfrog.alphafrogmicro.frontend.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserInviteCodeDao;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityManager;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceObservabilityTest {

    private static final String TEST_SECRET = "01234567890123456789012345678901";

    @Mock
    private JwtConfig jwtConfig;
    @Mock
    private UserDao userDao;
    @Mock
    private UserInviteCodeDao userInviteCodeDao;
    @SuppressWarnings("unchecked")
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthObservabilityManager authObservabilityManager;

    private AuthService authService;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        authService = new AuthService(jwtConfig, secretKey, userDao, userInviteCodeDao,
                redisTemplate, passwordEncoder, authObservabilityManager);
    }

    @Test
    void markAsLoggedIn_shouldEmitLoginStatusWriteEvent() {
        when(jwtConfig.getExpirationByMinutes()).thenReturn(30L);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(redisTemplate.hasKey("login_status:alice")).thenReturn(true);
        when(redisTemplate.getExpire("login_status:alice", TimeUnit.SECONDS)).thenReturn(1799L);

        int result = authService.markAsLoggedIn("alice");

        assertTrue(result > 0);
        verify(authObservabilityManager).emitLoginStatusWrite(
                eq("alice"), eq(true), eq(true), isNull(), eq(30L),
                eq(1799000L), eq(true), eq(1799000L), isNull());
    }

    @Test
    void markAsLoggedIn_shouldEmitEventWithErrorClassWhenRedisFails() {
        when(jwtConfig.getExpirationByMinutes()).thenReturn(30L);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(ops).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        int result = authService.markAsLoggedIn("alice");

        assertTrue(result < 0);
        verify(authObservabilityManager).emitLoginStatusWrite(
                eq("alice"), eq(true), eq(false), isNull(), eq(30L),
                isNull(), isNull(), isNull(), eq(RuntimeException.class.getName()));
    }

    @Test
    void checkIfLoggedIn_shouldEmitLoginStatusCheckEvent() {
        when(redisTemplate.hasKey("login_status:alice")).thenReturn(true);
        when(redisTemplate.getExpire("login_status:alice", TimeUnit.SECONDS)).thenReturn(1200L);

        boolean result = authService.checkIfLoggedIn("alice");

        assertTrue(result);
        verify(authObservabilityManager).emitLoginStatusCheck(
                eq("alice"), eq(true), eq(1200000L), isNull());
    }

    @Test
    void checkIfLoggedIn_shouldEmitEventWithErrorClassOnRedisException() {
        when(redisTemplate.hasKey("login_status:alice"))
                .thenThrow(new RuntimeException("Redis unavailable"));

        boolean result = authService.checkIfLoggedIn("alice");

        assertTrue(!result);
        verify(authObservabilityManager).emitLoginStatusCheck(
                eq("alice"), eq(false), isNull(), eq(RuntimeException.class.getName()));
    }

    @Test
    void generateToken_shouldCreateSignedJwt() {
        when(jwtConfig.getExpiration()).thenReturn(3600_000L);

        String token = authService.generateToken("alice");

        String subject = Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload().getSubject();
        assertTrue(subject.equals("alice"));
        Date expiration = Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload().getExpiration();
        assertTrue(expiration.after(new Date()));
    }
}
