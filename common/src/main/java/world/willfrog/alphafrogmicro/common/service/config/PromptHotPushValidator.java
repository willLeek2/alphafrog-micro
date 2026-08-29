package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.common.config.PromptHotPushIndex;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 Prompt 运行时覆盖文档写入配置中心之前的预检。
 *
 * <p>文档只允许四个顶层字段。字段名和工具名必须能在预检词表里对上；正文不能空、不能是文件引用；
 * 权威模板里声明过的占位符必须还在，额外占位符可以加。预检失败直接拒绝，避免坏版本覆盖正在用的版本。</p>
 */
public final class PromptHotPushValidator {

    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of(
            "formatVersion", "baseBundleDigest", "prompts", "toolDescriptions");
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
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!ALLOWED_ROOT_KEYS.contains(field)) {
                throw new ConfigValidationException("覆盖文档含未知顶层字段: " + field);
            }
        }
        JsonNode formatVersion = root.get("formatVersion");
        if (formatVersion == null || formatVersion.isNull()
                || !formatVersion.isIntegralNumber() || formatVersion.asLong() != 1L) {
            throw new ConfigValidationException("formatVersion 必须是整数 1");
        }
        if (root.has("baseBundleDigest")) {
            JsonNode digest = root.get("baseBundleDigest");
            if (digest == null || digest.isNull() || !digest.isTextual()) {
                throw new ConfigValidationException("baseBundleDigest 必须是字符串");
            }
        }
        validateObjectMap("prompts", root.get("prompts"), index.allowedPromptKeys(), true);
        validateObjectMap("toolDescriptions", root.get("toolDescriptions"), index.allowedToolNames(), false);
    }

    public List<String> diffPromptFields(String fromJson, String toJson) {
        return diffObjectKeys(objectNode(fromJson, "prompts"), objectNode(toJson, "prompts"));
    }

    public List<String> diffToolDescriptions(String fromJson, String toJson) {
        return diffObjectKeys(objectNode(fromJson, "toolDescriptions"), objectNode(toJson, "toolDescriptions"));
    }

    public List<String> diffOverlayFields(String fromJson, String toJson) {
        Set<String> changed = new LinkedHashSet<>();
        changed.addAll(diffPromptFields(fromJson, toJson));
        changed.addAll(diffToolDescriptions(fromJson, toJson));
        return new ArrayList<>(changed);
    }

    /**
     * 覆盖文档的稳定摘要。空正文、空对象、缺 prompts / toolDescriptions 都按空覆盖层计算。
     */
    public String canonicalDigest(String contentJson) {
        return sha256(canonicalOverlay(contentJson));
    }

    private void validateObjectMap(String section, JsonNode node, Set<String> allowed, boolean checkPlaceholders) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new ConfigValidationException(section + " 必须是对象");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                throw new ConfigValidationException(section + " 含未知字段，与权威索引不一致: " + field);
            }
            validateBody(field, node.get(field), checkPlaceholders);
        }
    }

    private void validateBody(String field, JsonNode value, boolean checkPlaceholders) {
        if (value == null || value.isNull()) {
            throw new ConfigValidationException(field + " 为空");
        }
        if (!value.isTextual()) {
            throw new ConfigValidationException(field + " 必须是文本正文");
        }
        String text = value.asText();
        if (text.isBlank()) {
            throw new ConfigValidationException(field + " 为空");
        }
        if (looksLikeFileReference(text)) {
            throw new ConfigValidationException(field + " 不能是文件引用");
        }
        if ("toolCapabilityCatalog".equals(field)) {
            JsonNode catalog;
            try {
                catalog = objectMapper.readTree(text);
            } catch (Exception e) {
                throw new ConfigValidationException("toolCapabilityCatalog 不是合法 JSON: " + e.getMessage());
            }
            if (catalog == null || !catalog.isObject() || catalog.size() == 0) {
                throw new ConfigValidationException("toolCapabilityCatalog 必须是非空 JSON 对象");
            }
        }
        if (!checkPlaceholders) {
            return;
        }
        for (String placeholder : index.requiredPlaceholders(field)) {
            String token = "{{" + placeholder + "}}";
            if (!text.contains(token)) {
                throw new ConfigValidationException(field + " 缺少占位符 " + token);
            }
        }
    }

    private String canonicalOverlay(String contentJson) {
        JsonNode root = parseLenient(contentJson);
        StringBuilder canonical = new StringBuilder();
        appendSortedEntries(canonical, objectChild(root, "prompts"), "");
        appendSortedEntries(canonical, objectChild(root, "toolDescriptions"), "toolDescription:");
        return canonical.toString();
    }

    private void appendSortedEntries(StringBuilder canonical, JsonNode object, String keyPrefix) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        for (String name : names) {
            JsonNode value = object.get(name);
            if (value == null || !value.isTextual()) {
                continue;
            }
            canonical.append(keyPrefix).append(name).append('\n')
                    .append(value.asText()).append('\n');
        }
    }

    private List<String> diffObjectKeys(JsonNode from, JsonNode to) {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        from.fieldNames().forEachRemaining(names::add);
        to.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode left = from.get(name);
            JsonNode right = to.get(name);
            if (left == null || right == null || !left.equals(right)) {
                changed.add(name);
            }
        }
        return new ArrayList<>(changed);
    }

    private JsonNode objectNode(String json, String field) {
        return objectChild(parseLenient(json), field);
    }

    private JsonNode objectChild(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return objectMapper.createObjectNode();
        }
        JsonNode child = root.get(field);
        return child != null && child.isObject() ? child : objectMapper.createObjectNode();
    }

    private JsonNode parseLenient(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject() ? root : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean looksLikeFileReference(String text) {
        String value = text.trim();
        return value.startsWith("file:") || value.startsWith("file://") || value.startsWith("@file:");
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ConfigValidationException("覆盖文档摘要计算失败");
        }
    }
}
