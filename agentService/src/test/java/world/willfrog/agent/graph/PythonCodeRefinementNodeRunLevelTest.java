package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 260623-harness-optimization-03: PythonCodeRefinementNode 的 run_args 清洗路径必须保留
 * manifest_ids，不能把模型生成的 manifest 编号静默丢掉。
 */
class PythonCodeRefinementNodeRunLevelTest {

    private final PythonCodeRefinementNode node = new PythonCodeRefinementNode(
            null, new ObjectMapper(), null, null, null, null, null
    );

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeRunArgs_shouldPreserveManifestIds() {
        Map<String, Object> raw = Map.of(
                "dataset_ids", "1,3",
                "manifest_ids", "2",
                "libraries", "pandas",
                "timeout_seconds", 60
        );
        Map<String, Object> out = ReflectionTestUtils.invokeMethod(node, "sanitizeRunArgs", raw);
        assertNotNull(out);
        assertEquals("1,3", out.get("dataset_ids"));
        assertEquals("2", out.get("manifest_ids"));
        assertEquals("pandas", out.get("libraries"));
        assertEquals(60, out.get("timeout_seconds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeRunArgs_shouldNormalizeManifestIdAliases() {
        Map<String, Object> raw = Map.of(
                "manifestId", "5",
                "manifest_id", "7"
        );
        Map<String, Object> out = ReflectionTestUtils.invokeMethod(node, "sanitizeRunArgs", raw);
        assertNotNull(out);
        // manifest_id should win because firstNonBlank checks manifest_ids, manifestIds, manifest_id, manifestId
        assertEquals("7", out.get("manifest_ids"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeRunArgs_shouldDropInvalidManifestIdValues() {
        Map<String, Object> raw = Map.of(
                "manifest_ids", "../etc/passwd"
        );
        Map<String, Object> out = ReflectionTestUtils.invokeMethod(node, "sanitizeRunArgs", raw);
        assertNotNull(out);
        assertFalse(out.containsKey("manifest_ids"), "非法 manifest id 应被过滤");
    }
}
