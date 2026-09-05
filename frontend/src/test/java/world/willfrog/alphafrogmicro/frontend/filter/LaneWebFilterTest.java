package world.willfrog.alphafrogmicro.frontend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
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
    void asyncDispatchStillHidesThePassphraseAndEveryExternalMarker() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        authenticateAllowedUser();
        MockHttpServletRequest request = markedRequest(DispatcherType.ASYNC);

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertSanitizedHeaders((HttpServletRequest) sanitized, properties);
            assertEquals("lane-test", LaneContext.trafficScopeId());
        });
    }

    @Test
    void separateErrorDispatchStillHidesThePassphraseAndEveryExternalMarker() throws Exception {
        LaneEntryProperties properties = properties();
        LaneWebFilter filter = new LaneWebFilter(properties);
        authenticateAllowedUser();
        MockHttpServletRequest request = markedRequest(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertSanitizedHeaders((HttpServletRequest) sanitized, properties);
            assertEquals("lane-test", LaneContext.trafficScopeId());
        });
    }

    @Test
    void nestedErrorDispatchRewrapsTheOriginalRequestBeforeCallingTheErrorHandler() throws Exception {
        LaneEntryProperties properties = properties();
        InspectableLaneWebFilter filter = new InspectableLaneWebFilter(properties);
        MockHttpServletRequest request = markedRequest(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(filter.alreadyFilteredAttributeName(), Boolean.TRUE);

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) ->
                assertSanitizedHeaders((HttpServletRequest) sanitized, properties));
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

    private static void authenticateAllowedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
    }

    private static MockHttpServletRequest markedRequest(DispatcherType dispatcherType) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs/run-1/stream");
        request.setDispatcherType(dispatcherType);
        request.addHeader("X-AlphaFrog-Lane-Passphrase", PASSPHRASE);
        request.addHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER, "forged-scope");
        request.addHeader(LaneWebFilter.DEPLOYMENT_HEADER, "forged-deployment");
        request.addHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER, "gen-" + "f".repeat(64));
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "forged-tag");
        request.addHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "forged-attachment");
        request.addHeader("X-Request-Id", "request-1");
        return request;
    }

    private static void assertSanitizedHeaders(HttpServletRequest request, LaneEntryProperties properties) {
        Set<String> hiddenHeaders = Set.of(
                properties.getPassphraseHeader(),
                LaneWebFilter.TRAFFIC_SCOPE_HEADER,
                LaneWebFilter.DEPLOYMENT_HEADER,
                LaneWebFilter.DEPLOYMENT_GENERATION_HEADER,
                LaneWebFilter.LANE_TAG_HEADER,
                LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID);
        for (String name : hiddenHeaders) {
            assertNull(request.getHeader(name));
            assertTrue(Collections.list(request.getHeaders(name)).isEmpty());
        }
        Set<String> visibleNames = Set.copyOf(Collections.list(request.getHeaderNames()));
        assertTrue(hiddenHeaders.stream().noneMatch(visibleNames::contains));
        assertEquals("request-1", request.getHeader("X-Request-Id"));
        assertEquals(Set.of("request-1"), Set.copyOf(Collections.list(request.getHeaders("X-Request-Id"))));
        assertTrue(visibleNames.contains("X-Request-Id"));
    }

    private static final class InspectableLaneWebFilter extends LaneWebFilter {

        private InspectableLaneWebFilter(LaneEntryProperties properties) {
            super(properties);
        }

        private String alreadyFilteredAttributeName() {
            return getAlreadyFilteredAttributeName();
        }
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
