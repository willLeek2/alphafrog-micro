package world.willfrog.alphafrogmicro.domestic.fetch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FetchJobsConfigTest {

    @Test
    void refreshScheduleShouldDefaultToTenSeconds() throws Exception {
        Scheduled scheduled = FetchJobsConfig.class
                .getDeclaredMethod("refresh")
                .getAnnotation(Scheduled.class);

        assertEquals("${af.fetch.jobs.config-refresh-interval-ms:10000}", scheduled.fixedDelayString());
    }

    @Test
    void laneProcess_shouldReadIsolatedCacheAndIgnoreSharedPath(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("fetch-jobs.json");
        Path isolated = shared.getParent().resolve("stage3-dag-0922.fetch-jobs.json");
        Files.writeString(shared, "{\"fetch\":{\"concurrency\":1}}", StandardCharsets.UTF_8);
        Files.writeString(isolated, "{\"fetch\":{\"concurrency\":9}}", StandardCharsets.UTF_8);

        FetchJobsConfig loader = new FetchJobsConfig();
        ReflectionTestUtils.setField(loader, "configFilePath", shared.toString());
        ReflectionTestUtils.setField(loader, "laneTrafficScopeId", "stage3-dag-0922");

        loader.applyNacosWrittenFile(shared.toString());
        assertEquals(3, loader.getConcurrency(), "共用路径上的主环境缓存不得被泳道加载器当成覆盖");

        loader.applyNacosWrittenFile(isolated.toString());
        assertEquals(9, loader.getConcurrency());
        loader.refresh();
        assertEquals(9, loader.getConcurrency());
    }
}
