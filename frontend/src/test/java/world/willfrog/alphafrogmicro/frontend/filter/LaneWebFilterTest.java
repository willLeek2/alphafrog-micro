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

    @Test
    void validHeaderTagMarksTheRequestAndKeepsEveryMarkerInvisibleDownstream() throws Exception {
        LaneWebFilter filter = new LaneWebFilter(properties());
        LaneContext.setTrafficScopeId("outer-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-scope");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "lane-a");
        request.addHeader(LaneWebFilter.TRAFFIC_SCOPE_HEADER, "forged-scope");
        request.addHeader(LaneWebFilter.DEPLOYMENT_HEADER, "forged-deployment");
        request.addHeader(LaneWebFilter.DEPLOYMENT_GENERATION_HEADER, "gen-" + "f".repeat(64));
        request.addHeader(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "forged-attachment");
        request.addHeader("X-Request-Id", "request-1");
        AtomicInteger downstreamCalls = new AtomicInteger();

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            downstreamCalls.incrementAndGet();
            var downstream = (jakarta.servlet.http.HttpServletRequest) sanitized;
            assertSanitizedHeaders(downstream);
            assertEquals("lane-a", LaneContext.trafficScopeId());
            assertEquals("lane-a", MDC.get(LaneContext.MDC_LANE_TAG));
        });

        assertEquals(1, downstreamCalls.get());
        assertEquals("outer-scope", LaneContext.trafficScopeId());
        assertEquals("outer-scope", MDC.get(LaneContext.MDC_LANE_TAG));
    }

    @Test
    void surroundingWhitespaceInTheHeaderTagIsTrimmedBeforeTagging() throws Exception {
        LaneWebFilter filter = new LaneWebFilter(properties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "  lane-a  ");

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response)
                -> assertEquals("lane-a", LaneContext.trafficScopeId()));
    }

    @Test
    void requestWithoutALaneTagHeaderRunsAsUntaggedTraffic() throws Exception {
        assertUntaggedAndRunsOnce(new LaneWebFilter(properties()),
                new MockHttpServletRequest("GET", "/api/agent/runs"));
    }

    @Test
    void blankLaneTagHeaderRunsAsUntaggedTraffic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "   ");

        assertUntaggedAndRunsOnce(new LaneWebFilter(properties()), request);
    }

    @Test
    void mainBetaHeaderTagRunsAsUntaggedTraffic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "main-beta");

        assertUntaggedAndRunsOnce(new LaneWebFilter(properties()), request);
    }

    @Test
    void invalidHeaderTagRunsAsUntaggedTrafficAndWarnsOnlyOnce() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LaneWebFilter.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            LaneWebFilter filter = new LaneWebFilter(properties());
            MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/agent/runs");
            first.addHeader(LaneWebFilter.LANE_TAG_HEADER, "Bad Tag!");
            MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/agent/runs");
            second.addHeader(LaneWebFilter.LANE_TAG_HEADER, "Also-Bad-!");

            assertUntaggedAndRunsOnce(filter, first);
            assertUntaggedAndRunsOnce(filter, second);

            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("入口泳道请求头格式不合法"))
                    .count());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void injectedTrafficScopeIdWinsOverTheRequestHeader() throws Exception {
        LaneEntryProperties properties = properties();
        properties.setTrafficScopeId("lane-injected");
        LaneWebFilter filter = new LaneWebFilter(properties);
        MockHttpServletRequest request = markedRequest(DispatcherType.REQUEST, "lane-from-header");

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertSanitizedHeaders((HttpServletRequest) sanitized);
            assertEquals("lane-injected", LaneContext.trafficScopeId());
            assertEquals("lane-injected", MDC.get(LaneContext.MDC_LANE_TAG));
        });
    }

    @Test
    void injectedTrafficScopeIdTagsTrafficWithoutAnyHeader() throws Exception {
        LaneEntryProperties properties = properties();
        properties.setTrafficScopeId("lane-injected");
        LaneWebFilter filter = new LaneWebFilter(properties);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/agent/runs"),
                new MockHttpServletResponse(), (sanitized, response)
                        -> assertEquals("lane-injected", LaneContext.trafficScopeId()));
    }

    @Test
    void disabledEntryNeverTagsEvenWithAValidHeader() throws Exception {
        LaneEntryProperties properties = properties();
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "lane-a");

        assertUntaggedAndRunsOnce(new LaneWebFilter(properties), request);
    }

    @Test
    void internalPathRunsAsUntaggedTrafficEvenWithAValidHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/health");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "lane-a");

        assertUntaggedAndRunsOnce(new LaneWebFilter(properties()), request);
    }

    @Test
    void asyncDispatchStillStripsEveryExternalMarkerAndKeepsTheTag() throws Exception {
        LaneWebFilter filter = new LaneWebFilter(properties());
        MockHttpServletRequest request = markedRequest(DispatcherType.ASYNC, "lane-a");

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertSanitizedHeaders((HttpServletRequest) sanitized);
            assertEquals("lane-a", LaneContext.trafficScopeId());
        });
    }

    @Test
    void separateErrorDispatchStillStripsEveryExternalMarkerAndKeepsTheTag() throws Exception {
        LaneWebFilter filter = new LaneWebFilter(properties());
        MockHttpServletRequest request = markedRequest(DispatcherType.ERROR, "lane-a");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) -> {
            assertSanitizedHeaders((HttpServletRequest) sanitized);
            assertEquals("lane-a", LaneContext.trafficScopeId());
        });
    }

    @Test
    void nestedErrorDispatchRewrapsTheOriginalRequestBeforeCallingTheErrorHandler() throws Exception {
        InspectableLaneWebFilter filter = new InspectableLaneWebFilter(properties());
        MockHttpServletRequest request = markedRequest(DispatcherType.ERROR, "lane-a");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(filter.alreadyFilteredAttributeName(), Boolean.TRUE);

        filter.doFilter(request, new MockHttpServletResponse(), (sanitized, response) ->
                assertSanitizedHeaders((HttpServletRequest) sanitized));
    }

    @Test
    void exceptionStillRestoresOuterThreadContext() {
        LaneWebFilter filter = new LaneWebFilter(properties());
        LaneContext.setTrafficScopeId("outer-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-scope");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/runs");
        request.addHeader(LaneWebFilter.LANE_TAG_HEADER, "lane-a");

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
