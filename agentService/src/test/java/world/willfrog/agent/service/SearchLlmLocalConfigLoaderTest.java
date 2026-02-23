package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.SearchLlmProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchLlmLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_shouldReadPromptsAndProviders() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("search");
        Files.createDirectories(promptsDir);
        Path promptFile = promptsDir.resolve("market_news_query.txt");
        Files.writeString(promptFile, "market prompt v1", StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "defaultProvider": "perplexity",
                  "providers": {
                    "perplexity": {
                      "type": "perplexity_search",
                      "baseUrl": "https://api.perplexity.ai",
                      "searchPath": "/search",
                      "defaultRecency": "day",
                      "defaultDomains": ["sina.com.cn"],
                      "defaultLanguages": ["zh"],
                      "maxResults": 5
                    }
                  },
                  "prompts": {
                    "marketNewsQuery": "file:prompts/search/market_news_query.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        SearchLlmProperties cfg = loader.current().orElseThrow();
        assertEquals("perplexity", cfg.getDefaultProvider());
        assertEquals("market prompt v1", cfg.getPrompts().getMarketNewsQuery());
        assertEquals(1, cfg.getProviders().size());
        assertEquals("day", cfg.getProviders().get("perplexity").getDefaultRecency());
        assertEquals(5, cfg.getProviders().get("perplexity").getMaxResults());
    }

    @Test
    void refresh_shouldReloadWhenPromptChanges() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("search");
        Files.createDirectories(promptsDir);
        Path promptFile = promptsDir.resolve("market_news_query.txt");
        Files.writeString(promptFile, "market prompt v1", StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "marketNewsQuery": "file:prompts/search/market_news_query.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals("market prompt v1", loader.current().orElseThrow().getPrompts().getMarketNewsQuery());

        Thread.sleep(5L);
        Files.writeString(promptFile, "market prompt v2", StandardCharsets.UTF_8);
        loader.refresh();

        assertTrue(loader.current().isPresent());
        assertEquals("market prompt v2", loader.current().orElseThrow().getPrompts().getMarketNewsQuery());
    }
}
