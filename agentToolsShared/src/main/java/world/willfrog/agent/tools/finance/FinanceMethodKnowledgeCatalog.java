package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读取构建生成的金融方法知识目录，把 methodId/version/specDigest 映射到文档章节锚点。
 */
@Component
@Slf4j
public class FinanceMethodKnowledgeCatalog {

    private static final String INDEX_PATH = "finance/method-knowledge/v1/index.json";

    private final Map<String, KnowledgeEntry> entriesByIdentity;

    public FinanceMethodKnowledgeCatalog(ObjectMapper objectMapper) {
        this.entriesByIdentity = loadIndex(objectMapper);
    }

    /**
     * 按方法三元组查找知识文档章节。
     *
     * @return 若存在返回 document 路径与 section 锚点，否则返回空。
     */
    public Optional<KnowledgeEntry> resolve(String methodId, String version, String specDigest) {
        return Optional.ofNullable(entriesByIdentity.get(identityKey(methodId, version, specDigest)));
    }

    private Map<String, KnowledgeEntry> loadIndex(ObjectMapper objectMapper) {
        String raw = readClasspath(INDEX_PATH);
        if (raw == null || raw.isBlank()) {
            log.warn("Finance method-knowledge index not found on classpath: {}", INDEX_PATH);
            return Collections.emptyMap();
        }
        try {
            List<IndexEntry> entries = objectMapper.readValue(raw, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, IndexEntry.class));
            Map<String, KnowledgeEntry> result = new HashMap<>();
            for (IndexEntry e : entries) {
                result.put(identityKey(e.methodId(), e.version(), e.specDigest()),
                        new KnowledgeEntry(e.document(), e.section()));
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException ex) {
            log.error("Failed to parse finance method-knowledge index", ex);
            return Collections.emptyMap();
        }
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

    private static String identityKey(String methodId, String version, String specDigest) {
        return methodId + "@" + version + "@" + specDigest;
    }

    /**
     * 知识条目：文档路径与章节锚点。
     */
    public record KnowledgeEntry(String document, String section) {
    }

    private record IndexEntry(String methodId, String version, String specDigest, String document, String section) {
    }
}
