package world.willfrog.alphafrogmicro.frontend.lane;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * 给部署控制器读取增加短周期缓存。明确的空结果立即失效；临时网络错误只能在最大陈旧期限内使用旧事实。
 */
@Slf4j
public final class CachingLaneRouteFactsSource implements LaneRouteFactsSource {

    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(30);

    private final LaneRouteFactsFetcher client;
    private final LaneEntryProperties properties;
    private final Clock clock;
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private final AtomicLong nextWarningAtMillis = new AtomicLong();

    public CachingLaneRouteFactsSource(
            LaneRouteFactsFetcher client,
            LaneEntryProperties properties) {
        this(client, properties, Clock.systemUTC());
    }

    CachingLaneRouteFactsSource(
            LaneRouteFactsFetcher client,
            LaneEntryProperties properties,
            Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<LaneRouteFacts> current(String trafficScopeId, String serviceName) {
        String key = trafficScopeId + '\n' + serviceName;
        Instant now = clock.instant();
        CacheEntry cached = entries.get(key);
        if (cached != null && now.isBefore(cached.refreshedAt().plus(properties.getRefreshInterval()))) {
            return Optional.of(cached.facts());
        }
        try {
            Optional<LaneRouteFacts> fresh = client.fetch(trafficScopeId, serviceName);
            if (fresh.isEmpty()) {
                entries.remove(key);
                warn("部署控制器没有提供可用且一致的默认路由事实");
                return Optional.empty();
            }
            entries.put(key, new CacheEntry(fresh.get(), now));
            return fresh;
        } catch (RuntimeException exception) {
            if (cached != null && !now.isAfter(cached.refreshedAt().plus(properties.getMaxStale()))) {
                warn("部署控制器暂时不可用，正在使用仍未超过期限的最近路由事实");
                return Optional.of(cached.facts());
            }
            entries.remove(key);
            warn("部署控制器不可用且本地路由事实已经过期，当前请求不打标");
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

    private record CacheEntry(LaneRouteFacts facts, Instant refreshedAt) {
    }
}
