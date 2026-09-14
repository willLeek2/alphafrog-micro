package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrayBucketContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaBucketing_shouldMatchEverySharedVector() throws IOException {
        JsonNode contract = readContract();

        for (JsonNode vector : contract.path("vectors")) {
            int actual = GrayDecider.stableBucket(
                    vector.path("ruleId").asText(),
                    vector.path("bucketSalt").asText(),
                    vector.path("userId").asText());
            assertThat(actual)
                    .as("shared vector %s", vector.path("id").asText())
                    .isEqualTo(vector.path("bucket").asInt());
        }
    }

    @Test
    void wideningPercent_shouldKeepOldMatchesAndMatchSharedSets() throws IOException {
        JsonNode contract = readContract();

        for (JsonNode widening : contract.path("wideningCases")) {
            Set<String> fromMatches = matches(widening, widening.path("fromPercent").asInt());
            Set<String> toMatches = matches(widening, widening.path("toPercent").asInt());

            assertThat(toMatches)
                    .as("expanded match set for %s", widening.path("id").asText())
                    .containsAll(fromMatches);
            assertThat(fromMatches)
                    .containsExactlyInAnyOrderElementsOf(textSet(widening.path("expectedFromMatches")));
            assertThat(toMatches)
                    .containsExactlyInAnyOrderElementsOf(textSet(widening.path("expectedToMatches")));
        }
    }

    @Test
    void ruleVersion_shouldNotParticipateInBucketing() throws IOException {
        JsonNode contract = readContract();

        for (JsonNode testCase : contract.path("versionIndependenceCases")) {
            int first = GrayDecider.stableBucket(
                    testCase.path("ruleId").asText(),
                    testCase.path("bucketSalt").asText(),
                    testCase.path("userId").asText());
            int second = GrayDecider.stableBucket(
                    testCase.path("ruleId").asText(),
                    testCase.path("bucketSalt").asText(),
                    testCase.path("userId").asText());

            assertThat(first).isEqualTo(testCase.path("expectedBucketForBothVersions").asInt());
            assertThat(second).isEqualTo(first);
            assertThat(testCase.path("firstRuleVersion").asText())
                    .isNotEqualTo(testCase.path("secondRuleVersion").asText());
        }
    }

    private Set<String> matches(JsonNode widening, int percent) {
        Set<String> matches = new LinkedHashSet<>();
        for (JsonNode userId : widening.path("userIds")) {
            int bucket = GrayDecider.stableBucket(
                    widening.path("ruleId").asText(),
                    widening.path("bucketSalt").asText(),
                    userId.asText());
            if (bucket < percent) {
                matches.add(userId.asText());
            }
        }
        return matches;
    }

    private Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private JsonNode readContract() throws IOException {
        return objectMapper.readTree(Files.readAllBytes(repositoryFile("deploy/gray/gray-bucket-test-vectors.json")));
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
