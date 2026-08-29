package world.willfrog.alphafrogmicro.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 覆盖层 {@code agent-prompt-overlay} 的预检词表，不是 {@code agent-llm} 整份配置。
 *
 * <p>权威词表新增字段必须同步本索引。索引和权威源之间的漂移靠一致性测试发现，启动时不会因此拦住服务。</p>
 */
public final class PromptHotPushIndex {

    private static final String RESOURCE =
            "world/willfrog/alphafrogmicro/common/config/prompt-hot-push-index.json";
    private static final PromptHotPushIndex SHARED = loadFromClasspath();

    private final String configType;
    private final String dataId;
    private final String group;
    private final List<String> textFields;
    private final List<String> pathFields;
    private final List<String> toolNames;
    private final Map<String, List<String>> placeholders;

    PromptHotPushIndex(String configType,
                       String dataId,
                       String group,
                       List<String> textFields,
                       List<String> pathFields,
                       List<String> toolNames,
                       Map<String, List<String>> placeholders) {
        this.configType = configType;
        this.dataId = dataId;
        this.group = group;
        this.textFields = List.copyOf(textFields);
        this.pathFields = List.copyOf(pathFields);
        this.toolNames = List.copyOf(toolNames);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        placeholders.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        this.placeholders = Map.copyOf(copy);
    }

    public static PromptHotPushIndex shared() {
        return SHARED;
    }

    public String configType() {
        return configType;
    }

    public String dataId() {
        return dataId;
    }

    public String group() {
        return group;
    }

    public List<String> textFields() {
        return textFields;
    }

    public List<String> pathFields() {
        return pathFields;
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public Map<String, List<String>> placeholders() {
        return placeholders;
    }

    /** 覆盖层 prompts 只接受权威正文字段，不含文件路径投影。 */
    public Set<String> allowedPromptKeys() {
        return new LinkedHashSet<>(textFields);
    }

    public Set<String> allowedToolNames() {
        return new LinkedHashSet<>(toolNames);
    }

    public List<String> requiredPlaceholders(String fieldName) {
        return placeholders.getOrDefault(fieldName, List.of());
    }

    static PromptHotPushIndex loadFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PromptHotPushIndex.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到 Prompt 覆盖预检索引: " + RESOURCE);
            }
            Map<String, Object> raw = new ObjectMapper().readValue(in, new TypeReference<>() { });
            raw.remove("_comment");
            @SuppressWarnings("unchecked")
            List<String> textFields = (List<String>) raw.get("textFields");
            @SuppressWarnings("unchecked")
            List<String> pathFields = (List<String>) raw.getOrDefault("pathFields", List.of());
            @SuppressWarnings("unchecked")
            List<String> toolNames = (List<String>) raw.getOrDefault("toolNames", List.of());
            @SuppressWarnings("unchecked")
            Map<String, List<String>> placeholders = (Map<String, List<String>>) raw.getOrDefault(
                    "placeholders", Map.of());
            return new PromptHotPushIndex(
                    String.valueOf(raw.get("configType")),
                    String.valueOf(raw.get("dataId")),
                    String.valueOf(raw.get("group")),
                    new ArrayList<>(textFields),
                    new ArrayList<>(pathFields == null ? List.of() : pathFields),
                    new ArrayList<>(toolNames == null ? List.of() : toolNames),
                    new LinkedHashMap<>(placeholders));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 覆盖预检索引无法读取: " + e.getMessage(), e);
        }
    }
}
