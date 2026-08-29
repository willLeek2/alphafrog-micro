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
 * Prompt 热推送预检用的权威字段索引。
 *
 * <p>配置类型、dataId、group 沿用现有 agent-llm 配置中心登记；覆盖层正文格式若项①另有约定，
 * 审查时按那份初版改这里，不在运行时猜测。</p>
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
    private final Map<String, List<String>> placeholders;

    PromptHotPushIndex(String configType,
                       String dataId,
                       String group,
                       List<String> textFields,
                       List<String> pathFields,
                       Map<String, List<String>> placeholders) {
        this.configType = configType;
        this.dataId = dataId;
        this.group = group;
        this.textFields = List.copyOf(textFields);
        this.pathFields = List.copyOf(pathFields);
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

    public Map<String, List<String>> placeholders() {
        return placeholders;
    }

    public Set<String> allowedPromptKeys() {
        Set<String> keys = new LinkedHashSet<>(textFields);
        keys.addAll(pathFields);
        return keys;
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
                throw new IllegalStateException("找不到 Prompt 热推送索引: " + RESOURCE);
            }
            Map<String, Object> raw = new ObjectMapper().readValue(in, new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<String> textFields = (List<String>) raw.get("textFields");
            @SuppressWarnings("unchecked")
            List<String> pathFields = (List<String>) raw.getOrDefault("pathFields", List.of());
            @SuppressWarnings("unchecked")
            Map<String, List<String>> placeholders = (Map<String, List<String>>) raw.getOrDefault(
                    "placeholders", Map.of());
            return new PromptHotPushIndex(
                    String.valueOf(raw.get("configType")),
                    String.valueOf(raw.get("dataId")),
                    String.valueOf(raw.get("group")),
                    new ArrayList<>(textFields),
                    new ArrayList<>(pathFields),
                    new LinkedHashMap<>(placeholders));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 热推送索引无法读取: " + e.getMessage(), e);
        }
    }
}
