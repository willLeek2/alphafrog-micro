package world.willfrog.externalinfo.search.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchBackendRetryTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        callCount.set(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SearchBackendRetry newRetry(int maxAttempts, long delayMs, Set<Integer> codes) {
        SearchLlmProperties properties = new SearchLlmProperties();
        SearchLlmProperties.WebSearchRetry r = new SearchLlmProperties.WebSearchRetry();
        r.setMaxAttempts(maxAttempts);
        r.setDelayMs(delayMs);
        r.setRetryableStatusCodes(new java.util.ArrayList<>(codes));
        properties.getFeatures().getWebSearch().setRetry(r);
        return new SearchBackendRetry(properties);
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
    }

    private HttpRequest newGetRequest() {
        return HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/probe"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET()
                .build();
    }

    private void startServer(java.util.function.BiConsumer<com.sun.net.httpserver.HttpExchange, Integer> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/probe", exchange -> {
            int n = callCount.incrementAndGet();
            handler.accept(exchange, n);
        });
        server.start();
        port = server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    @Test
    void sendWithRetry_firstTrySucceeds_returnsOneAttempt() throws Exception {
        startServer((ex, n) -> {
            try {
                respond(ex, 200, "ok");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SearchBackendRetry retry = newRetry(2, 0L, Set.of(500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertTrue(result.ok());
        assertEquals(200, result.response().statusCode());
        assertEquals(1, result.attempts());
        assertEquals(1, callCount.get());
        assertNull(result.error());
    }

    @Test
    void sendWithRetry_retryableStatus_succeedsOnSecondAttempt() throws Exception {
        startServer((ex, n) -> {
            try {
                if (n == 1) {
                    respond(ex, 503, "upstream-busy");
                } else {
                    respond(ex, 200, "ok");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SearchBackendRetry retry = newRetry(2, 0L, Set.of(500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertTrue(result.ok());
        assertEquals(2, result.attempts());
        assertEquals(2, callCount.get());
    }

    @Test
    void sendWithRetry_nonRetryableStatus_doesNotRetry() throws Exception {
        startServer((ex, n) -> {
            try {
                respond(ex, 400, "bad-request");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SearchBackendRetry retry = newRetry(3, 0L, Set.of(500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertFalse(result.ok());
        assertTrue(result.isHttpFailure());
        assertEquals(400, result.response().statusCode());
        assertEquals(1, result.attempts());
        assertEquals(1, callCount.get());
    }

    @Test
    void sendWithRetry_retryableStatusExhaustsAttempts_returnsLastResponse() throws Exception {
        startServer((ex, n) -> {
            try {
                respond(ex, 503, "still-busy");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SearchBackendRetry retry = newRetry(2, 0L, Set.of(500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertTrue(result.isHttpFailure());
        assertEquals(503, result.response().statusCode());
        assertEquals(2, result.attempts());
        assertEquals(2, callCount.get());
    }

    @Test
    void sendWithRetry_ioException_isRetriedThenTerminal() throws Exception {
        // Server is NOT started → connect will throw IOException.
        // We deliberately use a port that nothing is listening on.
        port = 1; // privileged port with no listener
        SearchBackendRetry retry = newRetry(2, 0L, Set.of(500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertTrue(result.isException());
        assertNotNull(result.error());
        assertEquals(2, result.attempts());
    }

    @Test
    void sendWithRetry_usesDefaults_whenConfigIsEmpty() {
        SearchLlmProperties properties = new SearchLlmProperties();
        // features.webSearch.retry stays at its default (empty fields)
        SearchBackendRetry retry = new SearchBackendRetry(properties);
        SearchBackendRetry.RetryConfig config = retry.config();

        assertEquals(2, config.maxAttempts());
        assertEquals(1000L, config.delayMs());
        assertTrue(config.retryableStatusCodes().contains(429));
        assertTrue(config.retryableStatusCodes().contains(503));
    }

    @Test
    void sendWithRetry_nullProperties_usesDefaults() {
        SearchBackendRetry retry = new SearchBackendRetry(null);
        SearchBackendRetry.RetryConfig config = retry.config();

        assertEquals(2, config.maxAttempts());
        assertEquals(1000L, config.delayMs());
    }

    @Test
    void sendWithRetry_4xxNotInList_doesNotRetry() throws Exception {
        startServer((ex, n) -> {
            try {
                respond(ex, 404, "not-found");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SearchBackendRetry retry = newRetry(3, 0L, Set.of(408, 425, 429, 500, 502, 503, 504));

        SearchBackendRetry.RetryResult result = retry.sendWithRetry(newClient(), newGetRequest(), "test");

        assertTrue(result.isHttpFailure());
        assertEquals(404, result.response().statusCode());
        assertEquals(1, result.attempts());
        assertEquals(1, callCount.get());
    }
}
