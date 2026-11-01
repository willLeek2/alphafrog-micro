package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GrayDeciderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();




    private String rulesDocument() {
        return """
                {
                  "ruleVersion": "2026-09-02-01",
                  "bucketSalt": "alpha-salt-260831",
                  "rules": [
                    {
                      "ruleId": "disabled-rule",
                      "enabled": false,
                      "percent": 100,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    },
                    {
                      "ruleId": "expired-rule",
                      "enabled": true,
                      "percent": 100,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2026-09-02T11:59:59Z"
                    },
                    {
                      "ruleId": "expires-now-rule",
                      "enabled": true,
                      "percent": 100,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2026-09-02T12:00:00Z"
                    },
                    {
                      "ruleId": "full-rollout-rule",
                      "enabled": true,
                      "percent": 100,
                      "userFilter": [],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    },
                    {
                      "ruleId": "allowlist-rule",
                      "enabled": true,
                      "percent": 0,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    },
                    {
                      "ruleId": "space-rule",
                      "enabled": true,
                      "percent": 0,
                      "userFilter": [" user "],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    },
                    {
                      "ruleId": "demo-rule",
                      "enabled": true,
                      "percent": 10,
                      "userFilter": [],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    }
                  ]
                }
                """;
    }

    private String rulesDocument(JsonNode testCase) throws Exception {
        String expiresAt = testCase.path("expired").asBoolean()
                ? "2026-09-02T11:59:59Z"
                : "2099-01-01T00:00:00Z";
        return """
                {
                  "ruleVersion": "2026-09-02-contract",
                  "bucketSalt": "%s",
                  "rules": [
                    {
                      "ruleId": "demo-rule",
                      "enabled": %s,
                      "percent": %d,
                      "userFilter": %s,
                      "owner": "platform",
                      "expiresAt": "%s"
                    }
                  ]
                }
                """.formatted(
                testCase.path("bucketSalt").asText("alpha-salt-260831"),
                testCase.path("enabled").asBoolean(),
                testCase.path("percent").asInt(),
                objectMapper.writeValueAsString(testCase.path("userFilter")),
                expiresAt);
    }

    private Path repositoryFile(String relativePath) {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Repository file not found: " + relativePath);
    }
}
