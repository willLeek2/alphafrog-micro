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
