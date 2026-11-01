package world.willfrog.externalinfo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        service = new MarketNewsService(new ObjectMapper(), properties, localConfigLoader);
    }






    private SearchLlmProperties buildMultiProfileConfig() {
        SearchLlmProperties cfg = new SearchLlmProperties();
        Map<String, SearchLlmProperties.Provider> providers = new LinkedHashMap<>();
        SearchLlmProperties.Provider exa = new SearchLlmProperties.Provider();
        exa.setBaseUrl("https://api.exa.ai");
        providers.put("exa", exa);
        SearchLlmProperties.Provider perplexity = new SearchLlmProperties.Provider();
        perplexity.setBaseUrl("https://api.perplexity.ai");
        providers.put("perplexity", perplexity);
        cfg.setProviders(providers);

        SearchLlmProperties.MarketNewsFeature marketNews = new SearchLlmProperties.MarketNewsFeature();
        marketNews.setDefaultProvider("exa");
        marketNews.setDefaultLimit(8);

        SearchLlmProperties.MarketNewsProfile cn = new SearchLlmProperties.MarketNewsProfile();
        cn.setName("cn");
        cn.setProvider("exa");
        cn.setQuery("cn");
        cn.setIncludeDomains(List.of("sina.com.cn"));

        SearchLlmProperties.MarketNewsProfile us = new SearchLlmProperties.MarketNewsProfile();
        us.setName("us");
        us.setProvider("perplexity");
        us.setQuery("us");
        us.setIncludeDomains(List.of("reuters.com"));

        marketNews.setProfiles(List.of(cn, us));
        SearchLlmProperties.Features features = new SearchLlmProperties.Features();
        features.setMarketNews(marketNews);
        cfg.setFeatures(features);
        return cfg;
    }
}
