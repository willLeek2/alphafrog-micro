package world.willfrog.externalinfo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchLlmLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_shouldFailForLegacyMarketNewsSchema() throws Exception {
        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "defaultProvider": "exa",
                  "marketNews": {
                    "defaultLimit": 8,
                    "queries": ["今日A股"]
                  }
                }
                """, StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());

        loader.load();

        assertFalse(loader.current().isPresent());
    }

    @Test
    void load_shouldParseFeatureProfilesSchema() throws Exception {
        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "providers": {
                    "exa": {
                      "baseUrl": "https://api.exa.ai",
                      "apiKey": "k",
                      "searchPath": "/search",
                      "authHeader": "x-api-key"
                    }
                  },
                  "features": {
                    "marketNews": {
                      "defaultProvider": "exa",
                      "defaultLimit": 8,
                      "profiles": [
                        {
                          "name": "cn",
                          "query": "今日A股",
                          "includeDomains": ["sina.com.cn"],
                          "languages": ["zh"]
                        }
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());

        loader.load();

        assertTrue(loader.current().isPresent());
        assertEquals(1, loader.current().orElseThrow().getFeatures().getMarketNews().getProfiles().size());
    }

    @Test
    void load_shouldRejectProfileWithOnlyBlankQueries() throws Exception {
        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "providers": {
                    "exa": {
                      "baseUrl": "https://api.exa.ai",
                      "apiKey": "k",
                      "searchPath": "/search",
                      "authHeader": "x-api-key"
                    }
                  },
                  "features": {
                    "marketNews": {
                      "defaultProvider": "exa",
                      "profiles": [
                        {
                          "name": "cn",
                          "queries": [" ", ""]
                        }
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());

        loader.load();

        assertFalse(loader.current().isPresent());
    }

    @Test
    void laneProcess_shouldReadIsolatedCacheAndIgnoreSharedPath() throws Exception {
        Path shared = tempDir.resolve("search-llm.local.json");
        Path isolated = shared.getParent().resolve("stage3-dag-0922.search-llm.local.json");
        Files.writeString(shared, featureProfilesJson("shared"), StandardCharsets.UTF_8);
        Files.writeString(isolated, featureProfilesJson("lane"), StandardCharsets.UTF_8);

        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", shared.toString());
        ReflectionTestUtils.setField(loader, "laneTrafficScopeId", "stage3-dag-0922");

        loader.applyNacosWrittenFile(shared.toString());
        assertFalse(loader.current().isPresent(), "共用路径上的主环境缓存不得被泳道加载器当成覆盖");

        loader.applyNacosWrittenFile(isolated.toString());
        assertEquals("lane",
                loader.current().orElseThrow().getFeatures().getMarketNews().getProfiles().get(0).getName());
        loader.refresh();
        assertEquals("lane",
                loader.current().orElseThrow().getFeatures().getMarketNews().getProfiles().get(0).getName());
    }

    @Test
    void snakeCaseApplicationMapper_stillBindsCamelCaseProviderFields() throws Exception {
        Path configFile = tempDir.resolve("search-llm.local.json");
        Files.writeString(configFile, """
                {
                  "providers": {
                    "exa": {
                      "baseUrl": "https://api.exa.ai",
                      "apiKey": "k",
                      "searchPath": "/search",
                      "authHeader": "x-api-key"
                    }
                  },
                  "features": {
                    "marketNews": {
                      "defaultProvider": "exa",
                      "defaultLimit": 8,
                      "profiles": [
                        {
                          "name": "cn",
                          "query": "今日A股",
                          "includeDomains": ["sina.com.cn"]
                        }
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ObjectMapper snake = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        SearchLlmLocalConfigLoader loader = new SearchLlmLocalConfigLoader(snake);
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());

        loader.load();

        assertEquals("https://api.exa.ai",
                loader.current().orElseThrow().getProviders().get("exa").getBaseUrl());
        assertEquals(List.of("sina.com.cn"),
                loader.current().orElseThrow().getFeatures().getMarketNews().getProfiles().get(0).getIncludeDomains());
    }

    private static String featureProfilesJson(String profileName) {
        return """
                {
                  "providers": {
                    "exa": {
                      "baseUrl": "https://api.exa.ai",
                      "apiKey": "k",
                      "searchPath": "/search",
                      "authHeader": "x-api-key"
                    }
                  },
                  "features": {
                    "marketNews": {
                      "defaultProvider": "exa",
                      "defaultLimit": 8,
                      "profiles": [
                        {
                          "name": "%s",
                          "query": "今日A股"
                        }
                      ]
                    }
                  }
                }
                """.formatted(profileName);
    }
}
