package world.willfrog.alphafrogmicro.common.deployment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentGenerationIdTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String IMAGE_A = "registry.local/a@sha256:" + "a".repeat(64);
    private static final String IMAGE_B = "registry.local/b@sha256:" + "b".repeat(64);

    @Test
    void serviceOrderDoesNotChangeGeneration() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("service-b", IMAGE_B);
        first.put("service-a", IMAGE_A);
        Map<String, String> second = new LinkedHashMap<>();
        second.put("service-a", IMAGE_A);
        second.put("service-b", IMAGE_B);

        assertEquals(
                DeploymentGenerationId.compute(7, COMMIT, first),
                DeploymentGenerationId.compute(7, COMMIT, second));
        assertEquals(
                "gen-f790b524a00bac28c4db1b7e63765be68a978170f184441a8d54ea3b36a9f26b",
                DeploymentGenerationId.compute(7, COMMIT, first));
    }

    @Test
    void everyGenerationInputChangesIdentity() {
        String original = DeploymentGenerationId.compute(7, COMMIT, Map.of("service-a", IMAGE_A));
        assertNotEquals(original,
                DeploymentGenerationId.compute(8, COMMIT, Map.of("service-a", IMAGE_A)));
        assertNotEquals(original,
                DeploymentGenerationId.compute(7, "1123456789abcdef0123456789abcdef01234567",
                        Map.of("service-a", IMAGE_A)));
        assertNotEquals(original,
                DeploymentGenerationId.compute(7, COMMIT, Map.of("service-a", IMAGE_B)));
    }

    @Test
    void rejectsMutableOrIncompleteInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGenerationId.compute(1, "short", Map.of("service-a", IMAGE_A)));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGenerationId.compute(1, COMMIT, Map.of("service-a", "repo:latest")));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGenerationId.compute(1, COMMIT, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGenerationId.compute(
                        9_007_199_254_740_992L, COMMIT, Map.of("service-a", IMAGE_A)));
    }

    @Test
    void acceptsTheLongestServiceNameAllowedByTheDeploymentManifest() {
        String serviceName = "s" + "a".repeat(95);
        assertEquals(68, DeploymentGenerationId.compute(
                1, COMMIT, Map.of(serviceName, IMAGE_A)).length());
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGenerationId.compute(
                        1, COMMIT, Map.of(serviceName + "a", IMAGE_A)));
    }

    @Test
    void matchesRepositoryContractVector() throws Exception {
        Path vectorPath = findRepositoryRoot()
                .resolve("deploy/agent-run/deployment-generation-test-vector.json");
        Map<String, Object> vector = new ObjectMapper().readValue(
                vectorPath.toFile(), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, String> images = (Map<String, String>) vector.get("serviceImages");

        assertEquals(vector.get("deploymentGenerationId"), DeploymentGenerationId.compute(
                ((Number) vector.get("manifestVersion")).longValue(),
                (String) vector.get("gitCommit"),
                images));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("deploy"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
