package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.tools.dataset.DatasetRegistry.ManifestMeta;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManifestRegistryTest {

    @TempDir
    Path tempDir;

    private DatasetRegistry registry;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private Map<String, String> redisStore;

    @BeforeEach
    void setUp() {
        registry = new DatasetRegistry(mock(StringRedisTemplate.class));
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(mock(SetOperations.class));

        objectMapper = new ObjectMapper();
        redisStore = new HashMap<>();

        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return redisStore.get(key);
        });
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisStore.put(key, value);
            return null;
        }).when(valueOps).set(anyString(), anyString());
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            redisStore.remove(key);
            return null;
        }).when(redisTemplate).delete(anyString());

        ReflectionTestUtils.setField(registry, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(registry, "datasetPath", tempDir.toString());
        ReflectionTestUtils.setField(registry, "enabled", true);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 3600L);
        ReflectionTestUtils.setField(registry, "scanCount", 100);
    }

    @Test
    void registerManifest_shouldPersistMetaInRedis() {
        List<String> tsCodes = List.of("000001.SZ", "000300.SH");
        List<String> columns = List.of("close", "trade_date");
        registry.registerManifest("stock_daily", "20240101", "20240131", tsCodes, columns,
                "manifest-stock_daily-20240101-20240131-abc12345", 2, 2, 0, 100);

        assertTrue(redisStore.keySet().stream().anyMatch(k -> k.startsWith("manifest:meta:")),
                "registerManifest 应写入 manifest:meta:* 键；实际 keys=" + redisStore.keySet());
    }

    @Test
    void findReusableManifest_shouldReturnEmptyWhenNotRegistered() {
        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), List.of("close"));
        assertTrue(result.isEmpty());
    }

    @Test
    void findReusableManifest_shouldReturnEmptyWhenDisabled() {
        ReflectionTestUtils.setField(registry, "enabled", false);
        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), List.of("close"));
        assertTrue(result.isEmpty());
    }

    @Test
    void findReusableManifest_shouldReturnEmptyOnIncompleteArgs() {
        assertTrue(registry.findReusableManifest(null, "20240101", "20240131",
                List.of("x"), List.of("close")).isEmpty());
        assertTrue(registry.findReusableManifest("stock_daily", "", "20240131",
                List.of("x"), List.of("close")).isEmpty());
        assertTrue(registry.findReusableManifest("stock_daily", "20240101", "",
                List.of("x"), List.of("close")).isEmpty());
    }

    @Test
    void findReusableManifest_shouldReturnEmptyWhenManifestFilesMissing() {
        List<String> tsCodes = List.of("000001.SZ");
        List<String> columns = List.of("close");
        String manifestId = "manifest-stock_daily-20240101-20240131-deadbeef";
        registry.registerManifest("stock_daily", "20240101", "20240131", tsCodes, columns,
                manifestId, 1, 1, 0, 10);

        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                tsCodes, columns);
        assertTrue(result.isEmpty(), "manifest 文件缺失应清理 meta 并返回 empty");
        assertTrue(redisStore.values().stream().noneMatch(v -> v.contains(manifestId)),
                "缺失文件的 manifest meta 已被清理");
    }

    @Test
    void findReusableManifest_shouldReturnMetaWhenFilesExist() throws Exception {
        List<String> tsCodes = List.of("000001.SZ");
        List<String> columns = List.of("close");
        String manifestId = "manifest-stock_daily-20240101-20240131-abc12345";
        registry.registerManifest("stock_daily", "20240101", "20240131", tsCodes, columns,
                manifestId, 1, 1, 0, 10);

        Path dir = tempDir.resolve(manifestId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(manifestId + ".manifest.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(manifestId + ".meta.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);

        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                tsCodes, columns);
        assertTrue(result.isPresent(), "文件齐备应返回 meta");
        assertEquals(manifestId, result.get().getManifestId());
    }

    @Test
    void findReusableManifest_shouldNotDependOnTsCodeInputOrder() throws Exception {
        List<String> orderA = List.of("000001.SZ", "000002.SZ");
        List<String> orderB = List.of("000002.SZ", "000001.SZ");
        List<String> columns = List.of("close");
        String manifestId = "manifest-stock_daily-20240101-20240131-cafebabe";

        registry.registerManifest("stock_daily", "20240101", "20240131", orderA, columns,
                manifestId, 2, 2, 0, 20);

        Path dir = tempDir.resolve(manifestId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(manifestId + ".manifest.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(manifestId + ".meta.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);

        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                orderB, columns);
        assertTrue(result.isPresent(), "tsCodes 顺序差异应命中同一 query key");
        assertEquals(manifestId, result.get().getManifestId());
    }

    @Test
    void findReusableManifest_shouldCleanupWhenExpired() throws Exception {
        List<String> tsCodes = List.of("000001.SZ");
        List<String> columns = List.of("close");
        String manifestId = "manifest-stock_daily-20240101-20240131-expired01";
        registry.registerManifest("stock_daily", "20240101", "20240131", tsCodes, columns,
                manifestId, 1, 1, 0, 10);

        Path dir = tempDir.resolve(manifestId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(manifestId + ".manifest.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(manifestId + ".meta.json"),
                "{\"manifestId\":\"" + manifestId + "\"}", StandardCharsets.UTF_8);

        for (String key : new ArrayList<>(redisStore.keySet())) {
            if (key.startsWith("manifest:meta:")) {
                String json = redisStore.get(key);
                ManifestMeta m = objectMapper.readValue(json, ManifestMeta.class);
                m.setExpireAt(0L);
                redisStore.put(key, objectMapper.writeValueAsString(m));
            }
        }

        Optional<ManifestMeta> result = registry.findReusableManifest("stock_daily", "20240101", "20240131",
                tsCodes, columns);
        assertTrue(result.isEmpty(), "过期 manifest 应被清理并返回 empty");
        assertTrue(redisStore.values().stream().noneMatch(v -> v.contains(manifestId)),
                "过期 manifest meta 应被清理");
    }

    @Test
    void registerManifest_shouldSkipWhenDisabled() {
        ReflectionTestUtils.setField(registry, "enabled", false);
        registry.registerManifest("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), List.of("close"),
                "manifest-stock_daily-20240101-20240131-disabled", 1, 1, 0, 1);
        assertTrue(redisStore.isEmpty(), "禁用状态下 registerManifest 不写 Redis");
    }

    @Test
    void registerManifest_shouldSkipWhenManifestIdNull() {
        registry.registerManifest("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), List.of("close"),
                null, 1, 1, 0, 1);
        assertTrue(redisStore.isEmpty(), "manifestId 为空时不写 Redis");
    }
}
