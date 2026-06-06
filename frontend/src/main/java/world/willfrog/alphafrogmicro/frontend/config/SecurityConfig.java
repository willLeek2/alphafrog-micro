package world.willfrog.alphafrogmicro.frontend.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import world.willfrog.alphafrogmicro.frontend.filter.FetchAccessFilter;
import world.willfrog.alphafrogmicro.frontend.filter.JwtAuthFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final FetchAccessFilter fetchAccessFilter;

    /**
     * SSE 响应已提交后，ASYNC 派发若再触发 entry point 会 sendError 失败并刷 500 日志。
     */
    private static void committedAwareUnauthorizedEntryPoint(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception) throws java.io.IOException {
        if (response.isCommitted()) {
            log.debug("Skip unauthorized entry point: response committed, uri={}", request.getRequestURI());
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    /**
     * Agent SSE 专用链：{@code SseEmitter} 会触发 ASYNC 二次派发，且带 JWT 时不能与全局
     * {@code anyRequest().authenticated()} 混在同一链里（否则易出现 401/500）。
     * 本链对 stream 路径一律 permitAll，由 {@link world.willfrog.alphafrogmicro.frontend.controller.agent.AgentSseController}
     * 根据 Authentication 决定返回 snapshot 或 error 事件。
     */
    @Bean
    @Order(0)
    public SecurityFilterChain agentSseStreamChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/agent/runs/*/stream")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.requireExplicitSave(false))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(SecurityConfig::committedAwareUnauthorizedEntryPoint))
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")));
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/auth/register",
                                "/auth/logout",
                                "/auth/forgot-password",
                                "/auth/reset-password",
                                "/auth/verify-reset-token",
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/logout",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/verify-reset-token"
                        ).permitAll()
                        .requestMatchers("/admin/login", "/admin/create").permitAll()
                        .requestMatchers("/rag/ingest", "/rag/fetch/trigger", "/rag/upload-doc").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/auth/**").authenticated()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/tasks/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(fetchAccessFilter, JwtAuthFilter.class)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(SecurityConfig::committedAwareUnauthorizedEntryPoint))
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")));
        return http.build();
    }
}
