package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.common.config.PromptHotPushIndex;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigValidationException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Prompt 覆盖版本写入 Nacos 之前的预检。
 *
 * <p>检查三项：字段必须能在权威索引里对上；正文不能空；权威模板声明过的占位符必须还在。
 * 以 {@code file:} / {@code file://} / {@code @file:} 开头的值按路径投影处理，只检查非空，不按占位符拒绝。
 * 预检失败直接拒绝，避免坏版本覆盖正在用的版本。</p>
 */
public final class PromptHotPushValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}");
    private static final PromptHotPushValidator SHARED = new PromptHotPushValidator(PromptHotPushIndex.shared());

    private final PromptHotPushIndex index;
    private final ObjectMapper objectMapper;

    public PromptHotPushValidator() {
        this(PromptHotPushIndex.shared());
    }

    PromptHotPushValidator(PromptHotPushIndex index) {
        this.index = index;
        this.objectMapper = new ObjectMapper();
    }

    public static PromptHotPushValidator shared() {
        return SHARED;
    }

    public PromptHotPushIndex index() {
        return index;
    }

    public boolean appliesTo(String typeName) {
        return index.configType().equals(typeName);
    }

    public void validateContentJson(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new ConfigValidationException("覆盖版本正文为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(contentJson);
        } catch (Exception e) {
            throw new ConfigValidationException("覆盖版本不是合法 JSON: " + e.getMessage());
        }
        validateRoot(root);
    }

    public void validateRoot(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ConfigValidationException("覆盖版本必须是 JSON 对象");
        }
        JsonNode prompts = root.get("prompts");
        if (prompts == null || prompts.isNull()) {
            return;
        }
        if (!prompts.isObject()) {
            throw new ConfigValidationException("prompts 必须是对象");
        }
        Set<String> allowed = index.allowedPromptKeys();
        Iterator<String> names = prompts.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                throw new ConfigValidationException("prompts 含未知字段，与权威索引不一致: " + field);
            }
            validateField(field, prompts.get(field));
        }
    }

    public List<String> diffPromptFields(String fromJson, String toJson) {
        Set<String> changed = new LinkedHashSet<>();
        JsonNode fromPrompts = promptsNode(fromJson);
        JsonNode toPrompts = promptsNode(toJson);
        Set<String> names = new LinkedHashSet<>();
        fromPrompts.fieldNames().forEachRemaining(names::add);
        toPrompts.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode left = fromPrompts.get(name);
            JsonNode right = toPrompts.get(name);
            if (left == null || right == null || !left.equals(right)) {
                changed.add(name);
            }
        }
        return new ArrayList<>(changed);
    }

    private JsonNode promptsNode(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode prompts = root.path("prompts");
            return prompts.isObject() ? prompts : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private void validateField(String field, JsonNode value) {
        if (value == null || value.isNull()) {
            throw new ConfigValidationException(field + " 为空");
        }
        if (index.pathFields().contains(field)) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new ConfigValidationException(field + " 的文件路径为空");
            }
            return;
        }
        if (!value.isTextual()) {
            throw new ConfigValidationException(field + " 必须是文本正文");
        }
        String text = value.asText();
        if (text.isBlank()) {
            throw new ConfigValidationException(field + " 为空");
        }
        if (looksLikeFileReference(text)) {
            return;
        }
        if ("toolCapabilityCatalog".equals(field)) {
            try {
                objectMapper.readTree(text);
            } catch (Exception e) {
                throw new ConfigValidationException("toolCapabilityCatalog 不是合法 JSON: " + e.getMessage());
            }
        }
        for (String placeholder : index.requiredPlaceholders(field)) {
            String token = "{{" + placeholder + "}}";
            if (!text.contains(token)) {
                throw new ConfigValidationException(field + " 缺少占位符 " + token);
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        Set<String> allowed = new LinkedHashSet<>(index.requiredPlaceholders(field));
        while (matcher.find()) {
            String found = matcher.group(1);
            if (!allowed.contains(found)) {
                throw new ConfigValidationException(field + " 含未登记占位符 {{" + found + "}}");
            }
        }
    }

    private boolean looksLikeFileReference(String text) {
        String value = text.trim();
        return value.startsWith("file:") || value.startsWith("file://") || value.startsWith("@file:");
    }
}
