package world.willfrog.alphafrogmicro.frontend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import world.willfrog.alphafrogmicro.frontend.filter.FetchAccessFilter;
import world.willfrog.alphafrogmicro.frontend.filter.JwtAuthFilter;
import world.willfrog.alphafrogmicro.frontend.filter.LaneWebFilter;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;

class SecurityFilterChainOrderTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    SecurityFilterAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bothChainsInstallJwtLaneAndFetchExactlyOnceInThatOrder() {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null,
                    () -> String.valueOf(context.getStartupFailure()));
            CountingJwtAuthFilter jwt = context.getBean(CountingJwtAuthFilter.class);
            CountingLaneWebFilter lane = context.getBean(CountingLaneWebFilter.class);
            CountingFetchAccessFilter fetch = context.getBean(CountingFetchAccessFilter.class);
            SecurityFilterChain sse = context.getBean("agentSseStreamChain", SecurityFilterChain.class);
            SecurityFilterChain ordinary = context.getBean("filterChain", SecurityFilterChain.class);

            assertOrder((DefaultSecurityFilterChain) sse, jwt, lane, fetch);
            assertOrder((DefaultSecurityFilterChain) ordinary, jwt, lane, fetch);
        });
    }

    @Test
    void ordinaryAndSseRequestsRunAllThreeFiltersOnceAfterAuthenticationDecision() {
        runner.run(context -> {
            CountingJwtAuthFilter jwt = context.getBean(CountingJwtAuthFilter.class);
            CountingLaneWebFilter lane = context.getBean(CountingLaneWebFilter.class);
            CountingFetchAccessFilter fetch = context.getBean(CountingFetchAccessFilter.class);
            List<SecurityFilterChain> chains = List.of(
                    context.getBean("agentSseStreamChain", SecurityFilterChain.class),
                    context.getBean("filterChain", SecurityFilterChain.class));
            FilterChainProxy proxy = new FilterChainProxy(chains);

            execute(proxy, "/auth/login", false);
            execute(proxy, "/auth/login", true);
            execute(proxy, "/api/agent/runs/run-1/stream", false);
            execute(proxy, "/api/agent/runs/run-1/stream", true);

            assertEquals(4, jwt.calls);
            assertEquals(4, lane.calls);
            assertEquals(4, fetch.calls);
            assertEquals(List.of(
                    "jwt:/auth/login:false", "lane:/auth/login:false", "fetch:/auth/login:false",
                    "jwt:/auth/login:true", "lane:/auth/login:true", "fetch:/auth/login:true",
                    "jwt:/api/agent/runs/run-1/stream:false", "lane:/api/agent/runs/run-1/stream:false",
                    "fetch:/api/agent/runs/run-1/stream:false",
                    "jwt:/api/agent/runs/run-1/stream:true", "lane:/api/agent/runs/run-1/stream:true",
                    "fetch:/api/agent/runs/run-1/stream:true"), jwt.events);
        });
    }

    private static void execute(FilterChainProxy proxy, String path, boolean token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token) {
            request.addHeader("Authorization", "Bearer test");
        }
        proxy.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> { });
    }

    private static void assertOrder(
            DefaultSecurityFilterChain chain,
            JwtAuthFilter jwt,
            LaneWebFilter lane,
            FetchAccessFilter fetch) {
        List<jakarta.servlet.Filter> filters = chain.getFilters();
        int jwtIndex = filters.indexOf(jwt);
        int laneIndex = filters.indexOf(lane);
        int fetchIndex = filters.indexOf(fetch);
        assertTrue(jwtIndex >= 0 && jwtIndex < laneIndex && laneIndex < fetchIndex);
        assertEquals(1, Collections.frequency(filters, jwt));
        assertEquals(1, Collections.frequency(filters, lane));
        assertEquals(1, Collections.frequency(filters, fetch));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SecurityConfig.class)
    static class TestConfiguration {

        private final List<String> events = new ArrayList<>();

        @Bean
        CountingJwtAuthFilter jwtAuthFilter() {
            return new CountingJwtAuthFilter(events);
        }

        @Bean
        CountingLaneWebFilter laneWebFilter() {
            return new CountingLaneWebFilter(events);
        }

        @Bean
        CountingFetchAccessFilter fetchAccessFilter() {
            return new CountingFetchAccessFilter(events);
        }
    }

    static final class CountingJwtAuthFilter extends JwtAuthFilter {

        private final List<String> events;
        private int calls;

        CountingJwtAuthFilter(List<String> events) {
            super(null, null, null, null, null);
            this.events = events;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            calls++;
            boolean token = request.getHeader("Authorization") != null;
            events.add("jwt:" + request.getRequestURI() + ":" + token);
            if (token) {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "tester", "n/a", Collections.emptyList()));
            }
            chain.doFilter(request, response);
        }
    }

    static final class CountingLaneWebFilter extends LaneWebFilter {

        private final List<String> events;
        private int calls;

        CountingLaneWebFilter(List<String> events) {
            super(new LaneEntryProperties(), (scope, service) -> java.util.Optional.empty());
            this.events = events;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            calls++;
            boolean authenticated = SecurityContextHolder.getContext().getAuthentication() != null;
            events.add("lane:" + request.getRequestURI() + ":" + authenticated);
            chain.doFilter(request, response);
        }
    }

    static final class CountingFetchAccessFilter extends FetchAccessFilter {

        private final List<String> events;
        private int calls;

        CountingFetchAccessFilter(List<String> events) {
            super(null);
            this.events = events;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            calls++;
            boolean authenticated = SecurityContextHolder.getContext().getAuthentication() != null;
            events.add("fetch:" + request.getRequestURI() + ":" + authenticated);
            chain.doFilter(request, response);
        }
    }
}
