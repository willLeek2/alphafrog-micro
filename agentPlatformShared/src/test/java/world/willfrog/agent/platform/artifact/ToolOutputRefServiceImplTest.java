package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolOutputRefServiceImplTest {

    @TempDir
    Path tempDir;

    private Map<String, String> redis;
    private StringRedisTemplate redisTemplate;
    private PersistentArtifactRegistry registry;
    private ToolOutputRefServiceImpl service;

    @BeforeEach
    void setUp() {
        redis = new LinkedHashMap<>();
        redisTemplate = mockRedis(redis);
        // D04：artifact 根经统一存储门面注入（替代原 @Value artifactRoot 反射注入）。
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                tempDir.resolve("datasets").toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(redisTemplate, new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getReread().setMaxLimit(8);
        cfg.getTools().getRawRef().setTtlHours(1);
        when(loader.current()).thenReturn(Optional.of(cfg));
        service = new ToolOutputRefServiceImpl(registry, Optional.of(loader));
        AgentContext.clear();
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void readShouldPageAndFilterWithinCurrentRun() {
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出",
                "alpha\nbeta\ngamma\nalphabet");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 0, 100, "alpha");

        assertEquals("alpha\nal", result.getContent());
        assertTrue(result.isHasMore());
        assertEquals(8, result.getNextOffset());
    }

    @Test
    void readShouldRejectCrossRunRawRef() {
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出", "secret");

        AgentContext.setRunId("run-2");

        assertThrows(IllegalArgumentException.class, () -> service.read(registration.getArtifactId(), 0, 10, null));
    }

    @Test
    void rebindFromLocatorShouldCreateCurrentRunRawRef() {
        PersistentArtifactRegistration first = service.registerRawOutput("tool-1", "工具输出", "payload");
        RawPayloadLocator locator = service.locatorFor(first.getArtifactId());
        AgentContext.setRunId("run-2");

        PersistentArtifactRegistration rebound = service.rebindFromLocator("tool-1", "工具输出", locator);

        assertEquals("run-2", rebound.getMeta().getRunId());
        assertEquals("payload", service.read(rebound.getArtifactId(), 0, 100, null).getContent());
    }

    @Test
    void cleanupExpiredArtifactsShouldDeleteOwnedFileAndMeta() throws Exception {
        PersistentArtifactRegistration registration = registry.register("raw-ref", "tool-1", "工具输出", "payload", 1);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        redis.put("agent:persistent-artifact:" + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));
        Path path = Path.of(meta.getPath());

        registry.cleanupExpiredArtifacts();

        assertFalse(Files.exists(path));
        assertFalse(redis.containsKey("agent:persistent-artifact:" + meta.getArtifactId()));
    }

    @Test
    void cleanupExpiredArtifactsShouldDeleteExternalSymlinkOnlyWhenMarked() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("dataset-target"));
        Path link = tempDir.resolve("dataset-link");
        Files.createSymbolicLink(link, target);
        PersistentArtifactRegistration registration = registry.registerExternal(
                "dataset-symlink", "dataset-1", "compat_symlink", link, 1, true);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        redis.put("agent:persistent-artifact:" + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));

        registry.cleanupExpiredArtifacts();

        assertFalse(Files.exists(link));
        assertTrue(Files.exists(target));
    }

    @Test
    void readLocatorShouldRejectPathTraversalOutsideRoot() {
        RawPayloadLocator locator = RawPayloadLocator.builder()
                .path(tempDir.resolve("outside.txt").toString())
                .contentHash("hash")
                .build();

        assertThrows(IllegalArgumentException.class, () -> registry.readLocator(locator));
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis(Map<String, String> store) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        when(ops.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> store.remove(invocation.getArgument(0)) != null)
                .when(template).delete(org.mockito.ArgumentMatchers.anyString());
        when(template.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class)))
                .thenAnswer(invocation -> new MapCursor(store.keySet().iterator()));
        return template;
    }

    private static class MapCursor implements Cursor<String> {
        private final Iterator<String> iterator;

        private MapCursor(Iterator<String> iterator) {
            this.iterator = iterator;
        }

        @Override
        public void close() {
        }

        @Override
        public long getCursorId() {
            return 0;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public long getPosition() {
            return 0;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public String next() {
            return iterator.next();
        }
    }
}
