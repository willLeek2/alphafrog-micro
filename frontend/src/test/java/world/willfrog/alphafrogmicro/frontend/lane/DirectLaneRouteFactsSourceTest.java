package world.willfrog.alphafrogmicro.frontend.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DirectLaneRouteFactsSourceTest {

    @Test
    void adjacentRequestsAlwaysReadCurrentPointerAgain() {
        LaneRouteFacts first = LaneRouteFactsTestData.facts("instance-a", 28081, 7);
        LaneRouteFacts second = LaneRouteFactsTestData.facts("instance-b", 28082, 8);
        Deque<Optional<LaneRouteFacts>> values = new ArrayDeque<>();
        values.add(Optional.of(first));
        values.add(Optional.of(second));
        AtomicInteger calls = new AtomicInteger();
        DirectLaneRouteFactsSource source = new DirectLaneRouteFactsSource((scope, service) -> {
            calls.incrementAndGet();
            return values.removeFirst();
        });

        assertEquals(first, source.current("lane-test", "agent-langchain-service").orElseThrow());
        assertEquals(second, source.current("lane-test", "agent-langchain-service").orElseThrow());
        assertEquals(2, calls.get());
    }

    @Test
    void authoritativeEmptyAndReadFailureBothReturnNoBinding() {
        DirectLaneRouteFactsSource empty = new DirectLaneRouteFactsSource(
                (scope, service) -> Optional.empty());
        DirectLaneRouteFactsSource failed = new DirectLaneRouteFactsSource((scope, service) -> {
            throw new LaneRouteFactsUnavailableException("controller unavailable");
        });

        assertTrue(empty.current("lane-test", "agent-langchain-service").isEmpty());
        assertTrue(failed.current("lane-test", "agent-langchain-service").isEmpty());
    }
}
