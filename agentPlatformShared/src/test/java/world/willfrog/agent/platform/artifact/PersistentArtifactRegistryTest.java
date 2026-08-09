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
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D22-5.1.3 registry 契约测试：钉住 codex 裁决 f0ee72cb §6 必测项。
 *
 * <ul>
 *   <li>①registry 新制品可 list/download —— {@link #explicitRegistrationShouldBeListedAndReadable}</li>
 *   <li>②旧 ID 可读（legacy AgentContext 入口兼容）—— {@link #legacyRegisterShouldStillBeReadable}</li>
 *   <li>③同一 logical artifact 多次 list 不重复 —— {@link #idempotentRegistrationShouldReuseSameArtifactId}
 *       / {@link #externalIdempotentShouldReuseSameIdAndNotCleanupPath}</li>
 *   <li>④跨 run/user 拒绝 —— {@link #crossRunAndUserOwnershipShouldBeRejected}</li>
 *   <li>⑤双 legacy 冲突启动失败 —— 归 AgentStoragePathsTest（K3 slice），此处不重复</li>
 *   <li>⑥路径逃逸拒绝 —— {@link #externalPathEscapeShouldBeRejected}</li>
 *   <li>⑦过期清理同删 meta + run index —— {@link #cleanupShouldDeleteMetaFileAndIndexEntries}</li>
 * </ul>
 *
 * <p>Redis 用内存 fake（value/hash/set 三张表），文件落 @TempDir；不碰生产 DB/Redis/Nacos。</p>
 */
class PersistentArtifactRegistryTest {

    private static final String RUN_LIST_PREFIX = "agent:persistent-artifact:run-list:";
    private static final String RUN_IDENTITY_PREFIX = "agent:persistent-artifact:run-identity:";

    @TempDir
    Path tempDir;

    private Map<String, String> values;
    private Map<String, Map<String, String>> hashes;
    private Map<String, Set<String>> sets;
    private PersistentArtifactRegistry registry;
    private Path artifactRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        values = new LinkedHashMap<>();
        hashes = new LinkedHashMap<>();
        sets = new LinkedHashMap<>();
        StringRedisTemplate redisTemplate = mockRedis();
        artifactRoot = tempDir.resolve("artifacts");
        datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                artifactRoot.toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(redisTemplate, new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 5);
        AgentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    // ===== ① 新制品可 list/download =====

    @Test
    void explicitRegistrationShouldBeListedAndReadable() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-1", "user-1", "python_script", "script-1", "脚本", "print(1)", 6);

        List<PersistentArtifactMeta> listed = registry.listByRunId("run-1");
        assertEquals(1, listed.size());
        PersistentArtifactMeta meta = listed.get(0);
        assertEquals(registration.getArtifactId(), meta.getArtifactId());
        assertEquals("run-1", meta.getRunId());
        assertEquals("user-1", meta.getUserId());
        assertEquals("script-1", meta.getLogicalId());
        assertFalse(Boolean.TRUE.equals(meta.getExternal()));

        // download 面：readContent 返回原文（路径哈希校验在 readPath 内）
        assertEquals("print(1)", registry.readContent(registration.getArtifactId()));
        assertTrue(Files.exists(Path.of(meta.getPath())));
    }

    // ===== ② 旧 ID 可读（legacy 入口兼容） =====

    @Test
    void legacyRegisterShouldStillBeReadable() {
        AgentContext.setRunId("run-legacy");
        AgentContext.setUserId("user-legacy");
        PersistentArtifactRegistration registration =
                registry.register("raw-ref", "tool-1", "工具输出", "legacy-payload");

        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        assertEquals("run-legacy", meta.getRunId());
        assertEquals("user-legacy", meta.getUserId());
        assertEquals("legacy-payload", registry.readContent(registration.getArtifactId()));
        // legacy 注册同样进 run 索引（list 面统一）
        assertEquals(1, registry.listByRunId("run-legacy").size());
    }

    @Test
    void legacyRegisterWithoutContextShouldStillWork() {
        // 历史兼容：无 AgentContext 时 runId/userId 落 null，不进索引但按 ID 可读。
        PersistentArtifactRegistration registration =
                registry.register("raw-ref", "tool-1", "工具输出", "no-context");
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        assertFalse(meta.getRunId() != null);
        assertEquals("no-context", registry.readContent(registration.getArtifactId()));
        assertTrue(registry.listByRunId("run-none").isEmpty());
    }

    // ===== ③ 同一 logical artifact 多次注册不重复 =====

    @Test
    void idempotentRegistrationShouldReuseSameArtifactId() throws Exception {
        PersistentArtifactRegistration first = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-dup", "脚本", "v1", 6);
        PersistentArtifactRegistration second = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-dup", "脚本", "v2", 6);
        PersistentArtifactRegistration third = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-dup", "脚本", "v3", 6);

        assertEquals(first.getArtifactId(), second.getArtifactId());
        assertEquals(first.getArtifactId(), third.getArtifactId());
        // 重复注册零重写：内容保持首次写入值
        assertEquals("v1", registry.readContent(first.getArtifactId()));
        // run 索引恰一项，type 目录下恰一文件
        assertEquals(1, registry.listByRunId("run-1").size());
        try (var paths = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, paths.count());
        }
        // 不同 logicalId 仍各自独立
        PersistentArtifactRegistration other = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-other", "脚本2", "x", 6);
        assertFalse(other.getArtifactId().equals(first.getArtifactId()));
        assertEquals(2, registry.listByRunId("run-1").size());
    }

    @Test
    void externalIdempotentShouldReuseSameIdAndNotCleanupPath() throws Exception {
        Files.createDirectories(datasetRoot.resolve("ds-1"));
        Path csv = Files.writeString(datasetRoot.resolve("ds-1").resolve("ds-1.csv"), "col\n1\n");

        PersistentArtifactRegistration first = registry.registerExternalIdempotent(
                "run-1", "user-1", "dataset", "ds-1", "数据集", csv, 6);
        PersistentArtifactRegistration second = registry.registerExternalIdempotent(
                "run-1", "user-1", "dataset", "ds-1", "数据集", csv, 6);

        assertEquals(first.getArtifactId(), second.getArtifactId());
        PersistentArtifactMeta meta = registry.find(first.getArtifactId()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(meta.getExternal()));
        // 幂等 external 固定 cleanupPath=false：引用制品清理不动底层文件
        assertFalse(Boolean.TRUE.equals(meta.getCleanupPath()));
        assertEquals(1, registry.listByRunId("run-1").size());
    }

    @Test
    void idempotentRegistrationShouldRequireRunId() {
        assertThrows(IllegalArgumentException.class, () -> registry.registerIdempotent(
                null, "user-1", "python_script", "s", "脚本", "v", 6));
        assertThrows(IllegalArgumentException.class, () -> registry.registerIdempotent(
                " ", "user-1", "python_script", "s", "脚本", "v", 6));
    }

    // ===== ④ 跨 run/user 拒绝 =====

    @Test
    void crossRunAndUserOwnershipShouldBeRejected() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-1", "user-1", "python_script", "s", "脚本", "secret", 6);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();

        assertTrue(PersistentArtifactRegistry.matchesOwner(meta, "run-1", "user-1"));
        assertFalse(PersistentArtifactRegistry.matchesOwner(meta, "run-2", "user-1"), "跨 run 必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwner(meta, "run-1", "user-2"), "跨 user 必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwner(meta, "run-2", "user-2"));
        assertFalse(PersistentArtifactRegistry.matchesOwner(null, "run-1", "user-1"));

        // meta 侧缺上下文（历史制品）：按边宽容，只有值的一侧参与校验
        PersistentArtifactRegistration noContext = registry.registerExplicit(
                null, null, "raw-ref", "t", "旧制品", "old", 6);
        PersistentArtifactMeta legacyMeta = registry.find(noContext.getArtifactId()).orElseThrow();
        assertTrue(PersistentArtifactRegistry.matchesOwner(legacyMeta, "run-any", "user-any"));

        // run 索引隔离：别的 run 列不到 run-1 的制品
        assertTrue(registry.listByRunId("run-2").isEmpty());
    }

    // ===== ⑥ 路径逃逸拒绝 =====

    @Test
    void externalPathEscapeShouldBeRejected() throws Exception {
        // 批准根之外 → SecurityException
        Path outside = Files.writeString(tempDir.resolve("outside.csv"), "col\n");
        assertThrows(SecurityException.class, () -> registry.registerExternalExplicit(
                "run-1", "user-1", "dataset", "ds-x", "数据集", outside, 6, false));
        assertThrows(SecurityException.class, () -> registry.registerExternalIdempotent(
                "run-1", "user-1", "dataset", "ds-x", "数据集", outside, 6));

        // traversal：datasetRoot/../ 归一化后越根
        Path traversal = datasetRoot.resolve("..").resolve("escape.csv");
        assertThrows(SecurityException.class, () -> registry.registerExternalExplicit(
                "run-1", "user-1", "dataset", "ds-y", "数据集", traversal, 6, false));

        // symlink 逃逸：link 在 datasetRoot 内，真实目标在根外 → 拒绝
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "top-secret");
        Files.createDirectories(datasetRoot);
        Path link = datasetRoot.resolve("sneaky-link");
        Files.createSymbolicLink(link, secret);
        assertThrows(SecurityException.class, () -> registry.registerExternalExplicit(
                "run-1", "user-1", "dataset", "ds-z", "数据集", link, 6, false));

        // 零落盘：拒绝后 run 索引与 meta 均无记录
        assertTrue(registry.listByRunId("run-1").isEmpty());
    }

    @Test
    void externalPathInsideApprovedRootsShouldBeAccepted() throws Exception {
        Files.createDirectories(datasetRoot.resolve("ds-ok"));
        Path csv = Files.writeString(datasetRoot.resolve("ds-ok").resolve("data.csv"), "a\n1\n");

        PersistentArtifactRegistration registration = registry.registerExternalExplicit(
                "run-1", "user-1", "dataset", "ds-ok", "数据集", csv, 6, false);

        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        assertEquals(csv.toAbsolutePath().normalize().toString(), meta.getPath());
        assertEquals(4L, meta.getSizeBytes());
    }

    // ===== ⑦ 过期清理同删 meta + run index =====

    @Test
    void cleanupShouldDeleteMetaFileAndIndexEntries() throws Exception {
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-expire", "脚本", "gone", 6);
        String artifactId = registration.getArtifactId();
        String metaKey = "agent:persistent-artifact:" + artifactId;
        Path file = Path.of(registration.getMeta().getPath());
        assertTrue(Files.exists(file));

        // 强制过期：回写 meta 的 expiresAtMillis 到过去
        PersistentArtifactMeta meta = registry.find(artifactId).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        values.put(metaKey, new ObjectMapper().writeValueAsString(meta));

        registry.cleanupExpiredArtifacts();

        assertFalse(values.containsKey(metaKey), "meta 必须删除");
        assertFalse(Files.exists(file), "文件必须删除");
        assertTrue(sets.getOrDefault(RUN_LIST_PREFIX + "run-1", Set.of()).isEmpty(),
                "run 索引项必须同删");
        assertTrue(hashes.getOrDefault(RUN_IDENTITY_PREFIX + "run-1", Map.of()).isEmpty(),
                "幂等身份字段必须同删");

        // 同 logicalId 过期后可重新注册（身份已清，不产生悬挂指向）
        PersistentArtifactRegistration reborn = registry.registerIdempotent(
                "run-1", "user-1", "python_script", "script-expire", "脚本", "reborn", 6);
        assertFalse(reborn.getArtifactId().equals(artifactId));
        assertEquals("reborn", registry.readContent(reborn.getArtifactId()));
    }

    // ===== 有界索引 =====

    @Test
    void runListIndexShouldBeBounded() {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 2);
        PersistentArtifactRegistration first = registry.registerExplicit(
                "run-cap", "user-1", "raw-ref", "a", "1", "one", 6);
        registry.registerExplicit("run-cap", "user-1", "raw-ref", "b", "2", "two", 6);
        PersistentArtifactRegistration overflow = registry.registerExplicit(
                "run-cap", "user-1", "raw-ref", "c", "3", "three", 6);

        // 超限制品不进索引，但 meta 仍在、按 ID 可访问（索引是有界加速结构，不是权威）
        assertEquals(2, registry.listByRunId("run-cap").size());
        assertNotNull(registry.find(overflow.getArtifactId()).orElse(null));
        assertEquals("three", registry.readContent(overflow.getArtifactId()));
        assertNotNull(registry.find(first.getArtifactId()).orElse(null));
    }

    @Test
    void listByRunIdShouldFilterStaleIndexEntries() {
        registry.registerExplicit("run-stale", "user-1", "raw-ref", "a", "1", "one", 6);
        // 陈旧索引项：指向不存在的 artifactId（如外部直接改过索引）
        sets.computeIfAbsent(RUN_LIST_PREFIX + "run-stale", k -> new LinkedHashSet<>())
                .add("raw-ref:ghost");

        List<PersistentArtifactMeta> listed = registry.listByRunId("run-stale");
        assertEquals(1, listed.size(), "陈旧索引项必须被滤掉而不是让 list 失败");
    }

    @Test
    void cleanupShouldSkipIndexKeysMatchingMetaScanPattern() throws Exception {
        // run 索引/身份键与 meta 共享前缀，cleanup SCAN 会命中：必须显式跳过、不误删。
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                "run-skip", "user-1", "python_script", "s", "脚本", "keep", 6);
        String metaKey = "agent:persistent-artifact:" + registration.getArtifactId();
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        String expiredJson = new ObjectMapper().writeValueAsString(meta);
        values.put(metaKey, expiredJson);
        // 索引键与 meta 共享前缀且误存了可解析 JSON：cleanup 必须跳过、不得按 meta 处理
        values.put(RUN_LIST_PREFIX + "run-skip", expiredJson);
        values.put(RUN_IDENTITY_PREFIX + "run-skip", expiredJson);

        registry.cleanupExpiredArtifacts();

        // 真 meta 被清，索引键原样保留（即使它们误存了可解析 JSON 也不得按 meta 处理）
        assertFalse(values.containsKey(metaKey));
        assertTrue(values.containsKey(RUN_LIST_PREFIX + "run-skip"));
        assertTrue(values.containsKey(RUN_IDENTITY_PREFIX + "run-skip"));
    }

    // ===== fake redis =====

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(valueOps.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> values.remove(invocation.getArgument(0)) != null)
                .when(template).delete(org.mockito.ArgumentMatchers.anyString());
        // SCAN 返回三张表全部键（模拟真实 Redis 中索引键也会被 META_PREFIX* 命中）
        when(template.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class)))
                .thenAnswer(invocation -> {
                    Set<String> all = new LinkedHashSet<>(values.keySet());
                    all.addAll(hashes.keySet());
                    all.addAll(sets.keySet());
                    return new SetCursor(all.iterator());
                });

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

    private static class SetCursor implements Cursor<String> {
        private final Iterator<String> iterator;

        private SetCursor(Iterator<String> iterator) {
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
