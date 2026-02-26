package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.config.SearchLlmProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MarketNewsServiceTest {

    @Mock
    private SearchLlmLocalConfigLoader localConfigLoader;

    private SearchLlmProperties properties;
    private MarketNewsService service;

    @BeforeEach
    void setUp() {
        properties = new SearchLlmProperties();
        SearchLlmProperties.MarketNews marketNews = new SearchLlmProperties.MarketNews();
        marketNews.setCategoryKeywords(Map.of(
                "policy", List.of("央行"),
                "global", List.of("美股")
        ));
        properties.setMarketNews(marketNews);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        service = new MarketNewsService(new ObjectMapper(), properties, localConfigLoader);
    }

    @Test
    void mapPerplexityResults_shouldDeriveCategoryAndSource() {
        SearchLlmProperties.MarketNews marketNews = properties.getMarketNews();
        MarketNewsService.PerplexitySearchResult result = new MarketNewsService.PerplexitySearchResult(
                "央行开展逆回购操作，维护流动性合理充裕",
                "https://www.pbc.gov.cn/zhengcehuobisi/123456.html",
                "snippet",
                "2026-02-22",
                "2026-02-22T09:15:00+08:00"
        );

        List<MarketNewsService.MarketNewsItem> items = service.mapPerplexityResults(List.of(result), marketNews);

        assertEquals(1, items.size());
        assertEquals("policy", items.get(0).category());
        assertEquals("pbc.gov.cn", items.get(0).source());
    }

    @Test
    void filterItems_shouldRespectLanguageAndTimeRange() {
        List<MarketNewsService.MarketNewsItem> items = List.of(
                new MarketNewsService.MarketNewsItem(
                        "news-1",
                        "2026-02-22T09:15:00+08:00",
                        "央行开展逆回购操作",
                        "pbc.gov.cn",
                        "policy",
                        "https://www.pbc.gov.cn/zhengcehuobisi/123456.html"
                ),
                new MarketNewsService.MarketNewsItem(
                        "news-2",
                        "2026-02-20T10:00:00+08:00",
                        "US stocks rally on earnings",
                        "finance.example.com",
                        "global",
                        "https://finance.example.com/us-stocks"
                )
        );
        OffsetDateTime start = OffsetDateTime.parse("2026-02-22T00:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-02-22T23:59:59+08:00");

        List<MarketNewsService.MarketNewsItem> filtered = service.filterItems(
                items,
                List.of("zh"),
                start,
                end,
                5
        );

        assertEquals(1, filtered.size());
        assertEquals("news-1", filtered.get(0).id());
    }

    @Test
    void fetchFromExa_shouldNotRequestSummaryContent() throws Exception {
        AtomicReference<String> requestBodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requestBodyRef.set(new String(body, StandardCharsets.UTF_8));
            byte[] response = """
                    {"results":[{"title":"沪深300上涨","url":"https://news.example.com/a","author":"news.example.com","id":"exa-1","publishedDate":"2026-02-22T09:15:00+08:00"}]}
                    """.trim().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            provider.setSearchPath("/search");
            provider.setConnectTimeoutSeconds(3);
            provider.setRequestTimeoutSeconds(10);

            SearchLlmProperties.MarketNews marketNews = new SearchLlmProperties.MarketNews();
            marketNews.setExaSearchType("auto");
            marketNews.setExaCategory("news");
            marketNews.setIncludeDomains(List.of("sina.com.cn"));

            MarketNewsService.MarketNewsResponse response = service.fetchFromExa(
                    provider,
                    marketNews,
                    "今日A股新闻",
                    5,
                    null,
                    null
            );

            assertEquals(1, response.items().size());

            String requestBody = requestBodyRef.get();
            assertNotNull(requestBody);
            JsonNode root = new ObjectMapper().readTree(requestBody);
            JsonNode contents = root.path("contents");
            assertFalse(contents.path("text").asBoolean(true));
            assertFalse(contents.has("summary"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveTimeoutSeconds_shouldUseProviderOverrideAndFallbackDefaults() {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setConnectTimeoutSeconds(6);
        provider.setRequestTimeoutSeconds(15);

        assertEquals(6, service.resolveConnectTimeoutSeconds(provider));
        assertEquals(15, service.resolveRequestTimeoutSeconds(provider));

        provider.setConnectTimeoutSeconds(0);
        provider.setRequestTimeoutSeconds(-1);

        assertEquals(20, service.resolveConnectTimeoutSeconds(provider));
        assertEquals(45, service.resolveRequestTimeoutSeconds(provider));
        assertEquals(20, service.resolveConnectTimeoutSeconds(null));
        assertEquals(45, service.resolveRequestTimeoutSeconds(null));
    }
}
