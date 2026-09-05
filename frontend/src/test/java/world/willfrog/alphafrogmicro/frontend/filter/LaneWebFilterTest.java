package world.willfrog.alphafrogmicro.frontend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;

class LaneWebFilterTest {

    private static final String PASSPHRASE = "p".repeat(32);

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        LaneContext.clear();
        MDC.clear();
    }

    @Test
    void qualifiedTestRequestOnlyAddsTheServerConfiguredTagAndStripsExternalMarkers() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        LaneContext.setTrafficScopeId("outer-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-scope");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        request.addHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER, "forged-scope");
        request.addHeader(LaneWebFilter.DEPLOYMENT_HEADER, "forged-deployment");
        request.addHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER, "gen-" + "f".repeat(64));
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "forged-tag");
        request.addHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "forged-attachment");
        request.addHeader("X-Request-Id", "request-1");
        AtomicInteger downstreamCalls = new AtomicInteger();

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            downstreamCalls.incrementAndGet();
            var downstream = (jakarta.servlet.http.HttpServletRequest) sanitized;
            assertNull(downstream.getHeader(properties.getPassphraseHeader()));
            assertNull(downstream.getHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.DEPLOYMENT_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.LANE_TAG_HEADER));
            assertNull(downstream.getHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
            assertEquals("request-1", downstream.getHeader("X-Request-Id"));
            assertTrue(Collections.list(downstream.getHeaderNames()).stream()
                    .noneMatch(name -> name.toLowerCase().contains("alphafrog")));
            assertEquals("lane-test", LaneContext.trafficScopeId());
            assertEquals("lane-test", MDC.get(LaneContext.MDC_LANE_TAG));
        });

        assertEquals(1, downstreamCalls.get());
        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void authenticatedAllowedUserWithoutPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));

        assertUntaggedAndRunsOnce(filter, properties, new MockHttpServletRequest("GET", "/api/agent/runs"));
    }

    @Test
    void authenticatedAllowedUserWithWrongPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), "wrong");

        assertUntaggedAndRunsOnce(filter, properties, request);
    }

    @Test
    void authenticatedUserOutsideAllowListWithCorrectPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "ordinary-user", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);

        assertUntaggedAndRunsOnce(filter, properties, request);
    }

    @Test
    void internalRequestRunsAsUntaggedTrafficEvenWithAnAllowedUserAndPassphrase() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/health");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);

        assertUntaggedAndRunsOnce(filter, properties, request);
    }

    @Test
    void incompleteEntryConfigurationFailsClosedToUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        properties.setTrafficScopeId(" ");
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);

        assertUntaggedAndRunsOnce(filter, properties, request);
    }

    @Test
    void exceptionStillRestoresOuterThreadContext() {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        LaneContext.setTrafficScopeId("outer-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-scope");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);

        assertThrows(ServletException.class, () -> filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (sanitized, response) -> {
                    throw new ServletException("downstream failed");
                }));

        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    private static LaneEntryProperties properties() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setEnabled(true);
        properties.setTestUsernames(Set.of("tester"));
        properties.setPassphrase(PASSPHRASE);
        properties.setTrafficScopeId("lane-test");
        return properties;
    }

    private static void assertUntaggedAndRunsOnce(
            LaneWebFilter filter,
            LaneEntryProperties properties,
            MockHttpServletRequest request) throws ServletException, IOException {
        AtomicInteger downstreamCalls = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            downstreamCalls.incrementAndGet();
            assertNull(LaneContext.trafficScopeId());
            assertNull(MDC.get(LaneContext.MDC_LANE_TAG));
            assertNull(((jakarta.servlet.http.HttpServletRequest) sanitized)
                    .getHeader(properties.getPassphraseHeader()));
        });
        assertEquals(1, downstreamCalls.get());
    }
}
