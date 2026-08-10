package world.willfrog.sandbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 260809-26Q3-stage1-w3 D13: Gateway HTTP client assembly.
 *
 * Two RestTemplate beans, each with explicit connect + read timeouts:
 *   - sandboxLongHttpClient:  createTask + getTaskResult (long-path)
 *   - sandboxShortHttpClient: getTaskStatus + getTaskByOperationId + cancelTask (short-query)
 *
 * D13 plan §4.1 + red lines 1/2/3 + Cindy 91490076 #3 (runtime invariants):
 *   - No production path may use a no-timeout default RestTemplate.
 *   - Long-path read >= max task timeout_millis + queue/prepare margin.
 *   - Short-query uses strictly shorter read timeout.
 *   - All bounds positive; short < long; long >= max + margin. Any violation fails fast at
 *     bean construction (IllegalArgumentException) so misconfiguration cannot silently
 *     restore infinite waits or invert client roles.
 *
 * Inject with @Qualifier — every consumer must prove it binds the correct bean.
 */
@Configuration
public class RestTemplateConfig {

    @Bean(name = "sandboxLongHttpClient")
    public RestTemplate sandboxLongHttpClient(
            @Value("${sandbox.service.connect-timeout-millis:5000}") long connectMillis,
            @Value("${sandbox.service.long-read-timeout-millis:2100000}") long longReadMillis,
            @Value("${sandbox.service.short-read-timeout-millis:10000}") long shortReadMillis,
            @Value("${sandbox.service.max-task-timeout-millis:1800000}") long maxTaskTimeoutMillis,
            @Value("${sandbox.service.queue-prepare-margin-millis:300000}") long queuePrepareMarginMillis
    ) {
        validateTimeoutConfiguration(connectMillis, shortReadMillis, longReadMillis,
                maxTaskTimeoutMillis, queuePrepareMarginMillis);
        return buildRestTemplate(connectMillis, longReadMillis);
    }

    @Bean(name = "sandboxShortHttpClient")
    public RestTemplate sandboxShortHttpClient(
            @Value("${sandbox.service.connect-timeout-millis:5000}") long connectMillis,
            @Value("${sandbox.service.long-read-timeout-millis:2100000}") long longReadMillis,
            @Value("${sandbox.service.short-read-timeout-millis:10000}") long shortReadMillis,
            @Value("${sandbox.service.max-task-timeout-millis:1800000}") long maxTaskTimeoutMillis,
            @Value("${sandbox.service.queue-prepare-margin-millis:300000}") long queuePrepareMarginMillis
    ) {
        // Validate again on the short bean path; both beans share the same config so a
        // single misconfiguration fails fast regardless of which bean Spring instantiates first.
        validateTimeoutConfiguration(connectMillis, shortReadMillis, longReadMillis,
                maxTaskTimeoutMillis, queuePrepareMarginMillis);
        return buildRestTemplate(connectMillis, shortReadMillis);
    }

    /**
     * D13 MUST-FIX 3 + round-2 #2 (Cindy 91490076 #3 + 6a6e6158 + 1b29792d #2 +
     * codex 3d78edba/aa8987d1): runtime invariants. Throws IllegalArgumentException
     * on any violation so Spring context fails to start instead of silently accepting bad config.
     *
     * Checks (long arithmetic with overflow guard — every value MUST be a positive long):
     *   1. connectMillis > 0
     *   2. shortReadMillis > 0
     *   3. longReadMillis > 0
     *   4. maxTaskTimeoutMillis > 0
     *   5. queuePrepareMarginMillis > 0 (margin must be strictly positive — zero margin
     *      would silently allow long-read == max-task-timeout, defeating the budget proof)
     *   6. shortReadMillis < longReadMillis (long-path is strictly longer than short-query)
     *   7. longReadMillis >= maxTaskTimeoutMillis + queuePrepareMarginMillis (with overflow guard)
     */
    public static void validateTimeoutConfiguration(
            long connectMillis, long shortReadMillis, long longReadMillis,
            long maxTaskTimeoutMillis, long queuePrepareMarginMillis
    ) {
        if (connectMillis <= 0) {
            throw new IllegalArgumentException(
                    "sandbox.service.connect-timeout-millis must be > 0, got " + connectMillis);
        }
        if (shortReadMillis <= 0) {
            throw new IllegalArgumentException(
                    "sandbox.service.short-read-timeout-millis must be > 0, got " + shortReadMillis);
        }
        if (longReadMillis <= 0) {
            throw new IllegalArgumentException(
                    "sandbox.service.long-read-timeout-millis must be > 0, got " + longReadMillis);
        }
        if (maxTaskTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "sandbox.service.max-task-timeout-millis must be > 0, got " + maxTaskTimeoutMillis);
        }
        if (queuePrepareMarginMillis <= 0) {
            throw new IllegalArgumentException(
                    "sandbox.service.queue-prepare-margin-millis must be > 0, got "
                            + queuePrepareMarginMillis);
        }
        if (shortReadMillis >= longReadMillis) {
            throw new IllegalArgumentException(
                    "sandbox.service.short-read-timeout-millis (" + shortReadMillis
                            + ") must be strictly less than long-read-timeout-millis ("
                            + longReadMillis + ")");
        }
        // Overflow-safe addition: if maxTaskTimeoutMillis + margin overflows, the config is
        // clearly absurd; reject without computing the wrapped sum.
        if (maxTaskTimeoutMillis > Long.MAX_VALUE - queuePrepareMarginMillis) {
            throw new IllegalArgumentException(
                    "sandbox.service.max-task-timeout-millis + queue-prepare-margin-millis overflow: "
                            + maxTaskTimeoutMillis + " + " + queuePrepareMarginMillis);
        }
        long requiredLongRead = maxTaskTimeoutMillis + queuePrepareMarginMillis;
        if (longReadMillis < requiredLongRead) {
            throw new IllegalArgumentException(
                    "sandbox.service.long-read-timeout-millis (" + longReadMillis
                            + ") must be >= max-task-timeout-millis + queue-prepare-margin-millis ("
                            + maxTaskTimeoutMillis + " + " + queuePrepareMarginMillis
                            + " = " + requiredLongRead + ")");
        }
    }

    private static RestTemplate buildRestTemplate(long connectMillis, long readMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(connectMillis));
        factory.setReadTimeout(Math.toIntExact(readMillis));
        return new RestTemplate(factory);
    }
}
