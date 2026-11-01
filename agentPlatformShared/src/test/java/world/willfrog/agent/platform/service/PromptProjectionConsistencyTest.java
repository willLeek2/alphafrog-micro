package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q-09：外置 Prompt 只能是 shared classpath 权威正文的字节级投影。 */
class PromptProjectionConsistencyTest {

    private static final Set<Path> CLASSPATH_ONLY = Set.of(
            Path.of("judge/dag_recovery_judge_system.txt"),
            Path.of("python/execute_python_tool_description.txt"),
            Path.of("todo/dag_react_system_default.txt")
    );




    private static Map<Path, Path> regularFiles(Path root) throws IOException {
        Map<Path, Path> files = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> files.put(root.relativize(path), path));
        }
        return files;
    }

    private static Set<Path> difference(Set<Path> left, Set<Path> right) {
        Set<Path> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String stripFilePrefix(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.startsWith("file://")) {
            return raw.substring("file://".length()).trim();
        }
        if (raw.startsWith("file:")) {
            return raw.substring("file:".length()).trim();
        }
        if (raw.startsWith("@file:")) {
            return raw.substring("@file:".length()).trim();
        }
        return raw;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agentPlatformShared"))
                    && Files.isDirectory(current.resolve("agentLangchainService"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
