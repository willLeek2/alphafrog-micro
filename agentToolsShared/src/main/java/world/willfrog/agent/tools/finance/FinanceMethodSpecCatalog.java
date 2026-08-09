package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 加载 classpath 中的 canonical MethodSpec JSON 与索引，启动时复算摘要并校验。
 *
 * <p>只读构建产物 {@code finance/method-specs/v1/}，不回读 YAML。摘要不一致时 fail-fast，
 * 避免运行时使用与构建期不一致的方法定义。</p>
 */
@Component
@Slf4j
public class FinanceMethodSpecCatalog {

    private static final String INDEX_PATH = "finance/method-specs/v1/index.json";
    private static final String SPEC_PREFIX = "finance/method-specs/v1/";

    private final ObjectMapper objectMapper;
    private final Map<String, FinanceMethodSpec> specsByIdentity;

    public FinanceMethodSpecCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.specsByIdentity = loadCatalog();
    }

    /**
     * 按方法三元组（methodId + version + specDigest）查找规范。
     */
    public Optional<FinanceMethodSpec> find(String methodId, String version, String specDigest) {
        return Optional.ofNullable(specsByIdentity.get(identityKey(methodId, version, specDigest)));
    }

    /**
     * 按 methodId 查找任意版本（目录内通常只有一个）。
     */
    public Optional<FinanceMethodSpec> findByMethodId(String methodId) {
        return specsByIdentity.values().stream()
                .filter(s -> s.getMethodId().equals(methodId))
                .findFirst();
    }

    /**
     * 返回按 methodId 排序后的全部规范。
     */
    public List<FinanceMethodSpec> listAll() {
        return specsByIdentity.values().stream()
                .sorted(Comparator.comparing(FinanceMethodSpec::getMethodId))
                .collect(Collectors.toList());
    }

    private Map<String, FinanceMethodSpec> loadCatalog() {
        String indexJson = readClasspath(INDEX_PATH);
        if (indexJson == null || indexJson.isBlank()) {
            throw new IllegalStateException("Finance method-spec index not found on classpath: " + INDEX_PATH);
        }
        try {
            List<IndexEntry> entries = objectMapper.readValue(indexJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, IndexEntry.class));
            Map<String, FinanceMethodSpec> result = new LinkedHashMap<>();
            for (IndexEntry entry : entries) {
                String resourcePath = SPEC_PREFIX + baseName(entry.methodId()) + ".json";
                String canonicalJson = readClasspath(resourcePath);
                if (canonicalJson == null || canonicalJson.isBlank()) {
                    throw new IllegalStateException("Finance method-spec canonical JSON missing: " + resourcePath);
                }
                String recomputedDigest = computeDigest(canonicalJson);
                if (!recomputedDigest.equals(entry.specDigest())) {
                    throw new IllegalStateException(String.format(
                            "Finance method-spec digest mismatch for %s@%s: index=%s, recomputed=%s",
                            entry.methodId(), entry.version(), entry.specDigest(), recomputedDigest));
                }
                FinanceMethodSpec spec = parseCanonical(canonicalJson);
                if (!spec.getMethodId().equals(entry.methodId())
                        || !spec.getVersion().equals(entry.version())
                        || !spec.getSpecDigest().equals(entry.specDigest())) {
                    throw new IllegalStateException(String.format(
                            "Finance method-spec identity mismatch: index=%s@%s/%s, canonical=%s@%s/%s",
                            entry.methodId(), entry.version(), entry.specDigest(),
                            spec.getMethodId(), spec.getVersion(), spec.getSpecDigest()));
                }
                result.put(identityKey(entry.methodId(), entry.version(), entry.specDigest()), spec);
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse finance method-spec index", e);
        }
    }

    private String computeDigest(String canonicalJson) {
        try {
            // 按 L144 不变式与协议 3.5：摘要对不含自身摘要字段的 canonical 字节求 SHA-256。
            JsonNode node = objectMapper.readTree(canonicalJson);
            if (node.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("specDigest");
            }
            // 使用 TreeMap 排序保证序列化顺序稳定，与构建插件保持一致。
            Object sorted = objectMapper.convertValue(node, TreeMap.class);
            ObjectMapper stableMapper = new ObjectMapper();
            stableMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] bytes = stableMapper.writeValueAsBytes(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute specDigest", e);
        }
    }

    @SuppressWarnings("unchecked")
    private FinanceMethodSpec parseCanonical(String canonicalJson) throws IOException {
        Map<String, Object> root = objectMapper.readValue(canonicalJson, LinkedHashMap.class);

        FinanceMethodSpec.FinanceMethodSpecBuilder builder = FinanceMethodSpec.builder()
                .schemaVersion(str(root.get("schemaVersion")))
                .methodId(str(root.get("methodId")))
                .version(str(root.get("version")))
                .displayName(str(root.get("displayName")))
                .definition(str(root.get("definition")))
                .specDigest(str(root.get("specDigest")));

        Object hints = root.get("resolverHints");
        if (hints instanceof Map) {
            Map<String, Object> hm = (Map<String, Object>) hints;
            builder.resolverHints(FinanceMethodSpec.FinanceResolverHints.builder()
                    .aliases(listOfString(hm.get("aliases")))
                    .commonPhrases(listOfString(hm.get("commonPhrases")))
                    .clarificationDimensions(parseDimensions(hm.get("clarificationDimensions")))
                    .build());
        }

        Object params = root.get("parameters");
        if (params instanceof Map) {
            Map<String, Object> pm = (Map<String, Object>) params;
            Map<String, FinanceMethodSpec.FinanceParameter> parameterMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : pm.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (value instanceof Map) {
                    Map<String, Object> vd = (Map<String, Object>) value;
                    parameterMap.put(key, FinanceMethodSpec.FinanceParameter.builder()
                            .name(key)
                            .type(str(vd.get("type")))
                            .required(bool(vd.get("required")))
                            .defaultValue(vd.get("default"))
                            .minimum(vd.get("minimum"))
                            .maximum(vd.get("maximum"))
                            .enumValues(listOfObject(vd.get("enum")))
                            .meaning(str(vd.get("meaning")))
                            .description(str(vd.get("description")))
                            .build());
                }
            }
            builder.parameters(parameterMap);
        }

        Object conventions = root.get("conventions");
        if (conventions instanceof Map) {
            builder.conventions(deepStringObjectMap(conventions));
        }
        Object extensions = root.get("extensions");
        if (extensions instanceof Map) {
            builder.extensions(deepStringObjectMap(extensions));
        }

        Object outputs = root.get("outputs");
        if (outputs instanceof List) {
            builder.outputs(((List<Map<String, Object>>) outputs).stream()
                    .map(o -> FinanceMethodSpec.FinanceOutput.builder()
                            .name(str(o.get("name")))
                            .unit(str(o.get("unit")))
                            .description(str(o.get("description")))
                            .displayFormat(str(o.get("displayFormat")))
                            .build())
                    .collect(Collectors.toList()));
        }

        Object lib = root.get("libraryBinding");
        if (lib instanceof Map) {
            Map<String, Object> lm = (Map<String, Object>) lib;
            builder.libraryBinding(FinanceMethodSpec.FinanceLibraryBinding.builder()
                    .packageName(str(lm.get("package")))
                    .function(str(lm.get("function")))
                    .apiCompatRange(str(lm.get("apiCompatRange")))
                    .build());
        }

        builder.sourceRefs(listOfString(root.get("sourceRefs")));

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> deepStringObjectMap(Object value) {
        Map<String, Object> map = (Map<String, Object>) value;
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (e.getValue() instanceof Map) {
                result.put(e.getKey(), (Map<String, Object>) e.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<FinanceMethodSpec.ClarificationDimension> parseDimensions(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return ((List<Map<String, Object>>) value).stream()
                .map(d -> FinanceMethodSpec.ClarificationDimension.builder()
                        .id(str(d.get("id")))
                        .question(str(d.get("question")))
                        .build())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfString(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return ((List<Object>) value).stream().map(String::valueOf).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Object> listOfObject(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList((List<Object>) value);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private String readClasspath(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read classpath resource: " + path, e);
        }
    }

    private static String baseName(String methodId) {
        int idx = methodId.lastIndexOf('.');
        return idx < 0 ? methodId : methodId.substring(idx + 1);
    }

    private static String identityKey(String methodId, String version, String specDigest) {
        return methodId + "@" + version + "@" + specDigest;
    }

    /**
     * 索引项 DTO，仅用于启动时反序列化。
     */
    public record IndexEntry(String methodId, String version, String specDigest, String resourcePath) {
    }
}
