package world.willfrog.agent.tools.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class LoadToolGuideTool {

    private static final String DATA_DIR = "/data/agent_guides";
    private static final String CLASSPATH_PREFIX = "/agent_guides/";
    private static final Set<String> VALID_TOPICS = Set.of(
            "python_sandbox",
            "dataset_manifest",
            "advanced_market_data",
            "execute_python_tips",
            "finance_method_knowledge"
    );

    private final ObjectMapper objectMapper;

    public LoadToolGuideTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool("""
        加载平台工具指南文档。只读，不消耗外部配额。

        参数：
          topic - 指南主题，必填。可选值：
            python_sandbox      - Python 沙箱环境与 af_dataset_loader 用法
            dataset_manifest    - dataset_manifest 结构与 partial failure 处理
            advanced_market_data - searchAssetInfo/searchIndex advanced 模式与日期语义
            execute_python_tips - executePython 常见陷阱与模板
            finance_method_knowledge - 金融方法规范、调用约定与常见反例

        返回：{ ok, data: { topic, content, source_path }, error }。
        """)
    public String loadToolGuide(@P(value = "指南主题，必填：python_sandbox|dataset_manifest|advanced_market_data|execute_python_tips|finance_method_knowledge", required = true) String topic) {
        try {
            String normalized = topic == null ? "" : topic.trim();
            if (normalized.isBlank() || !VALID_TOPICS.contains(normalized)) {
                return fail("INVALID_TOPIC",
                        "topic must be one of " + VALID_TOPICS,
                        Map.of("topic", normalized));
            }

            String fileName = normalized + ".md";
            String content = readFromDataDir(fileName);
            String sourcePath = Paths.get(DATA_DIR, fileName).toString();
            if (content == null) {
                content = readFromClasspath(fileName);
                sourcePath = CLASSPATH_PREFIX + fileName;
            }
            if (content == null || content.isBlank()) {
                return fail("GUIDE_NOT_FOUND",
                        "Guide not found for topic: " + normalized,
                        Map.of("topic", normalized));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("topic", normalized);
            data.put("content", content);
            data.put("source_path", sourcePath);
            return ok(data);
        } catch (Exception e) {
            log.error("loadToolGuide failed: topic={}", topic, e);
            return fail("TOOL_ERROR", "Failed to load guide: " + nvl(e.getMessage()), Map.of());
        }
    }

    private String readFromDataDir(String fileName) {
        Path path = Paths.get(DATA_DIR, fileName);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read guide from {}", path, e);
            return null;
        }
    }

    private String readFromClasspath(String fileName) {
        try (InputStream stream = getClass().getResourceAsStream(CLASSPATH_PREFIX + fileName)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read guide from classpath: {}{}", CLASSPATH_PREFIX, fileName, e);
            return null;
        }
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "loadToolGuide");
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "loadToolGuide");
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"loadToolGuide\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"" + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
