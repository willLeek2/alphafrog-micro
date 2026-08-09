package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private Map<String, Map<String, String>> hashStore;
    private Map<String, Set<String>> setStore;
    private StringRedisTemplate redisTemplate;
    private PersistentArtifactRegistry registry;
    private ToolOutputRefServiceImpl service;

    @BeforeEach
    void setUp() {
        redis = new LinkedHashMap<>();
        hashStore = new LinkedHashMap<>();
        setStore = new LinkedHashMap<>();
        redisTemplate = mockRedis(redis, hashStore, setStore);
        // D04：artifact 根经统一存储门面注入（替代原 @Value artifactRoot 反射注入）。
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                tempDir.resolve("datasets").toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(redisTemplate, new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 1000);
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
    void explicitContextOverloadsShouldBypassAgentContext() {
        // D22-5.1.3：显式 overload 不读 AgentContext——线程态是别的 run 也能注册/读取目标 run。
        PersistentArtifactRegistration registration =
                service.registerRawOutput("run-x", "user-x", "tool-x", "工具输出", "explicit-payload");
        assertEquals("run-x", registration.getMeta().getRunId());
        assertEquals("user-x", registration.getMeta().getUserId());

        // 当前线程态仍是 run-1/user-1：显式 overload 读取 run-x 不受影响
        // （setUp maxLimit=8 截顶：16 字符 payload 只返回前 8 字符，hasMore=true）
        ToolOutputReadResult explicitRead = service.read("run-x", "user-x",
                registration.getArtifactId(), 0, 100, null);
        assertEquals("explicit", explicitRead.getContent());
        assertTrue(explicitRead.isHasMore());
        // 旧入口语义不变：AgentContext(run-1) 读 run-x 的 ref 仍被拒
        assertThrows(IllegalArgumentException.class,
                () -> service.read(registration.getArtifactId(), 0, 10, null));
    }

    @Test
    void explicitReadShouldRejectWhenCallerContextMissing() {
        // D22-5.1.3 MUST-FIX ③：显式入口严格校验——调用方任一值为空即拒（fail-closed）
        PersistentArtifactRegistration registration =
                service.registerRawOutput("run-x", "user-x", "tool-x", "工具输出", "payload");
        String rawRef = registration.getArtifactId();

        assertThrows(IllegalArgumentException.class,
                () -> service.read(null, "user-x", rawRef, 0, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.read("run-x", " ", rawRef, 0, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.locatorFor(null, "user-x", rawRef));
    }

    @Test
    void legacyReadShouldStayLenientForContextlessArtifactWhileExplicitRejects() {
        // D22-5.1.3 MUST-FIX ③：legacy seam 宽容（历史无上下文制品仍可读），显式入口严格拒绝
        AgentContext.clear();
        PersistentArtifactRegistration registration =
                service.registerRawOutput("tool-legacy", "旧输出", "legacy-payload");
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");

        ToolOutputReadResult legacyRead = service.read(registration.getArtifactId(), 0, 100, null);
        // setUp 的 reread maxLimit=8 把 limit 截顶到 8 字符，故期望前 8 字符而非全文。
        assertEquals("legacy-p", legacyRead.getContent());

        assertThrows(IllegalArgumentException.class, () -> service.read(
                "run-1", "user-1", registration.getArtifactId(), 0, 100, null));
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
        // D22-5.1.3：cleanup 同删 run 索引项
        assertTrue(setStore.getOrDefault("agent:persistent-artifact:run-list:run-1", Set.of()).isEmpty());
    }

    @Test
    void cleanupExpiredArtifactsShouldDeleteExternalSymlinkOnlyWhenMarked() throws Exception {
        // D22-5.1.3：external 路径只能落批准根内——target 与 link 都放 datasetRoot 下。
        Path datasetRoot = tempDir.resolve("datasets");
        Path target = Files.createDirectories(datasetRoot.resolve("dataset-target"));
        Path link = datasetRoot.resolve("dataset-link");
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
    private StringRedisTemplate mockRedis(Map<String, String> store,
                                          Map<String, Map<String, String>> hashes,
                                          Map<String, Set<String>> sets) {
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

        // D22-5.1.3：registry 新增幂等身份 hash 与 run 索引 SET，fake 同步扩展。
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);
        when(hashOps.putIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> hashes
                        .computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashMap<>())
                        .putIfAbsent(invocation.getArgument(1).toString(),
                                invocation.getArgument(2).toString()) == null);
        org.mockito.Mockito.doAnswer(invocation -> {
            hashes.computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashMap<>())
                    .put(invocation.getArgument(1).toString(), invocation.getArgument(2).toString());
            return null;
        }).when(hashOps).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(hashOps.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Map<String, String> h = hashes.get(invocation.getArgument(0));
                    return h == null ? null : h.get(invocation.getArgument(1).toString());
                });
        when(hashOps.delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Map<String, String> h = hashes.get(invocation.getArgument(0));
                    return h == null ? 0L : (h.remove(invocation.getArgument(1).toString()) != null ? 1L : 0L);
                });

        // D22-5.1.3 MUST-FIX：registry 值条件 HDEL 走 Lua execute()，fake 以同步块模拟原子 CAS。
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            java.util.List<String> keys = (java.util.List<String>) args[1];
            String field = String.valueOf(args[2]);
            String expected = String.valueOf(args[3]);
            Map<String, String> h = hashes.get(keys.get(0));
            if (h == null) {
                return 0L;
            }
            synchronized (h) {
                if (expected.equals(h.get(field))) {
                    h.remove(field);
                    return 1L;
                }
                return 0L;
            }
        }).when(template).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(template.opsForSet()).thenReturn(setOps);
        when(setOps.add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String>any()))
                .thenAnswer(invocation -> sets
                        .computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashSet<>())
                        .add(invocation.getArgument(1)) ? 1L : 0L);
        when(setOps.members(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s == null ? new HashSet<>() : new HashSet<>(s);
                });
        when(setOps.size(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s == null ? 0L : (long) s.size();
                });
        when(setOps.remove(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s != null && s.remove(invocation.getArgument(1).toString()) ? 1L : 0L;
                });
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
