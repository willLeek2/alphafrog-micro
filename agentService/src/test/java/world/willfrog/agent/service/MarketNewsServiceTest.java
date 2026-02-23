package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.config.SearchLlmProperties;
import world.willfrog.agent.model.MarketNewsModels;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketNewsServiceTest {

    @Mock
    private SearchLlmLocalConfigLoader localConfigLoader;
    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpResponse<String> httpResponse;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SearchLlmProperties properties;

    @BeforeEach
    void init() {
        properties = new SearchLlmProperties();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTodayMarketNews_shouldCallPerplexitySearch() throws Exception {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setType("perplexity_search");
        provider.setBaseUrl("https://api.perplexity.ai");
        provider.setSearchPath("/search");
        provider.setDefaultRecency("day");
        provider.setMaxResults(5);
        properties.setDefaultProvider("perplexity");
        properties.getProviders().put("perplexity", provider);

        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("""
                {
                  "results": [
                    {
                      "title": "沪深300指数开盘上涨0.5%",
                      "url": "https://finance.sina.com.cn/a",
                      "snippet": "今日A股市场开盘表现活跃",
                      "date": "2026-02-22",
                      "last_updated": "2026-02-22T09:30:00+08:00"
                    }
                  ],
                  "server_time": "2026-02-22T09:35:00Z"
                }
                """);

        MarketNewsService service = new MarketNewsService(properties, localConfigLoader, objectMapper, httpClient);
        var result = service.getTodayMarketNews(new MarketNewsModels.MarketNewsQuery(
                2,
                "perplexity",
                "zh",
                "2026-02-22T00:00:00+08:00",
                "2026-02-22T23:59:59+08:00"
        ));

        assertEquals("perplexity", result.provider());
        assertEquals("2026-02-22T09:35:00Z", result.updatedAt());
        assertEquals(1, result.items().size());
        assertEquals("沪深300指数开盘上涨0.5%", result.items().get(0).title());
        assertEquals("https://finance.sina.com.cn/a", result.items().get(0).url());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTodayMarketNews_shouldCallExa() throws Exception {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setType("exa_search");
        provider.setBaseUrl("https://api.exa.ai");
        provider.setSearchPath("/search");
        provider.setDefaultCategory("news");
        provider.setDefaultType("auto");
        provider.setDefaultLanguages(java.util.List.of("zh"));
        properties.setDefaultProvider("exa");
        properties.getProviders().put("exa", provider);

        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("""
                {
                  "results": [
                    {
                      "id": "exa_1",
                      "title": "央行开展1000亿元逆回购操作",
                      "url": "https://www.pbc.gov.cn/news",
                      "publishedDate": "2026-02-22T09:15:00Z",
                      "author": "央行官网"
                    }
                  ]
                }
                """);

        MarketNewsService service = new MarketNewsService(properties, localConfigLoader, objectMapper, httpClient);
        var result = service.getTodayMarketNews(new MarketNewsModels.MarketNewsQuery(
                3,
                "exa",
                "",
                "2026-02-22T00:00:00Z",
                "2026-02-22T23:59:59Z"
        ));

        assertEquals("exa", result.provider());
        assertEquals(1, result.items().size());
        assertEquals("央行开展1000亿元逆回购操作", result.items().get(0).title());
        assertEquals("https://www.pbc.gov.cn/news", result.items().get(0).url());
        assertEquals("央行官网", result.items().get(0).source());
        assertEquals("2026-02-22T09:15:00Z", result.updatedAt());
    }
}
