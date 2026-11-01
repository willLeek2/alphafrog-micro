package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunDeploymentIdentityMigrationContractTest {




    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("migrate"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
