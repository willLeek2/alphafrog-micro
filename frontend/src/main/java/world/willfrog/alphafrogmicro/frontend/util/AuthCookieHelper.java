package world.willfrog.alphafrogmicro.frontend.util;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import world.willfrog.alphafrogmicro.frontend.config.CookieConfig;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 鉴权 Cookie 工具类。
 * <p>
 * 职责：
 * <ul>
 *   <li>写入/读取/清除 HttpOnly Cookie；</li>
 *   <li>对 Cookie 鉴权做 Origin/Referer 校验（防 CSRF）。</li>
 * </ul>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>使用 {@code Set-Cookie} header 手动拼装，而非 {@link Cookie} API，
 *       因为 Jakarta Servlet Cookie API 不支持 SameSite 属性；</li>
 *   <li>Cookie 值与 Authorization Bearer token 格式一致（同一个 JWT），
 *       前端可同时使用两种鉴权方式（双轨并行）；</li>
 *   <li>Token 值使用 URL 编码写入/读取，防止特殊字符破坏 header 格式；</li>
 *   <li>Origin/Referer 校验复用 {@code cors.allowed-origins} 配置，与 CORS 策略保持一致，
 *       避免生产环境因 Host 不匹配（反代 / 内网 IP）误拦合法请求。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthCookieHelper {

    private final CookieConfig cookieConfig;

    @Value("${cors.allowed-origins:*}")
    private String allowedOriginsConfig;

    /** 解析后的 CORS allowlist；若含 {@code *} 则为 {@code null}（表示全放行）。 */
    private List<String> allowedOrigins;

    /** allowlist 中是否包含通配符 {@code *}。 */
    private boolean allowAllOrigins;

    @PostConstruct
    void init() {
        List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (origins.isEmpty() || origins.contains("*")) {
            allowAllOrigins = true;
            allowedOrigins = null;
        } else {
            allowAllOrigins = false;
            allowedOrigins = origins;
        }
    }

    /**
     * 写入 HttpOnly Cookie。
     *
     * @param response HTTP 响应
     * @param token    JWT token（与 Authorization Bearer 同一个 token）
     */
    public void setCookie(HttpServletResponse response, String token) {
        if (!cookieConfig.isEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(cookieConfig.getName()).append("=")
          .append(URLEncoder.encode(token, StandardCharsets.UTF_8));
        sb.append("; Path=").append(cookieConfig.getPath());
        sb.append("; Max-Age=").append(cookieConfig.getMaxAgeSeconds());
        sb.append("; HttpOnly");

        if (cookieConfig.isSecure()) {
            sb.append("; Secure");
        }

        if (StringUtils.hasText(cookieConfig.getSameSite())) {
            sb.append("; SameSite=").append(cookieConfig.getSameSite());
        }

        response.addHeader("Set-Cookie", sb.toString());
        log.debug("Set auth cookie: name={}, path={}, maxAge={}s, secure={}, sameSite={}",
                cookieConfig.getName(), cookieConfig.getPath(),
                cookieConfig.getMaxAgeSeconds(), cookieConfig.isSecure(),
                cookieConfig.getSameSite());
    }

    /**
     * 清除 Cookie（Max-Age=0）。
     *
     * @param response HTTP 响应
     */
    public void clearCookie(HttpServletResponse response) {
        if (!cookieConfig.isEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(cookieConfig.getName()).append("=");
        sb.append("; Path=").append(cookieConfig.getPath());
        sb.append("; Max-Age=0");
        sb.append("; HttpOnly");

        if (cookieConfig.isSecure()) {
            sb.append("; Secure");
        }

        if (StringUtils.hasText(cookieConfig.getSameSite())) {
            sb.append("; SameSite=").append(cookieConfig.getSameSite());
        }

        response.addHeader("Set-Cookie", sb.toString());
        log.debug("Cleared auth cookie: name={}", cookieConfig.getName());
    }

    /**
     * 从请求中读取 Cookie 值。
     *
     * @param request HTTP 请求
     * @return Cookie 值（JWT token），如果不存在则返回 null
     */
    public String readCookie(HttpServletRequest request) {
        if (!cookieConfig.isEnabled()) {
            return null;
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        String cookieName = cookieConfig.getName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (StringUtils.hasText(value)) {
                    return URLDecoder.decode(value.trim(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /**
     * 校验请求的 Origin/Referer 是否在 CORS allowlist 内（防 CSRF）。
     * <p>
     * 仅对 Cookie 鉴权生效，Authorization header 鉴权不需要此校验。
     * <p>
     * 校验逻辑：
     * <ol>
     *   <li>若 {@code cors.allowed-origins} 为 {@code *}（开发环境默认），直接放行；</li>
     *   <li>优先检查 {@code Origin} header，提取 scheme://host[:port] 与 allowlist 精确匹配；</li>
     *   <li>如果 Origin 不存在，回退检查 {@code Referer} header 的 origin 部分；</li>
     *   <li>Origin 和 Referer 都不存在时允许（非浏览器客户端，如 EventSource）。</li>
     * </ol>
     *
     * @param request HTTP 请求
     * @return true 如果校验通过或校验未启用
     */
    public boolean validateOrigin(HttpServletRequest request) {
        if (!cookieConfig.isOriginCheckEnabled()) {
            return true;
        }

        // cors.allowed-origins=* 时全放行（与 CorsConfig 保持一致）
        if (allowAllOrigins) {
            return true;
        }

        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        // 优先检查 Origin（浏览器对跨站请求必带）
        if (StringUtils.hasText(origin)) {
            if (isOriginAllowed(origin)) {
                return true;
            }
            log.warn("Cookie origin check rejected: Origin={} not in allowlist, uri={}",
                    origin, request.getRequestURI());
            return false;
        }

        // 回退检查 Referer 的 origin 部分
        if (StringUtils.hasText(referer)) {
            String refererOrigin = extractOrigin(referer);
            if (refererOrigin != null && isOriginAllowed(refererOrigin)) {
                return true;
            }
            log.warn("Cookie origin check rejected: Referer={} origin not in allowlist, uri={}",
                    referer, request.getRequestURI());
            return false;
        }

        // Origin 和 Referer 都不存在（非浏览器客户端，如 EventSource）
        log.debug("Cookie origin check: no Origin/Referer header, uri={}, allowing", request.getRequestURI());
        return true;
    }

    /**
     * 判断 origin（{@code scheme://host[:port]}）是否在 allowlist 中。
     * <p>
     * 匹配规则：
     * <ul>
     *   <li>精确匹配（大小写不敏感）；</li>
     *   <li>若 allowlist 条目包含通配符 {@code *}，转为正则匹配（支持 {@code https://*.example.com} 等）。</li>
     * </ul>
     */
    private boolean isOriginAllowed(String origin) {
        if (allowedOrigins == null) {
            return true;
        }
        String normalized = origin.toLowerCase();
        for (String allowed : allowedOrigins) {
            String allowedLower = allowed.toLowerCase();
            if (allowedLower.contains("*")) {
                // 通配符模式：将 * 转为正则 .*，其他字符转义
                String regex = Pattern.quote(allowedLower).replace("\\*", "\\E.*\\Q");
                if (normalized.matches(regex)) {
                    return true;
                }
            } else if (allowedLower.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 URL 中提取 origin 部分（{@code scheme://host[:port]}）。
     * <p>
     * 例如：{@code https://example.com/path?q=1} → {@code https://example.com}
     */
    private String extractOrigin(String url) {
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) {
                return null;
            }
            int pathStart = url.indexOf('/', schemeEnd + 3);
            return pathStart > 0 ? url.substring(0, pathStart) : url;
        } catch (Exception e) {
            log.warn("Failed to extract origin from URL: {}, error={}", url, e.getMessage());
            return null;
        }
    }
}
