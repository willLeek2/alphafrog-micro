package world.willfrog.alphafrogmicro.frontend.filter;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityManager;
import world.willfrog.alphafrogmicro.frontend.util.AuthCookieHelper;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;
    private final AuthService authService;
    private final AuthCookieHelper authCookieHelper;
    private final AuthObservabilityManager authObservabilityManager;

    /**
     * Token 来源枚举，用于区分鉴权方式（Cookie 来源需要额外 Origin/Referer 校验）。
     */
    private enum TokenSource {
        HEADER, URL_PARAM, COOKIE
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TokenSource[] sourceHolder = new TokenSource[1];
        String token = resolveToken(request, sourceHolder);
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        log.info("JwtAuthFilter processing: uri={}, tokenResolved={}, source={}",
                requestUri, token != null, sourceHolder[0]);

        if (token != null) {
            TokenValidationResult validation = validateTokenWithDetail(token);
            if (validation.valid) {
                Authentication authentication = getAuthentication(token);
                String username = authentication.getName();

                if (sourceHolder[0] == TokenSource.COOKIE && !authCookieHelper.validateOrigin(request)) {
                    log.warn("JWT Cookie rejected by origin check: uri={}", requestUri);
                    emitRejected(requestUri, method, username, true,
                            sourceName(sourceHolder[0]), token, true, null,
                            "COOKIE_ORIGIN_CHECK_FAILED", null, null, null);
                    chain.doFilter(request, response);
                    return;
                }

                if (!authService.checkIfLoggedIn(username)) {
                    log.warn("JWT rejected because login status is missing: uri={}, username={}", requestUri, username);
                    emitRejected(requestUri, method, username, true,
                            sourceName(sourceHolder[0]), token, true, null,
                            "LOGIN_STATUS_MISSING", false, null, null);
                    chain.doFilter(request, response);
                    return;
                }
                if (!authService.isUserActive(username)) {
                    log.warn("JWT rejected because account is disabled: uri={}, username={}", requestUri, username);
                    emitRejected(requestUri, method, username, true,
                            sourceName(sourceHolder[0]), token, true, null,
                            "ACCOUNT_DISABLED", null, null, null);
                    chain.doFilter(request, response);
                    return;
                }
                // ASYNC 派发（SseEmitter 等）需将 SecurityContext 写入 request attribute，否则二次鉴权丢失登录态
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
                log.info("JWT authentication successful for {}: principal={}, authenticated={}, source={}",
                        requestUri, authentication.getName(), authentication.isAuthenticated(), sourceHolder[0]);
            } else {
                log.warn("JWT token validation failed for {}", requestUri);
                emitRejected(requestUri, method, null, true,
                        sourceName(sourceHolder[0]), token, false, validation.expDeltaMs,
                        validation.rejectReason, null, null, null);
            }
        } else {
            log.info("No JWT token found in request: {}", requestUri);
            emitRejected(requestUri, method, null, false,
                    null, null, false, null,
                    "NO_TOKEN", null, null, null);
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request, TokenSource[] sourceHolder) {
        // 1. 优先从 Header 解析
        String bearerToken = request.getHeader(jwtConfig.getHeader());
        log.debug("resolveToken: headerValue={}", bearerToken);
        if(StringUtils.hasText(bearerToken)) {
            String prefix = jwtConfig.getTokenPrefix();
            // 支持 "Bearer token" 和 "Bearertoken" 两种格式
            if(bearerToken.startsWith(prefix + " ")) {
                sourceHolder[0] = TokenSource.HEADER;
                return bearerToken.substring(prefix.length() + 1).trim();
            } else if(bearerToken.startsWith(prefix)) {
                sourceHolder[0] = TokenSource.HEADER;
                return bearerToken.substring(prefix.length()).trim();
            }
        }

        // 2. 从 URL 参数解析（用于 artifact 下载等场景）
        String tokenParam = request.getParameter("token");
        if(StringUtils.hasText(tokenParam)) {
            log.info("resolveToken: resolved from URL parameter");
            sourceHolder[0] = TokenSource.URL_PARAM;
            return tokenParam.trim();
        }

        // 3. Cookie fallback（用于 SSE 等无法自定义 header 的场景）
        String cookieToken = authCookieHelper.readCookie(request);
        if (StringUtils.hasText(cookieToken)) {
            log.debug("resolveToken: resolved from Cookie");
            sourceHolder[0] = TokenSource.COOKIE;
            return cookieToken;
        }

        log.info("resolveToken: no valid token found");
        return null;
    }

    private TokenValidationResult validateTokenWithDetail(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return TokenValidationResult.valid();
        } catch (ExpiredJwtException e) {
            long expDeltaMs = e.getClaims().getExpiration() != null
                    ? e.getClaims().getExpiration().getTime() - System.currentTimeMillis()
                    : null;
            return TokenValidationResult.invalid("TOKEN_EXPIRED", expDeltaMs);
        } catch (Exception e) {
            return TokenValidationResult.invalid(e.getClass().getSimpleName(), null);
        }
    }

    private void emitRejected(String path,
                              String method,
                              String username,
                              boolean authHeaderPresent,
                              String tokenSource,
                              String token,
                              boolean jwtValid,
                              Long jwtExpDeltaMs,
                              String rejectReason,
                              Boolean loginStatusKeyExists,
                              Long loginStatusTtlMs,
                              String redisErrorClass) {
        try {
            authObservabilityManager.emitAuthContextRejected(
                    UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    path, method, username, authHeaderPresent, tokenSource,
                    AuthObservabilityManager.hashToken(token),
                    jwtValid, jwtExpDeltaMs, rejectReason,
                    loginStatusKeyExists, loginStatusTtlMs, redisErrorClass);
        } catch (Exception e) {
            log.warn("Failed to emit auth observability rejected event", e);
        }
    }

    private String sourceName(TokenSource source) {
        return source == null ? null : source.name();
    }

    private static final class TokenValidationResult {
        final boolean valid;
        final String rejectReason;
        final Long expDeltaMs;

        TokenValidationResult(boolean valid, String rejectReason, Long expDeltaMs) {
            this.valid = valid;
            this.rejectReason = rejectReason;
            this.expDeltaMs = expDeltaMs;
        }

        static TokenValidationResult valid() {
            return new TokenValidationResult(true, null, null);
        }

        static TokenValidationResult invalid(String rejectReason, Long expDeltaMs) {
            return new TokenValidationResult(false, rejectReason, expDeltaMs);
        }
    }

    private Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String principal = claims.getSubject();
        // 使用三个参数的构造函数，明确标记为已认证
        List<SimpleGrantedAuthority> authorities = Collections.emptyList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
