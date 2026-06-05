package world.willfrog.externalinfo.search.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Wraps {@link HttpClient#send(HttpRequest, HttpResponse.BodyHandler)} with
 * bounded retry for transient upstream failures.
 *
 * <p>Scope (260605-2 §1.3): <b>per-backend retry only</b>. The caller (each
 * {@code SearchBackend} impl) is still the unit of failure: a backend that
 * exhausts retries returns its own error result. Cross-backend fallback
 * (Perplexity → Tavily/Exa) lives one layer up in
 * {@code WebSearchOrchestrator} (Phase 2, not in this class).</p>
 *
 * <p>Default policy (env-overridable via
 * {@code external-info.search-llm.features.web-search.retry.*}):
 * <ul>
 *   <li>{@code maxAttempts=2} (1 initial + 1 retry) — keeps tail latency tight
 *       since these are synchronous user-facing paths.</li>
 *   <li>{@code delayMs=1000} — fixed backoff, sufficient to ride out short
 *       upstream hiccups observed in the 260605 stress test.</li>
 *   <li>Retryable HTTP statuses: 408, 425, 429, 500, 502, 503, 504.</li>
 *   <li>IOExceptions and HTTP timeouts always retry up to the attempt budget.</li>
 *   <li>4xx other than 408/425/429 do NOT retry — caller error, won't fix itself.</li>
 * </ul>
 */
@Component
@Slf4j
public class SearchBackendRetry {

    private static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES = Set.of(
            408, 425, 429, 500, 502, 503, 504
    );

    private final RetryConfig config;

    public SearchBackendRetry(SearchLlmProperties properties) {
        this.config = resolveConfig(properties);
    }

    public RetryConfig config() {
        return config;
    }

    /**
     * Send {@code request} with retry. Returns the final outcome — caller is
     * responsible for mapping non-2xx and exceptions to a
     * {@code BackendSearchResult.error(...)}.
     */
    public RetryResult sendWithRetry(HttpClient client,
                                     HttpRequest request,
                                     String backendName) {
        long startMs = System.currentTimeMillis();
        int maxAttempts = Math.max(1, config.maxAttempts());
        long delayMs = Math.max(0, config.delayMs());
        HttpResponse<String> lastResponse = null;
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                lastResponse = response;
                lastError = null;
                if (response.statusCode() < 300) {
                    return RetryResult.success(response, attempt,
                            System.currentTimeMillis() - startMs, null);
                }
                if (!isRetryableStatus(response.statusCode()) || attempt == maxAttempts) {
                    return RetryResult.failure(response, attempt,
                            System.currentTimeMillis() - startMs, null);
                }
                log.warn("Search backend {} 返回可重试状态码 {}，第 {}/{} 次重试前等待 {}ms",
                        backendName, response.statusCode(), attempt, maxAttempts, delayMs);
            } catch (IOException e) {
                lastError = e;
                lastResponse = null;
                if (attempt == maxAttempts) {
                    return RetryResult.exception(attempt, System.currentTimeMillis() - startMs, e);
                }
                log.warn("Search backend {} IO 异常 (attempt {}/{}): {}，等待 {}ms 后重试",
                        backendName, attempt, maxAttempts, e.getMessage(), delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RetryResult.exception(attempt, System.currentTimeMillis() - startMs, e);
            } catch (RuntimeException e) {
                // HttpClient wraps connect / timeout in HttpConnectTimeoutException
                // (subclass of IOException) — any other RuntimeException is a bug
                // in our caller code, not the upstream; do not retry it.
                if (attempt == maxAttempts || !isTransientRuntime(e)) {
                    return RetryResult.exception(attempt, System.currentTimeMillis() - startMs, e);
                }
                lastError = e;
                log.warn("Search backend {} 运行时异常 (attempt {}/{}): {}，等待 {}ms 后重试",
                        backendName, attempt, maxAttempts, e.getMessage(), delayMs);
            }

            sleep(delayMs);
        }

        // Unreachable: loop always returns or throws. Defensive fallback.
        if (lastResponse != null) {
            return RetryResult.failure(lastResponse, maxAttempts,
                    System.currentTimeMillis() - startMs, null);
        }
        return RetryResult.exception(maxAttempts, System.currentTimeMillis() - startMs, lastError);
    }

    private boolean isRetryableStatus(int status) {
        return config.retryableStatusCodes().contains(status);
    }

    private static boolean isTransientRuntime(RuntimeException e) {
        String name = e.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("timeout") || name.contains("connect");
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static RetryConfig resolveConfig(SearchLlmProperties properties) {
        if (properties == null
                || properties.getFeatures() == null
                || properties.getFeatures().getWebSearch() == null
                || properties.getFeatures().getWebSearch().getRetry() == null) {
            return RetryConfig.defaults();
        }
        SearchLlmProperties.WebSearchRetry r = properties.getFeatures().getWebSearch().getRetry();
        int maxAttempts = (r.getMaxAttempts() == null || r.getMaxAttempts() < 1)
                ? RetryConfig.defaults().maxAttempts() : r.getMaxAttempts();
        long delayMs = (r.getDelayMs() == null || r.getDelayMs() < 0)
                ? RetryConfig.defaults().delayMs() : r.getDelayMs();
        Set<Integer> codes = (r.getRetryableStatusCodes() == null || r.getRetryableStatusCodes().isEmpty())
                ? RetryConfig.defaults().retryableStatusCodes()
                : Set.copyOf(r.getRetryableStatusCodes());
        return new RetryConfig(maxAttempts, delayMs, codes);
    }

    /**
     * Retry policy. Immutable.
     */
    public record RetryConfig(int maxAttempts, long delayMs, Set<Integer> retryableStatusCodes) {
        public static RetryConfig defaults() {
            return new RetryConfig(2, 1000L, DEFAULT_RETRYABLE_STATUS_CODES);
        }
    }

    /**
     * Outcome of a {@link #sendWithRetry} call.
     *
     * @param response      final HTTP response (may be null on exception path)
     * @param attempts      number of attempts made (1..maxAttempts)
     * @param totalDurationMs wall-clock duration including backoff sleeps
     * @param error         last exception (null on success / non-2xx terminal failure)
     */
    public record RetryResult(HttpResponse<String> response,
                              int attempts,
                              long totalDurationMs,
                              Throwable error) {

        public boolean ok() {
            return response != null && response.statusCode() < 300 && error == null;
        }

        public boolean isHttpFailure() {
            return response != null && response.statusCode() >= 300 && error == null;
        }

        public boolean isException() {
            return error != null;
        }

        public static RetryResult success(HttpResponse<String> response, int attempts,
                                          long durationMs, Throwable error) {
            return new RetryResult(response, attempts, durationMs, error);
        }

        public static RetryResult failure(HttpResponse<String> response, int attempts,
                                          long durationMs, Throwable error) {
            return new RetryResult(response, attempts, durationMs, error);
        }

        public static RetryResult exception(int attempts, long durationMs, Throwable error) {
            return new RetryResult(null, attempts, durationMs, error);
        }
    }

    // Unused but kept for symmetry with future duration-aware variants.
    @SuppressWarnings("unused")
    private static Duration toDuration(long ms) {
        return Duration.ofMillis(ms);
    }
}
