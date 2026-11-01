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
