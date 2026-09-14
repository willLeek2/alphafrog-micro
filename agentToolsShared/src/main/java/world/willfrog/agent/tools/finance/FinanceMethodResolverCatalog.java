package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 读取构建生成的 resolver-catalog.json，按稳定顺序构造紧凑目录片段，并暴露目录字节摘要。
 *
 * <p>本类刻意不持有 system prompt 模板：最终 prompt 由 platform 经 AgentPromptService
 * 解析实际模板（含 local override）后把 {@link #getCompactCatalogText()} 片段填入，
 * 避免二次嵌套并保证 snapshot 的 promptVersion 是实际模板的事实。</p>
 */
@Component
@Slf4j
public class FinanceMethodResolverCatalog {

    private static final String CATALOG_PATH = "finance/method-specs/v1/resolver-catalog.json";

    private final ObjectMapper objectMapper;
    private final String catalogDigest;
    private final String compactCatalogText;
    private final List<ResolverCatalogEntry> entries;

    public FinanceMethodResolverCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String rawCatalog = readClasspath(CATALOG_PATH);
        if (rawCatalog == null || rawCatalog.isBlank()) {
            throw new IllegalStateException("Finance resolver catalog not found on classpath: " + CATALOG_PATH);
        }
        this.catalogDigest = sha256(rawCatalog.getBytes(StandardCharsets.UTF_8));
        this.entries = parseCatalog(rawCatalog);
        this.compactCatalogText = renderCompactText(entries);
    }

    /**
     * 返回当前 resolver 目录的 sha256 摘要（原始 resolver-catalog.json 字节）。
     */
    public String getCatalogDigest() {
        return catalogDigest;
    }

    /**
     * 返回按稳定顺序渲染的紧凑目录文本（仅目录片段，供 platform 拼入实际 system prompt 模板）。
     */
    public String getCompactCatalogText() {
        return compactCatalogText;
    }

    /**
     * 返回按 methodId 排序后的目录条目（只读视图）。
     */
    public List<ResolverCatalogEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @SuppressWarnings("unchecked")
    private List<ResolverCatalogEntry> parseCatalog(String rawCatalog) {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(rawCatalog, List.class);
            return list.stream()
                    .map(this::toEntry)
                    .sorted(Comparator.comparing(ResolverCatalogEntry::methodId))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse resolver catalog", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ResolverCatalogEntry toEntry(Map<String, Object> map) {
        return new ResolverCatalogEntry(
                str(map.get("methodId")),
                str(map.get("version")),
                str(map.get("specDigest")),
                str(map.get("displayName")),
                listOfString(map.get("aliases")),
                listOfString(map.get("commonPhrases")),
                parseDimensions(map.get("clarificationDimensions"))
        );
    }

    private String renderCompactText(List<ResolverCatalogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前可用金融方法目录（按 methodId 排序）：\n");
        for (ResolverCatalogEntry e : entries) {
            sb.append("- ").append(e.methodId()).append(" v").append(e.version());
            sb.append(" / ").append(e.displayName()).append("  ").append(e.specDigest()).append("\n");
            if (!e.aliases().isEmpty()) {
                sb.append("  别名：").append(String.join("、", e.aliases())).append("\n");
            }
            if (!e.commonPhrases().isEmpty()) {
                sb.append("  常见说法：").append(String.join("；", e.commonPhrases())).append("\n");
            }
            if (!e.clarificationDimensions().isEmpty()) {
                sb.append("  待澄清维度：").append(e.clarificationDimensions().stream()
                        .map(ClarificationDimension::question)
                        .collect(Collectors.joining("；"))).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfString(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return ((List<Object>) value).stream().map(String::valueOf).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<ClarificationDimension> parseDimensions(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return ((List<Map<String, Object>>) value).stream()
                .map(d -> new ClarificationDimension(str(d.get("id")), str(d.get("question"))))
                .collect(Collectors.toList());
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 解析器目录条目，只包含用于候选判断的最小字段。
     */
    public record ResolverCatalogEntry(
            String methodId,
            String version,
            String specDigest,
            String displayName,
            List<String> aliases,
            List<String> commonPhrases,
            List<ClarificationDimension> clarificationDimensions
    ) {
    }

    public record ClarificationDimension(String id, String question) {
    }
}
