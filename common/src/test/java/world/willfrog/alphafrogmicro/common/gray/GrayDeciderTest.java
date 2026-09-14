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

    @Test
    void decisionOrder_shouldMatchEverySharedDecisionCase() throws Exception {
        JsonNode contract = objectMapper.readTree(Files.readAllBytes(
                repositoryFile("deploy/gray/gray-bucket-test-vectors.json")));
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);

        for (JsonNode testCase : contract.path("decisionCases")) {
            Path file = tempDir.resolve(testCase.path("id").asText() + ".json");
            Files.writeString(file, rulesDocument(testCase), StandardCharsets.UTF_8);

            try (GrayRuleStore store = new GrayRuleStore(
                    objectMapper, file, 60_000L, "common-test", "instance-test")) {
                assertThat(store.refreshNow()).as(testCase.path("id").asText()).isTrue();
                GrayDecider decider = new GrayDecider(store, clock);
                String userId = testCase.path("userId").isNull()
                        ? null
                        : testCase.path("userId").asText();

                assertThat(decider.isEnabled("demo-rule", userId))
                        .as(testCase.path("id").asText())
                        .isEqualTo(testCase.path("expectedMatch").asBoolean());
            }
        }
    }

    @Test
    void decisionOrder_shouldFollowTheSharedContractWithoutNormalizingIdentity() throws Exception {
        Path file = tempDir.resolve("gray-rules.local.json");
        Files.writeString(file, rulesDocument(), StandardCharsets.UTF_8);

        try (GrayRuleStore store = new GrayRuleStore(
                new ObjectMapper(), file, 60_000L, "common-test", "instance-test")) {
            assertThat(store.refreshNow()).isTrue();
            Clock clock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
            GrayDecider decider = new GrayDecider(store, clock);

            assertThat(decider.isEnabled("missing-rule", "user-0007")).isFalse();
            assertThat(decider.isEnabled("disabled-rule", "allowlisted-user")).isFalse();
            assertThat(decider.isEnabled("expired-rule", "allowlisted-user")).isFalse();
            assertThat(decider.isEnabled("expires-now-rule", "allowlisted-user")).isFalse();
            assertThat(decider.isEnabled("full-rollout-rule", null)).isFalse();
            assertThat(decider.isEnabled("full-rollout-rule", "")).isFalse();

            assertThat(decider.isEnabled("allowlist-rule", "allowlisted-user")).isTrue();
            assertThat(decider.isEnabled("allowlist-rule", "ALLOWLISTED-USER")).isFalse();
            assertThat(decider.isEnabled("space-rule", " user ")).isTrue();
            assertThat(decider.isEnabled("space-rule", "user")).isFalse();

            assertThat(decider.isEnabled("demo-rule", "user-0007")).isTrue();
            assertThat(decider.isEnabled("demo-rule", "user-0001")).isFalse();
        }
    }

    @Test
    void disabledDecider_shouldAlwaysReturnFalse() {
        GrayDecider decider = GrayDecider.disabled();

        assertThat(decider.isEnabled("any-rule", "any-user")).isFalse();
        assertThat(decider.isEnabled(null, "any-user")).isFalse();
    }

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
