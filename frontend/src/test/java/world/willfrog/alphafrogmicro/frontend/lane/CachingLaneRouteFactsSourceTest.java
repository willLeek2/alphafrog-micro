package world.willfrog.alphafrogmicro.frontend.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

class CachingLaneRouteFactsSourceTest {

    @Test
    void temporaryFailureUsesOnlyUnexpiredLastGoodFacts() {
        LaneEntryProperties properties = properties();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        LaneRouteFacts facts = facts();
        AtomicInteger calls = new AtomicInteger();
        LaneRouteFactsFetcher fetcher = (scope, service) -> {
            if (calls.getAndIncrement() == 0) {
                return Optional.of(facts);
            }
            throw new LaneRouteFactsUnavailableException("controller unavailable");
        };
        CachingLaneRouteFactsSource source = new CachingLaneRouteFactsSource(fetcher, properties, clock);

        assertEquals(facts, source.current("lane-test", "agent-langchain-service").orElseThrow());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(facts, source.current("lane-test", "agent-langchain-service").orElseThrow());
        assertEquals(1, calls.get());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(facts, source.current("lane-test", "agent-langchain-service").orElseThrow());
        clock.advance(Duration.ofSeconds(8));
        assertTrue(source.current("lane-test", "agent-langchain-service").isEmpty());
    }

    @Test
    void authoritativeEmptyResultImmediatelyInvalidatesOldFacts() {
        LaneEntryProperties properties = properties();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        Deque<Optional<LaneRouteFacts>> values = new ArrayDeque<>();
        values.add(Optional.of(facts()));
        values.add(Optional.empty());
        CachingLaneRouteFactsSource source = new CachingLaneRouteFactsSource(
                (scope, service) -> values.removeFirst(), properties, clock);

        assertTrue(source.current("lane-test", "agent-langchain-service").isPresent());
        clock.advance(Duration.ofSeconds(3));
        assertTrue(source.current("lane-test", "agent-langchain-service").isEmpty());
    }

    @Test
    void unexpectedClientFailureAlsoFailsClosedWithoutBreakingTheRequest() {
        LaneEntryProperties properties = properties();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        CachingLaneRouteFactsSource source = new CachingLaneRouteFactsSource(
                (scope, service) -> {
                    throw new IllegalStateException("unexpected client failure");
                }, properties, clock);

        assertTrue(source.current("lane-test", "agent-langchain-service").isEmpty());
    }

    private static LaneEntryProperties properties() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setRefreshInterval(Duration.ofSeconds(2));
        properties.setMaxStale(Duration.ofSeconds(10));
        return properties;
    }

    private static LaneRouteFacts facts() {
        return new LaneRouteFacts(
                "lane-test", "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", "gen-" + "a".repeat(64)), 7);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
