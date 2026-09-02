package world.willfrog.alphafrogmicro.frontend.lane;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/** 每个新的入口请求都从部署控制器读取一次当前默认路由，不跨请求复用旧指针。 */
@Slf4j
public final class DirectLaneRouteFactsSource implements LaneRouteFactsSource {

    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(30);

    private final LaneRouteFactsFetcher client;
    private final Clock clock;
    private final AtomicLong nextWarningAtMillis = new AtomicLong();

    public DirectLaneRouteFactsSource(LaneRouteFactsFetcher client) {
        this(client, Clock.systemUTC());
    }

    DirectLaneRouteFactsSource(LaneRouteFactsFetcher client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Optional<LaneRouteFacts> current(String trafficScopeId, String serviceName) {
        try {
            Optional<LaneRouteFacts> facts = client.fetch(trafficScopeId, serviceName);
            if (facts.isEmpty()) {
                warn("部署控制器没有提供可用且一致的默认路由事实");
            }
            return facts;
        } catch (RuntimeException exception) {
            warn("部署控制器不可用，当前测试请求停止转发");
            return Optional.empty();
        }
    }

    private void warn(String message) {
        long now = clock.millis();
        long next = nextWarningAtMillis.get();
        if (now >= next && nextWarningAtMillis.compareAndSet(next, now + WARNING_INTERVAL.toMillis())) {
            log.warn(message);
        }
    }
}
