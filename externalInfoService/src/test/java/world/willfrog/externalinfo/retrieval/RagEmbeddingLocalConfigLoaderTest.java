package world.willfrog.externalinfo.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEmbeddingLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void laneProcess_shouldReadIsolatedCacheAndIgnoreSharedPath() throws Exception {
        Path shared = tempDir.resolve("rag-embedding.local.json");
        Path isolated = shared.getParent().resolve("stage3-dag-0922.rag-embedding.local.json");
        Files.writeString(shared, "{\"model\":\"shared-model\",\"dimensions\":8}", StandardCharsets.UTF_8);
        Files.writeString(isolated, "{\"model\":\"lane-model\",\"dimensions\":16}", StandardCharsets.UTF_8);

        RagEmbeddingLocalConfigLoader loader = new RagEmbeddingLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", shared.toString());
        ReflectionTestUtils.setField(loader, "laneTrafficScopeId", "stage3-dag-0922");

        loader.applyNacosWrittenFile(shared.toString());
        assertTrue(loader.current().isEmpty(), "共用路径上的主环境缓存不得被泳道加载器当成覆盖");

        loader.applyNacosWrittenFile(isolated.toString());
        assertEquals("lane-model", loader.current().orElseThrow().getModel());
        assertEquals(16, loader.current().orElseThrow().getDimensions());
        loader.refresh();
        assertEquals("lane-model", loader.current().orElseThrow().getModel());
    }

    @Test
    void snakeCaseApplicationMapper_stillBindsCamelCaseBaseUrlAndApiKey() throws Exception {
        Path configFile = tempDir.resolve("rag-embedding.local.json");
        Files.writeString(configFile,
                "{\"baseUrl\":\"https://embed.example\",\"apiKey\":\"secret-key\",\"model\":\"text-embedding-3-small\"}",
                StandardCharsets.UTF_8);

        ObjectMapper snake = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        RagEmbeddingLocalConfigLoader loader = new RagEmbeddingLocalConfigLoader(snake);
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());

        loader.load();

        assertEquals("https://embed.example", loader.current().orElseThrow().getBaseUrl());
        assertEquals("secret-key", loader.current().orElseThrow().getApiKey());
        assertEquals("text-embedding-3-small", loader.current().orElseThrow().getModel());
    }
}
