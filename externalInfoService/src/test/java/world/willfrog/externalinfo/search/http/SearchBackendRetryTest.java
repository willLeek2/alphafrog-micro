package world.willfrog.externalinfo.search.http;

import com.sun.net.httpserver.HttpServer;
import java.util.List;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputInvocation;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputStore;
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

    private static final String INPUT = "{\"query\":\"test\"}";

    private ModelInputInvocation invocation() {
        return ModelInputInvocation.start("search-test", new ModelInputSources(List.of(
                new ModelInputSource("/query", ModelInputSource.Origin.RUNTIME_DATA, List.of()))),
                attempt -> new ModelInputStore.Confirmation("saved", attempt.attemptId(), attempt.sha256(), attempt.utf8Length()));
    }

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

    private HttpRequest newPostRequest() {
        return HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/probe"))
                .timeout(java.time.Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(INPUT, StandardCharsets.UTF_8))
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








}
