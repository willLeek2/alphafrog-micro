package world.willfrog.alphafrogmicro.frontend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cookie 鉴权配置。
 * <p>
 * 用于支持 HttpOnly Cookie 鉴权（SSE 等场景），同时保持与 Authorization header 的兼容。
 * <p>
 * 配置项示例（application.yml）：
 * <pre>
 * auth:
 *   cookie:
 *     enabled: true
 *     name: access_token
 *     secure: false  # 生产环境建议 true（HTTPS）
 *     same-site: Lax
 *     path: /
 *     max-age-seconds: 86400  # 24 小时
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "auth.cookie")
@Data
public class CookieConfig {

    /**
     * 是否启用 Cookie 鉴权。默认 true。
     */
    private boolean enabled = true;

    /**
     * Cookie 名称。默认 "access_token"。
     */
    private String name = "access_token";

    /**
     * 是否设置 Secure 标志（仅 HTTPS 传输）。
     * <p>
     * 本地开发（HTTP）应设为 false；生产环境（HTTPS）应设为 true。
     */
    private boolean secure = false;

    /**
     * SameSite 属性。默认 "Lax"。
     * <p>
     * 可选值：Strict, Lax, None。
     * <p>
     * SSE 场景建议 Lax 或 None（配合 Secure=true）。
     */
    private String sameSite = "Lax";

    /**
     * Cookie Path。默认 "/"（全站有效）。
     */
    private String path = "/";

    /**
     * Cookie 最大存活时间（秒）。默认 86400（24 小时）。
     * <p>
     * 设为 -1 表示会话级 Cookie（浏览器关闭即失效）。
     */
    private int maxAgeSeconds = 86400;

    /**
     * 是否对 Cookie 鉴权启用 Origin/Referer 校验。
     * <p>
     * 仅对 Cookie fallback 生效，不影响 Authorization header 鉴权。
     * <p>
     * 默认 true，防止 CSRF 攻击。
     */
    private boolean originCheckEnabled = true;
}
