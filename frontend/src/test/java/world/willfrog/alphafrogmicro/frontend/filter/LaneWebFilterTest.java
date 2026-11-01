package world.willfrog.alphafrogmicro.frontend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;

class LaneWebFilterTest {

    @AfterEach
    void cleanContext() {
        LaneContext.clear();
        MDC.clear();
    }















    private static LaneEntryProperties properties() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setEnabled(true);
        properties.setTrafficScopeId("");
        return properties;
    }

    private static MockHttpServletRequest markedRequest(DispatcherType dispatcherType, String laneTag) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs/run-1/stream");
        request.setDispatcherType(dispatcherType);
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, laneTag);
        request.addHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER, "forged-scope");
        request.addHeader(LaneWebFilter.DEPLOYMENT_HEADER, "forged-deployment");
        request.addHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER, "gen-" + "f".repeat(64));
        request.addHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "forged-attachment");
        request.addHeader("X-Request-Id", "request-1");
        return request;
    }

    private static void assertSanitizedHeaders(HttpServletRequest request) {
        assertNull(request.getHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER));
        assertNull(request.getHeader(LaneWebFilter.DEPLOYMENT_HEADER));
        assertNull(request.getHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER));
        assertNull(request.getHeader(LaneWebFilter.LANE_TAG_HEADER));
        assertNull(request.getHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
        for (String name : new String[] {
                LaneWebFilter.TRAFFIC_SCOPE_HEADER,
                LaneWebFilter.DEPLOYMENT_HEADER,
                LaneWebFilter.DEPLOYMENT_GENERATION_HEADER,
                LaneWebFilter.LANE_TAG_HEADER,
                LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID}) {
            assertTrue(Collections.list(request.getHeaders(name)).isEmpty());
        }
        assertTrue(Collections.list(request.getHeaderNames()).stream()
                .noneMatch(name -> name.toLowerCase().contains("alphafrog")));
        assertEquals("request-1", request.getHeader("X-Request-Id"));
        assertEquals(Set.of("request-1"), Set.copyOf(Collections.list(request.getHeaders("X-Request-Id"))));
        assertTrue(Collections.list(request.getHeaderNames()).contains("X-Request-Id"));
    }

    private static final class InspectableLaneWebFilter extends LaneWebFilter {

        private InspectableLaneWebFilter(LaneEntryProperties properties) {
            super(properties);
        }

        private String alreadyFilteredAttributeName() {
            return getAlreadyFilteredAttributeName();
        }
    }

    private static void assertUntaggedAndRunsOnce(LaneWebFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        AtomicInteger downstreamCalls = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            downstreamCalls.incrementAndGet();
            assertNull(LaneContext.trafficScopeId());
            assertNull(MDC.get(LaneContext.MDC_LANE_TAG));
            assertNull(((jakarta.servlet.http.HttpServletRequest) sanitized)
                    .getHeader(LaneWebFilter.LANE_TAG_HEADER));
        });
        assertEquals(1, downstreamCalls.get());
    }
}
