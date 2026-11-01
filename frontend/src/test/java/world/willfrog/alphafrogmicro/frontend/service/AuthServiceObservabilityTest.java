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







}
