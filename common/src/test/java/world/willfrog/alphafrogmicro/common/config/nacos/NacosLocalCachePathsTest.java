package world.willfrog.alphafrogmicro.common.config.nacos;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NacosLocalCachePathsTest {

    @Test
    void isolate_shouldKeepOriginalPath_whenScopeBlank() {
        assertEquals("/app/config-dynamic/agent-llm.local.json",
                NacosLocalCachePaths.isolate("/app/config-dynamic/agent-llm.local.json", null));
        assertEquals("/app/config-dynamic/agent-llm.local.json",
                NacosLocalCachePaths.isolate("/app/config-dynamic/agent-llm.local.json", "  "));
        assertNull(NacosLocalCachePaths.isolate(null, "lane-demo"));
        assertEquals("", NacosLocalCachePaths.isolate("", "lane-demo"));
    }

    @Test
    void isolate_shouldPrefixFilenameAndKeepParentDirectory() {
        String isolated = NacosLocalCachePaths.isolate(
                "/app/config-dynamic/agent-llm.local.json", "stage3-dag-0922");
        assertEquals("/app/config-dynamic/stage3-dag-0922.agent-llm.local.json", isolated);
        assertEquals(Path.of("/app/config-dynamic"), Path.of(isolated).getParent());
    }

    @Test
    void isolate_shouldNotPrefixTwice() {
        String once = NacosLocalCachePaths.isolate(
                "/app/config-dynamic/agent-llm.local.json", "lane-demo");
        assertEquals("/app/config-dynamic/lane-demo.agent-llm.local.json", once);
        assertEquals(once, NacosLocalCachePaths.isolate(once, "lane-demo"));
    }

    @Test
    void isolate_shouldKeepRelativeParent() {
        Path configured = Path.of("data", "agent-llm.local.json");
        assertEquals(Path.of("data", "lane-demo.agent-llm.local.json").toString(),
                NacosLocalCachePaths.isolate(configured.toString(), "lane-demo"));
    }
}
