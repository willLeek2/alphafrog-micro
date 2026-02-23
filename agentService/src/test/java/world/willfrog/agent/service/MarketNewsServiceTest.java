package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.config.SearchLlmProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
