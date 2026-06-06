package world.willfrog.alphafrogmicro.frontend.filter;

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
import world.willfrog.alphafrogmicro.frontend.util.AuthCookieHelper;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

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
        log.info("JwtAuthFilter processing: uri={}, tokenResolved={}, source={}",
                requestUri, token != null, sourceHolder[0]);
        if(token != null && validateToken(token)) {
            // Cookie 来源需要额外 Origin/Referer 校验（防 CSRF）
            if (sourceHolder[0] == TokenSource.COOKIE && !authCookieHelper.validateOrigin(request)) {
                log.warn("JWT Cookie rejected by origin check: uri={}", requestUri);
                chain.doFilter(request, response);
                return;
            }

            Authentication authentication = getAuthentication(token);
            String username = authentication.getName();
            if (!authService.checkIfLoggedIn(username)) {
                log.warn("JWT rejected because login status is missing: uri={}, username={}", requestUri, username);
                chain.doFilter(request, response);
                return;
            }
            if (!authService.isUserActive(username)) {
                log.warn("JWT rejected because account is disabled: uri={}, username={}", requestUri, username);
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
        } else if (token != null) {
            log.warn("JWT token validation failed for {}", requestUri);
        } else {
            log.info("No JWT token found in request: {}", requestUri);
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

    private boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.error("JWT token validation failed: {}", e.getClass().getSimpleName());
            return false;
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
