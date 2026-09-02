package world.willfrog.alphafrogmicro.frontend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.AtomicLaneRoutePointer;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBinding;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBindingContext;
import world.willfrog.alphafrogmicro.common.lane.LaneCallRouter;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.common.lane.LaneEndpoint;
import world.willfrog.alphafrogmicro.common.lane.LaneExactInstanceRouter;
import world.willfrog.alphafrogmicro.common.lane.LaneRouteTable;
import world.willfrog.alphafrogmicro.common.lane.LaneRoutingSupport;
import world.willfrog.alphafrogmicro.common.lane.LaneServiceRoute;
import world.willfrog.alphafrogmicro.frontend.lane.FrontendDeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRequestContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRouteFacts;

class LaneWebFilterTest {

    private static final String PASSPHRASE = "p".repeat(32);
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String AGENT_GROUP = "langchain";

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        LaneContext.clear();
        LaneCallBindingContext.clear();
        LaneRoutingSupport.reset();
        LaneRequestContext.clear();
        MDC.clear();
    }

    @Test
    void trustedRequestUsesServerFactsAndStripsEveryExternalMarker() throws Exception {
        LaneEntryProperties properties = properties();
        LaneRouteFacts facts = facts("instance-a", 28081, 7);
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
            assertEquals("instance-a", LaneCallBindingContext.current().binding().instanceId());
            assertEquals("lane-test", MDC.get(LaneContext.MDC_LANE_TAG));
            assertEquals("beta-main-001",
                    new FrontendDeploymentIdentityProvider(properties).current().deploymentId());
        });

        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertNull(LaneRequestContext.current());
        assertNull(LaneCallBindingContext.current());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void authenticatedAllowedUserWithoutPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneWebFilter filter = filterThatMustNotReadRoute(properties, lookups);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");

        assertUntaggedAndRunsOnce(filter, properties, request);

        assertEquals(0, lookups.get());
    }

    @Test
    void authenticatedAllowedUserWithWrongPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneWebFilter filter = filterThatMustNotReadRoute(properties, lookups);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), "wrong");

        assertUntaggedAndRunsOnce(filter, properties, request);

        assertEquals(0, lookups.get());
    }

    @Test
    void authenticatedUserOutsideAllowListWithCorrectPassphraseRunsAsUntaggedTraffic() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneWebFilter filter = filterThatMustNotReadRoute(properties, lookups);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("ordinary-user", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);

        assertUntaggedAndRunsOnce(filter, properties, request);

        assertEquals(0, lookups.get());
    }

    @Test
    void exceptionStillRestoresOuterThreadContext() {
        LaneEntryProperties properties = properties();
        LaneRouteFacts facts = facts("instance-a", 28081, 7);
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
        assertNull(LaneCallBindingContext.current());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void asyncRedispatchDoesNotApplyEntryColoringTwice() throws ServletException, IOException {
        LaneEntryProperties properties = properties();
        AtomicInteger lookups = new AtomicInteger();
        LaneRouteFacts facts = facts("instance-a", 28081, 7);
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

    @Test
    void qualifiedTestRequestStopsBeforeDownstreamWhenCurrentRouteIsUnavailable() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger downstreamCalls = new AtomicInteger();
        LaneWebFilter filter = new LaneWebFilter(properties, (scope, service) -> java.util.Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (sanitized, downstream) -> downstreamCalls.incrementAndGet());

        assertEquals(503, response.getStatus());
        assertEquals(0, downstreamCalls.get());
        assertNull(LaneContext.trafficScopeId());
        assertNull(LaneRequestContext.current());
        assertNull(LaneCallBindingContext.current());
    }

    @Test
    void cutoverAfterEntryReadKeepsRpcIdentityAndExactInstanceOnTheSameSnapshot() throws Exception {
        LaneEntryProperties properties = properties();
        LaneRouteFacts entryFacts = facts("instance-a", 28081, 7);
        LaneWebFilter filter = new LaneWebFilter(
                properties, (scope, service) -> java.util.Optional.of(entryFacts));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        Invoker<Object> instanceA = invoker("10.0.0.8", 28081, "instance-a");
        Invoker<Object> instanceB = invoker("10.0.0.8", 28082, "instance-b");

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
            pointer.replaceAll(LaneRouteTable.of(List.of(route("instance-b", 28082, 8))));
            LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
            LaneExactInstanceRouter router = new LaneExactInstanceRouter(
                    URL.valueOf("dubbo://127.0.0.1/agent-langchain-service"));
            URL agentConsumerUrl = agentConsumerUrl();
            List<Invoker<Object>> selected = router.route(
                    List.of(instanceA, instanceB),
                    agentConsumerUrl,
                    realAgentInvocation(agentConsumerUrl));

            assertEquals(List.of(instanceA), selected);
            assertEquals(GENERATION, new FrontendDeploymentIdentityProvider(properties).current().generationId());
            assertEquals("instance-a", LaneCallBindingContext.current().binding().instanceId());
            assertEquals(
                    AGENT_GROUP + "/" + agentInterfaceName(),
                    LaneCallBindingContext.current().dubboServiceKey().value());
        });
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

    private static LaneWebFilter filterThatMustNotReadRoute(
            LaneEntryProperties properties, AtomicInteger lookups) {
        return new LaneWebFilter(properties, (scope, service) -> {
            lookups.incrementAndGet();
            throw new AssertionError("未同时满足已认证允许用户和正确口令时不应读取路由事实");
        });
    }

    private static void assertUntaggedAndRunsOnce(
            LaneWebFilter filter,
            LaneEntryProperties properties,
            MockHttpServletRequest request) throws ServletException, IOException {
        AtomicInteger downstreamCalls = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            downstreamCalls.incrementAndGet();
            assertNull(LaneContext.trafficScopeId());
            assertNull(LaneRequestContext.current());
            assertNull(LaneCallBindingContext.current());
            assertNull(MDC.get(LaneContext.MDC_LANE_TAG));
            assertNull(((jakarta.servlet.http.HttpServletRequest) sanitized)
                    .getHeader(properties.getPassphraseHeader()));
        });
        assertEquals(1, downstreamCalls.get());
    }

    private static LaneRouteFacts facts(String instanceId, int port, long routeVersion) {
        LaneCallBinding binding = new LaneCallBinding(
                "lane-test",
                "agent-langchain-service",
                instanceId,
                "release-a",
                GENERATION,
                routeVersion,
                new LaneEndpoint("10.0.0.8", port));
        return new LaneRouteFacts(
                "lane-test",
                "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION),
                registrationName(),
                binding,
                17);
    }

    private static LaneServiceRoute route(String instanceId, int port, long routeVersion) {
        return new LaneServiceRoute(
                "lane-test",
                "agent-langchain-service",
                registrationName(),
                instanceId,
                "release-b",
                "gen-" + "b".repeat(64),
                routeVersion,
                "2026-09-03T00:00:00Z",
                new LaneEndpoint("10.0.0.8", port));
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(String host, int port, String instanceId) {
        Invoker<Object> invoker = mock(Invoker.class);
        when(invoker.getUrl()).thenReturn(URL.valueOf(
                "dubbo://" + host + ':' + port + "/" + registrationName()
                        + "?alphafrog.instance-id=" + instanceId));
        return invoker;
    }

    @SuppressWarnings("deprecation")
    private static RpcInvocation realAgentInvocation(URL consumerUrl) {
        RpcInvocation invocation = new RpcInvocation(
                "invoke",
                consumerUrl.getServiceInterface(),
                consumerUrl.getProtocolServiceKey(),
                new Class<?>[0],
                new Object[0]);
        invocation.setTargetServiceUniqueName(consumerUrl.getServiceKey());
        assertEquals(agentInterfaceName(), invocation.getServiceName());
        assertEquals(AGENT_GROUP + "/" + agentInterfaceName(), invocation.getTargetServiceUniqueName());
        assertEquals(
                AGENT_GROUP + "/" + agentInterfaceName() + ":dubbo",
                invocation.getProtocolServiceKey());
        return invocation;
    }

    private static URL agentConsumerUrl() {
        return URL.valueOf("dubbo://127.0.0.1/" + agentInterfaceName())
                .addParameter("group", AGENT_GROUP);
    }

    private static String registrationName() {
        return agentProtocolServiceKey() + "@@providers";
    }

    private static String agentProtocolServiceKey() {
        return agentInterfaceName() + ":1.0";
    }

    private static String agentInterfaceName() {
        return "world.willfrog.alphafrogmicro.agent.idl.AgentDubboService";
    }
}
