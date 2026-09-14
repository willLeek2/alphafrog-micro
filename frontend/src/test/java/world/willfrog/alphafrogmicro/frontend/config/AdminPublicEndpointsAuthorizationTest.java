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

    @Test
    void unauthenticatedAdminCreateAndLoginPassOrdinaryChainAuthorization() throws Exception {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null,
                    () -> String.valueOf(context.getStartupFailure()));

            Outcome create = execute(proxy(context), post("/admin/create"));
            assertTrue(create.reachedEnd(), "POST /admin/create should reach controller, got " + create.status());
            assertEquals(200, create.status());

            Outcome login = execute(proxy(context), post("/admin/login"));
            assertTrue(login.reachedEnd(), "POST /admin/login should reach controller, got " + login.status());
            assertEquals(200, login.status());

            Outcome authLogin = execute(proxy(context), post("/api/auth/login"));
            assertTrue(authLogin.reachedEnd(), "POST /api/auth/login should still reach controller");
        });
    }

    @Test
    void unauthenticatedOtherAdminEndpointsStillRejectedWith401() throws Exception {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null,
                    () -> String.valueOf(context.getStartupFailure()));

            Outcome overall = execute(proxy(context), request("GET", "/admin/overall"));
            assertFalse(overall.reachedEnd(), "GET /admin/overall must not reach controller unauthenticated");
            assertEquals(401, overall.status());

            Outcome inviteCodes = execute(proxy(context), post("/admin/invite-codes"));
            assertFalse(inviteCodes.reachedEnd(), "POST /admin/invite-codes must not reach controller unauthenticated");
            assertEquals(401, inviteCodes.status());
        });
    }

    @Test
    void errorDispatchIsPermittedOnOrdinaryChain() throws Exception {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null,
                    () -> String.valueOf(context.getStartupFailure()));

            MockHttpServletRequest request = request("GET", "/error");
            request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
            Outcome outcome = execute(proxy(context), request);
            assertTrue(outcome.reachedEnd(), "ERROR dispatch to /error should be permitted, got " + outcome.status());
        });
    }

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
