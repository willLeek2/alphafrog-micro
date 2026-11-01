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
