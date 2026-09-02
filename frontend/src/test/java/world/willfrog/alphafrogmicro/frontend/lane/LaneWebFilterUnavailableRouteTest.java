package world.willfrog.alphafrogmicro.frontend.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import world.willfrog.alphafrogmicro.common.lane.LaneCallBindingContext;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.common.lane.LaneDubboServiceKey;
import world.willfrog.alphafrogmicro.frontend.filter.LaneWebFilter;

class LaneWebFilterUnavailableRouteTest {

    private static final String PASSPHRASE = "p".repeat(32);

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        LaneContext.clear();
        LaneRequestContext.clear();
        LaneCallBindingContext.clear();
        MDC.clear();
    }

    @Test
    void unavailableQualifiedRouteRestoresEveryOuterContextValue() throws Exception {
        LaneEntryProperties properties = properties();
        AtomicInteger downstreamCalls = new AtomicInteger();
        DirectLaneRouteFactsSource source = new DirectLaneRouteFactsSource((scope, service) -> {
            throw new LaneRouteFactsUnavailableException("controller unavailable");
        });
        LaneWebFilter filter = new LaneWebFilter(properties, source);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "n/a", Collections.emptyList()));
        LaneRouteFacts outerFacts = LaneRouteFactsTestData.facts("outer-instance", 28999, 99);
        LaneContext.setTrafficScopeId("outer-scope");
        LaneRequestContext.set(outerFacts);
        LaneCallBindingContext.set(
                LaneDubboServiceKey.parse(properties.getIdentityDubboServiceKey()),
                outerFacts.callBinding());
        LaneCallBindingContext.PinnedBinding outerBinding = LaneCallBindingContext.current();
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-mdc");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(properties.getPassphraseHeader(), PASSPHRASE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (sanitized, downstream) -> downstreamCalls.incrementAndGet());

        assertEquals(503, response.getStatus());
        assertEquals(0, downstreamCalls.get());
        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertEquals(outerFacts, LaneRequestContext.current());
        assertEquals(outerBinding, LaneCallBindingContext.current());
        assertEquals("outer-mdc", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    private static LaneEntryProperties properties() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setEnabled(true);
        properties.setTestUsernames(Set.of("tester"));
        properties.setPassphrase(PASSPHRASE);
        properties.setTrafficScopeId("lane-test");
        properties.setIdentityServiceName("agent-langchain-service");
        return properties;
    }
}
