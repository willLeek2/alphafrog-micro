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
 * 260623-harness-optimization-03: SubAgentRunner 从子代理计划中提取 Python run_args 时，
 * 必须保留 manifest_ids，不能只落 dataset_ids。
 */
class SubAgentRunnerRunLevelTest {

    private final SubAgentRunner runner = new SubAgentRunner(
            null, null, new ObjectMapper(), null, null, null, null, null, null, null
    );

    @Test
    @SuppressWarnings("unchecked")
    void extractInitialPythonRunArgs_shouldPreserveManifestIds() {
        Map<String, Object> args = Map.of(
                "dataset_ids", "1",
                "manifest_ids", "2",
                "libraries", "numpy",
                "timeout_seconds", 30
        );
        Map<String, Object> runArgs = ReflectionTestUtils.invokeMethod(runner, "extractInitialPythonRunArgs", args);
        assertNotNull(runArgs);
        assertEquals("1", runArgs.get("dataset_ids"));
        assertEquals("2", runArgs.get("manifest_ids"));
        assertEquals("numpy", runArgs.get("libraries"));
        assertEquals(30, runArgs.get("timeout_seconds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractInitialPythonRunArgs_shouldReadManifestIdFromRunArgs() {
        Map<String, Object> args = Map.of(
                "run_args", Map.of("manifest_ids", "3")
        );
        Map<String, Object> runArgs = ReflectionTestUtils.invokeMethod(runner, "extractInitialPythonRunArgs", args);
        assertNotNull(runArgs);
        assertEquals("3", runArgs.get("manifest_ids"));
        assertFalse(runArgs.containsKey("dataset_ids"));
    }
}
