package world.willfrog.alphafrogmicro.frontend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import world.willfrog.alphafrogmicro.frontend.filter.FetchAccessFilter;
import world.willfrog.alphafrogmicro.frontend.filter.JwtAuthFilter;
import world.willfrog.alphafrogmicro.frontend.filter.LaneWebFilter;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;

/**
 * 钉主链授权行为：未登录的 /admin/create、/admin/login 必须穿透授权层进到控制器
 * （第一个管理员的唯一空库入口），其余 /admin/** 未登录仍由 entry point 回 401，
 * ERROR 派发放行（sendError 之后的 /error 派发不能再次吃掉响应）。
 */
class AdminPublicEndpointsAuthorizationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    SecurityFilterAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);




    private static FilterChainProxy proxy(org.springframework.boot.test.context.assertj.AssertableWebApplicationContext context) {
        List<SecurityFilterChain> chains = List.of(
                context.getBean("agentSseStreamChain", SecurityFilterChain.class),
                context.getBean("filterChain", SecurityFilterChain.class));
        return new FilterChainProxy(chains);
    }

    private static MockHttpServletRequest post(String path) {
        return request("POST", path);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        // 真容器把 servlet 映射在 "/" 时 servletPath 就是完整路径；MockHttpServletRequest
        // 不会从 requestURI 自动填，AntPathRequestMatcher 按 servletPath+pathInfo 取路径
        request.setServletPath(path);
        return request;
    }

    private static Outcome execute(FilterChainProxy proxy, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedEnd = new AtomicBoolean(false);
        proxy.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedEnd.set(true));
        return new Outcome(reachedEnd.get(), response.getStatus());
    }

    private record Outcome(boolean reachedEnd, int status) {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SecurityConfig.class)
    static class TestConfiguration {

        @Bean
        JwtAuthFilter jwtAuthFilter() {
            return new NoopJwtAuthFilter();
        }

        @Bean
        LaneWebFilter laneWebFilter() {
            return new NoopLaneWebFilter();
        }

        @Bean
        FetchAccessFilter fetchAccessFilter() {
            return new NoopFetchAccessFilter();
        }
    }

    static final class NoopJwtAuthFilter extends JwtAuthFilter {

        NoopJwtAuthFilter() {
            super(null, null, null, null, null);
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }

    static final class NoopLaneWebFilter extends LaneWebFilter {

        NoopLaneWebFilter() {
            super(new LaneEntryProperties());
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }

    static final class NoopFetchAccessFilter extends FetchAccessFilter {

        NoopFetchAccessFilter() {
            super(null);
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }
}
