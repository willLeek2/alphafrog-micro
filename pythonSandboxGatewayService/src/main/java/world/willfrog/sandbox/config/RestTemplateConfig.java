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
 * D13 plan §4.1 + red lines 1/2/3:
 *   - No production path may use a no-timeout default RestTemplate.
 *   - Long-path read >= max task timeout_millis + queue/prepare margin.
 *   - Short-query uses strictly shorter read timeout.
 *
 * Inject with @Qualifier — every consumer must prove it binds the correct bean.
 */
@Configuration
public class RestTemplateConfig {

    @Bean(name = "sandboxLongHttpClient")
    public RestTemplate sandboxLongHttpClient(
            @Value("${sandbox.service.connect-timeout-millis:5000}") int connectMillis,
            @Value("${sandbox.service.long-read-timeout-millis:2100000}") int longReadMillis
    ) {
        return buildRestTemplate(connectMillis, longReadMillis);
    }

    @Bean(name = "sandboxShortHttpClient")
    public RestTemplate sandboxShortHttpClient(
            @Value("${sandbox.service.connect-timeout-millis:5000}") int connectMillis,
            @Value("${sandbox.service.short-read-timeout-millis:10000}") int shortReadMillis
    ) {
        return buildRestTemplate(connectMillis, shortReadMillis);
    }

    private static RestTemplate buildRestTemplate(int connectMillis, int readMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMillis);
        factory.setReadTimeout(readMillis);
        return new RestTemplate(factory);
    }
}
