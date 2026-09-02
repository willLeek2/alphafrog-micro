package world.willfrog.alphafrogmicro.frontend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.DispatcherType;
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
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.frontend.lane.FrontendDeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRequestContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRouteFacts;

class LaneWebFilterTest {

    private static final String PASSPHRASE = "p".repeat(32);
    private static final String GENERATION = "gen-" + "a".repeat(64);

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        LaneContext.clear();
        LaneRequestContext.clear();
        MDC.clear();
    }

    @Test
    void trustedRequestUsesServerFactsAndStripsEveryExternalMarker() throws Exception {
        LaneEntryProperties properties = properties();
        LaneRouteFacts facts = new LaneRouteFacts(
                "lane-test", "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION), 7);
        LaneWebFilter filter = new LaneWebFilter(properties, (scope, service) -> java.util.Optional.of(facts));
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

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            var downstream = (jakarta.servlet.http.HttpServletRequest) sanitized;
            assertNull(downstream.getHeader(properties.getPassphraseHeader()));
            assertNull(downstream.getHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.DEPLOYMENT_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER));
            assertNull(downstream.getHeader(LaneWebFilter.LANE_TAG_HEADER));
            assertNull(downstream.getHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
            assertTrue(Collections.list(downstream.getHeaderNames()).stream()
                    .noneMatch(name -> name.toLowerCase().contains("alphafrog")));
            assertEquals("lane-test", LaneContext.trafficScopeId());
            assertEquals(facts, LaneRequestContext.current());
            assertEquals("lane-test", MDC.get(LaneContext.MDC_LANE_TAG));
            assertEquals("beta-main-001",
                    new FrontendDeploymentIdentityProvider(properties).current().deploymentId());
        });

        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertNull(LaneRequestContext.current());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void missingAuthenticationOrWrongPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneWebFilter filter = new LaneWebFilter(properties, (scope, service) -> {
            lookups.incrementAndGet();
            throw new AssertionError("未满足身份和口令时不应读取路由事实");
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), "wrong");

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertNull(LaneContext.trafficScopeId());
            assertNull(LaneRequestContext.current());
            assertNull(((jakarta.servlet.http.HttpServletRequest) sanitized)
                    .getHeader(properties.getPassphraseHeader()));
        });

        assertEquals(0, lookups.get());
    }

    @Test
    void exceptionStillRestoresOuterThreadContext() {
        LaneEntryProperties properties = properties();
        LaneRouteFacts facts = new LaneRouteFacts(
                "lane-test", "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION), 7);
        LaneWebFilter filter = new LaneWebFilter(properties, (scope, service) -> java.util.Optional.of(facts));
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
        assertNull(LaneRequestContext.current());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void asyncRedispatchDoesNotApplyEntryColoringTwice() throws ServletException, IOException {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneRouteFacts facts = new LaneRouteFacts(
                "lane-test", "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION), 7);
        LaneWebFilter filter = new LaneWebFilter(properties, (scope, service) -> {
            lookups.incrementAndGet();
            return java.util.Optional.of(facts);
        });
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest initial = new MockHttpServletRequest("GET", "/api/agent/runs/run-1/stream");
        initial.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        AtomicInteger downstreamCalls = new AtomicInteger();

        filter.doFilter(initial, new MockHttpServletResponse(), (request, response) -> downstreamCalls.incrementAndGet());
        MockHttpServletRequest async = new MockHttpServletRequest("GET", "/api/agent/runs/run-1/stream");
        async.setDispatcherType(DispatcherType.ASYNC);
        async.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        filter.doFilter(async, new MockHttpServletResponse(), (request, response) -> downstreamCalls.incrementAndGet());

        assertEquals(1, lookups.get());
        assertEquals(2, downstreamCalls.get());
    }

    private static LaneEntryProperties properties() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setEnabled(true);
        properties.setTestUsernames(Set.of("tester"));
        properties.setPassphrase(PASSPHRASE);
        properties.setTrafficScopeId("lane-test");
        properties.setIdentityServiceName("agent-langchain-service");
        properties.setLocalDeploymentId("stable");
        properties.setLocalDeploymentGenerationId("gen-" + "1".repeat(64));
        return properties;
    }
}
