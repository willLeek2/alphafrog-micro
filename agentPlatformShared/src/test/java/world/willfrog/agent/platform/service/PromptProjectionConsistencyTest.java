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

    @Test
    void externalProjection_shouldMatchAuthorityByteForByte() throws Exception {
        Path root = repositoryRoot();
        Path authorityRoot = root.resolve("agentPlatformShared/src/main/resources/prompts");
        Path projectionRoot = root.resolve("agentLangchainService/config/prompts");
        Map<Path, Path> authority = regularFiles(authorityRoot);
        Map<Path, Path> projection = regularFiles(projectionRoot);

        assertFalse(projection.isEmpty(), "外置 Prompt 投影不能为空");
        assertEquals(CLASSPATH_ONLY, difference(authority.keySet(), projection.keySet()),
                "新增 classpath-only 文件必须说明为什么不属于外置投影");
        assertTrue(authority.keySet().containsAll(projection.keySet()),
                "外置投影不得出现 shared 权威树之外的文件");

        for (Map.Entry<Path, Path> entry : projection.entrySet()) {
            Path authorityFile = authority.get(entry.getKey());
            assertArrayEquals(Files.readAllBytes(authorityFile), Files.readAllBytes(entry.getValue()),
                    "外置投影漂移: " + entry.getKey());
        }
    }

    @Test
    void localExample_shouldReferenceRealAuthorityProjectionsOnly() throws Exception {
        Path root = repositoryRoot();
        Path authorityRoot = root.resolve("agentPlatformShared/src/main/resources/prompts");
        Path projectionRoot = root.resolve("agentLangchainService/config/prompts");
        JsonNode prompts = new ObjectMapper().readTree(Files.readString(
                root.resolve("agentLangchainService/config/agent-llm.local.example.json"))).path("prompts");

        assertTrue(prompts.isObject(), "local 示例必须包含 prompts 对象");
        Set<Path> referenced = new LinkedHashSet<>();
        prompts.fields().forEachRemaining(entry -> {
            assertTrue(entry.getValue().isTextual(), "示例 Prompt 字段只能保存投影文件引用: " + entry.getKey());
            String reference = stripFilePrefix(entry.getValue().asText());
            assertFalse(reference.isBlank(), "示例 Prompt 文件引用不能为空: " + entry.getKey());
            Path relative = Path.of(reference).normalize();
            assertFalse(relative.isAbsolute() || relative.startsWith(".."),
                    "示例 Prompt 文件引用必须留在 config 目录: " + entry.getKey());
            referenced.add(relative.startsWith("prompts") ? relative.subpath(1, relative.getNameCount()) : relative);
        });

        assertEquals(33, referenced.size(), "示例 Prompt 投影引用数量变化时必须复核映射");
        for (Path relative : referenced) {
            assertTrue(Files.isRegularFile(authorityRoot.resolve(relative)),
                    "示例引用缺少 shared 权威文件: " + relative);
            assertTrue(Files.isRegularFile(projectionRoot.resolve(relative)),
                    "示例引用缺少外置投影文件: " + relative);
        }
    }

    @Test
    void dagReactFallback_shouldEqualCanonicalAuthority() throws Exception {
        Path root = repositoryRoot();
        Path promptRoot = root.resolve("agentPlatformShared/src/main/resources/prompts/todo");
        assertArrayEquals(
                Files.readAllBytes(promptRoot.resolve("dag_react_system.txt")),
                Files.readAllBytes(promptRoot.resolve("dag_react_system_default.txt")),
                "同一 DAG ReAct 语义不能在 classpath 内再分叉");
    }

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
