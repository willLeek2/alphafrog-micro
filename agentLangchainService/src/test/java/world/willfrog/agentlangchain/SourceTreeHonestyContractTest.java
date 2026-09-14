package world.willfrog.agentlangchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTreeHonestyContractTest {

    private final Path moduleRoot = Path.of(System.getProperty("user.dir"));

    @Test
    void mainSourceTree_shouldNotContainTrackedBackupSources() throws Exception {
        try (var paths = Files.walk(moduleRoot.resolve("src/main/java"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".bak")).toList())
                    .isEmpty();
        }
    }

    @Test
    void pocDirectories_shouldDeclareNonProductionBoundary() {
        assertThat(moduleRoot.resolve("src/agentic-poc/README.md")).exists();
        assertThat(moduleRoot.resolve("src/subagent-poc/README.md")).exists();
    }
}
