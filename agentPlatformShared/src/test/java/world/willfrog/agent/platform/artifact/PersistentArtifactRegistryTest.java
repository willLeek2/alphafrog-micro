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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D22-5.1.3 registry 契约测试：钉住 codex 裁决 f0ee72cb §6 必测项
 * + MUST-FIX f54454fe 五类反测。
 *
 * <ul>
 *   <li>①registry 新制品可 list/download —— {@link #explicitRegistrationShouldBeListedAndReadable}</li>
 *   <li>②旧 ID 可读（legacy AgentContext 入口兼容）—— {@link #legacyRegisterShouldStillBeReadable}</li>
 *   <li>③同一 logical artifact 多次 list 不重复 —— {@link #idempotentRegistrationShouldReuseSameArtifactId}
 *       / {@link #externalIdempotentShouldReuseSameIdAndNotCleanupPath}</li>
 *   <li>④跨 run/user 拒绝 —— {@link #crossRunAndUserOwnershipShouldBeRejected}
 *       （strict 四值非空且相等 / lenient 仅 legacy seam）</li>
 *   <li>⑤双 legacy 冲突启动失败 —— 归 AgentStoragePathsTest（K3 slice），此处不重复</li>
 *   <li>⑥路径逃逸拒绝 —— {@link #externalPathEscapeShouldBeRejected}</li>
 *   <li>⑦过期清理同删 meta + run index —— {@link #cleanupShouldDeleteMetaFileAndIndexEntries}</li>
 * </ul>
 *
 * <h3>MUST-FIX 反测（五类）</h3>
 * <ul>
 *   <li>①幂等竞态单一赢家（latch/interleaving）——
 *       {@link #concurrentIdempotentRegistrationShouldProduceSingleWinner}
 *       + 陈旧身份确定性窃取 {@link #staleIdentityFieldShouldBeClearedAndRegistrationRetried}</li>
 *   <li>②run 索引原子有界 + overflow 可见性 ——
 *       {@link #runListIndexOverflowShouldRejectRegistrationAndRollBack}
 *       / {@link #nonPositiveRunListCapShouldFailClosed}
 *       / {@link #concurrentRegistrationsShouldNeverOverflowRunListCap}</li>
 *   <li>③归属 fail-closed —— {@link #crossRunAndUserOwnershipShouldBeRejected}（null-meta/null-caller/跨 run+user）</li>
 *   <li>④TOCTOU —— {@link #symlinkSwapAfterRegistrationShouldBeRejectedOnRead}
 *       / {@link #contentTamperAfterRegistrationShouldFailHashCheck}</li>
 *   <li>⑤身份 collision-free —— {@link #identityFieldEncodingShouldBeCollisionFree}</li>
 * </ul>
 *
 * <p>Redis 用线程安全内存 fake（ConcurrentHashMap/concurrent set，支持真线程并发测试；
 * 值条件 HDEL 的 Lua execute() 以同步块模拟原子 CAS），文件落 @TempDir；不碰生产 DB/Redis/Nacos。</p>
 */
class PersistentArtifactRegistryTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";
    private static final String RUN_IDENTITY_PREFIX = META_PREFIX + "run-identity:";

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
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        sets = new ConcurrentHashMap<>();
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

        // download 面：readContent 返回原文（TOCTOU 强化读取：realpath 复检 + no-follow + 哈希校验）
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
        // run 索引恰一项，type 目录下恰一文件（输家候选文件必须回滚）
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

    // ===== ④ 跨 run/user 拒绝（strict fail-closed / lenient 仅 legacy seam） =====

    @Test
    void crossRunAndUserOwnershipShouldBeRejected() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-1", "user-1", "python_script", "s", "脚本", "secret", 6);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();

        // 严格 matcher：四值非空且相等才放行
        assertTrue(PersistentArtifactRegistry.matchesOwnerStrict(meta, "run-1", "user-1"));
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(meta, "run-2", "user-1"), "跨 run 必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(meta, "run-1", "user-2"), "跨 user 必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(meta, "run-2", "user-2"));
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(meta, null, "user-1"), "调用方 runId 空必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(meta, "run-1", " "), "调用方 userId 空必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(null, "run-1", "user-1"));

        // meta 侧缺上下文（历史制品）：严格 matcher fail-closed；宽容 matcher 按边放行
        PersistentArtifactRegistration noContext = registry.registerExplicit(
                null, null, "raw-ref", "t", "旧制品", "old", 6);
        PersistentArtifactMeta legacyMeta = registry.find(noContext.getArtifactId()).orElseThrow();
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(legacyMeta, "run-any", "user-any"),
                "meta 侧空值：严格校验必须拒");
        assertTrue(PersistentArtifactRegistry.matchesOwnerLenient(legacyMeta, "run-any", "user-any"),
                "meta 侧空值：宽容校验仅 legacy seam 可用");
        // 宽容 matcher：两侧都有值时同样拒绝跨 run/user
        assertTrue(PersistentArtifactRegistry.matchesOwnerLenient(meta, "run-1", "user-1"));
        assertFalse(PersistentArtifactRegistry.matchesOwnerLenient(meta, "run-2", "user-1"));
        assertFalse(PersistentArtifactRegistry.matchesOwnerLenient(meta, "run-1", "user-2"));
        assertFalse(PersistentArtifactRegistry.matchesOwnerLenient(null, "run-1", "user-1"));

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
        String metaKey = META_PREFIX + artifactId;
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

    // ===== MUST-FIX ②：run 索引原子有界（硬上限、可见失败、fail-closed） =====

    @Test
    void runListIndexOverflowShouldRejectRegistrationAndRollBack() throws Exception {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 2);
        registry.registerExplicit("run-cap", "user-1", "raw-ref", "a", "1", "one", 6);
        registry.registerExplicit("run-cap", "user-1", "raw-ref", "b", "2", "two", 6);

        // 超限 = 注册原子拒绝并回滚（可见失败），禁止 silent meta-only 成功
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit("run-cap", "user-1", "raw-ref", "c", "3", "three", 6));
        assertTrue(e.getMessage().contains("capacity exceeded"), e.getMessage());

        assertEquals(2, registry.listByRunId("run-cap").size());
        // 被拒注册零残留：values 中恰两条 meta，type 目录恰两文件
        long metaCount = values.keySet().stream()
                .filter(k -> k.startsWith(META_PREFIX)
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX))
                .count();
        assertEquals(2, metaCount, "被拒注册不得残留 meta");
        try (var paths = Files.list(artifactRoot.resolve("raw-ref"))) {
            assertEquals(2, paths.count(), "被拒注册不得残留文件");
        }
        // 索引项与 meta 一一对应（无孤儿索引项）
        for (String id : sets.getOrDefault(RUN_LIST_PREFIX + "run-cap", Set.of())) {
            assertTrue(values.containsKey(META_PREFIX + id), "索引项必须有对应 meta");
        }
    }

    @Test
    void nonPositiveRunListCapShouldFailClosed() {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 0);
        IllegalStateException zero = assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit("run-cap0", "user-1", "raw-ref", "a", "1", "one", 6));
        assertTrue(zero.getMessage().contains("must be positive"), zero.getMessage());

        ReflectionTestUtils.setField(registry, "maxRunListEntries", -5);
        assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit("run-capneg", "user-1", "raw-ref", "a", "1", "one", 6));

        // cap<=0 不得变成"关闭上限"：回正后注册恢复
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 5);
        assertNotNull(registry.registerExplicit("run-cap0", "user-1", "raw-ref", "b", "2", "two", 6));
    }

    @Test
    void concurrentRegistrationsShouldNeverOverflowRunListCap() throws Exception {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 3);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> okIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> rejects = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        PersistentArtifactRegistration r = registry.registerExplicit(
                                "run-ccap", "user-1", "raw-ref", "logical-" + n, "n" + n, "payload-" + n, 6);
                        okIds.add(r.getArtifactId());
                    } catch (Throwable t) {
                        rejects.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        Set<String> listed = sets.getOrDefault(RUN_LIST_PREFIX + "run-ccap", Set.of());
        assertTrue(listed.size() <= 3, "索引绝不超 cap: size=" + listed.size());
        // overflow 可见性：成功注册必须全部可见于索引；失败全部是 cap 超限异常
        assertEquals(new HashSet<>(okIds), listed, "成功注册与索引项必须一致");
        for (Throwable t : rejects) {
            assertTrue(t instanceof IllegalStateException, "拒绝必须是 IllegalStateException: " + t);
            assertTrue(t.getMessage().contains("capacity exceeded"), t.getMessage());
        }
        assertEquals(threads, okIds.size() + rejects.size());
        // 无孤儿 meta：values 中的 meta 恰好都是成功注册
        Set<String> metaIds = values.keySet().stream()
                .filter(k -> k.startsWith(META_PREFIX)
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX))
                .map(k -> k.substring(META_PREFIX.length()))
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(okIds), metaIds, "被拒注册不得残留 meta");
        // 竞态后索引未满时注册必须恢复（无永久阻塞）
        if (listed.size() < 3) {
            PersistentArtifactRegistration after = registry.registerExplicit(
                    "run-ccap", "user-1", "raw-ref", "after", "9", "after", 6);
            assertTrue(sets.get(RUN_LIST_PREFIX + "run-ccap").contains(after.getArtifactId()),
                    "竞态后索引未满时注册必须恢复");
        }
    }

    // ===== MUST-FIX ①：幂等抢占单一赢家（latch/interleaving） =====

    @Test
    void concurrentIdempotentRegistrationShouldProduceSingleWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        PersistentArtifactRegistration r = registry.registerIdempotent(
                                "run-race", "user-1", "python_script", "script-race", "脚本", "v" + n, 6);
                        ids.add(r.getArtifactId());
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), () -> "并发注册不得失败: " + errors);
        assertEquals(threads, ids.size());
        // 单一赢家：所有线程拿到同一 artifactId
        assertEquals(1, new HashSet<>(ids).size(), "同一身份并发注册必须返回同一 artifactId");
        String winnerId = ids.get(0);
        // 单索引项
        List<PersistentArtifactMeta> listed = registry.listByRunId("run-race");
        assertEquals(1, listed.size());
        assertEquals(winnerId, listed.get(0).getArtifactId());
        // 单文件（输家候选文件必须回滚）
        try (var paths = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, paths.count(), "type 目录恰一文件");
        }
        // 单身份 field 且指向赢家；values 中恰一条该 run 的 meta（输家 meta 已回滚）
        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + "run-race");
        assertNotNull(identity);
        assertEquals(1, identity.size(), "身份 hash 恰一 field");
        assertEquals(winnerId, identity.values().iterator().next());
        long metaCount = values.keySet().stream()
                .filter(k -> k.startsWith(META_PREFIX)
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX))
                .count();
        assertEquals(1, metaCount, "恰一条 meta，输家候选 meta 必须回滚");
    }

    @Test
    void staleIdentityFieldShouldBeClearedAndRegistrationRetried() throws Exception {
        // 预置陈旧身份：field 指向一个没有 meta 的 artifactId（模拟赢家被清理后的悬挂）
        String field = PersistentArtifactRegistry.identityField("python_script", "script-stale", null);
        hashes.computeIfAbsent(RUN_IDENTITY_PREFIX + "run-stale", k -> new ConcurrentHashMap<>())
                .put(field, "python_script:ghost");

        PersistentArtifactRegistration registration = registry.registerIdempotent(
                "run-stale", "user-1", "python_script", "script-stale", "脚本", "alive", 6);

        assertNotNull(registration.getArtifactId());
        assertNotEquals("python_script:ghost", registration.getArtifactId());
        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + "run-stale");
        assertEquals(registration.getArtifactId(), identity.get(field), "身份必须指向新赢家");
        assertEquals(1, identity.size());
        assertEquals("alive", registry.readContent(registration.getArtifactId()));
        // 首次尝试的候选零残留：type 目录恰一文件、run 索引恰一项
        try (var paths = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, paths.count());
        }
        assertEquals(1, registry.listByRunId("run-stale").size());
    }

    // ===== MUST-FIX ④：TOCTOU（注册后 symlink swap / 内容篡改） =====

    @Test
    void symlinkSwapAfterRegistrationShouldBeRejectedOnRead() throws Exception {
        // external：注册合法文件后把路径换成指向根外的 symlink → 读取必须拒
        Files.createDirectories(datasetRoot.resolve("ds-swap"));
        Path csv = Files.writeString(datasetRoot.resolve("ds-swap").resolve("data.csv"), "a\n1\n");
        PersistentArtifactRegistration external = registry.registerExternalExplicit(
                "run-swap", "user-1", "dataset", "ds-swap", "数据集", csv, 6, false);
        Path secret = Files.writeString(tempDir.resolve("secret-swap.txt"), "top-secret");
        Files.delete(csv);
        Files.createSymbolicLink(csv, secret);
        assertThrows(SecurityException.class,
                () -> registry.readArtifactBytes(external.getArtifactId(), -1L));

        // 内容制品：注册后把文件换成 symlink（目标即使在根内也拒——no-follow 打开）
        PersistentArtifactRegistration content = registry.registerExplicit(
                "run-swap", "user-1", "raw-ref", "t", "工具", "payload", 6);
        Path contentPath = Path.of(registry.find(content.getArtifactId()).orElseThrow().getPath());
        Files.writeString(artifactRoot.resolve("raw-ref").resolve("decoy.txt"), "decoy");
        Files.delete(contentPath);
        Files.createSymbolicLink(contentPath, artifactRoot.resolve("raw-ref").resolve("decoy.txt"));
        assertThrows(IllegalStateException.class, () -> registry.readContent(content.getArtifactId()));
    }

    @Test
    void contentTamperAfterRegistrationShouldFailHashCheck() throws Exception {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-tamper", "user-1", "raw-ref", "t", "工具", "original", 6);
        Path path = Path.of(registry.find(registration.getArtifactId()).orElseThrow().getPath());
        Files.writeString(path, "tampered");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.readContent(registration.getArtifactId()));
        assertTrue(e.getMessage().contains("hash mismatch"), e.getMessage());
    }

    // ===== MUST-FIX ⑤：身份 collision-free 编码 =====

    @Test
    void identityFieldEncodingShouldBeCollisionFree() {
        // 段边界歧义组合：(type="a|b", logicalId="c") vs (type="a", logicalId="b|c")
        assertNotEquals(PersistentArtifactRegistry.identityField("a|b", "c", null),
                PersistentArtifactRegistry.identityField("a", "b|c", null));
        assertNotEquals(PersistentArtifactRegistry.identityField("a", "b", "c|d"),
                PersistentArtifactRegistry.identityField("a", "b|c", "d"));
        assertNotEquals(PersistentArtifactRegistry.identityField("a", "b", null),
                PersistentArtifactRegistry.identityField("a", "b", ""));

        // registry 级：两类撞名组合各自独立注册、互不采纳
        PersistentArtifactRegistration r1 = registry.registerIdempotent(
                "run-collide", "user-1", "a|b", "c", "1", "one", 6);
        PersistentArtifactRegistration r2 = registry.registerIdempotent(
                "run-collide", "user-1", "a", "b|c", "2", "two", 6);
        assertNotEquals(r1.getArtifactId(), r2.getArtifactId(), "不同身份不得撞 field 互相采纳");
        assertEquals(2, registry.listByRunId("run-collide").size());
        assertEquals(2, hashes.get(RUN_IDENTITY_PREFIX + "run-collide").size());
        assertEquals("one", registry.readContent(r1.getArtifactId()));
        assertEquals("two", registry.readContent(r2.getArtifactId()));
    }

    // ===== 有界索引 / 陈旧索引自愈 / cleanup 键跳过 =====

    @Test
    void listByRunIdShouldFilterStaleIndexEntries() {
        registry.registerExplicit("run-stale", "user-1", "raw-ref", "a", "1", "one", 6);
        // 陈旧索引项：指向不存在的 artifactId（如外部直接改过索引）
        sets.computeIfAbsent(RUN_LIST_PREFIX + "run-stale", k -> ConcurrentHashMap.newKeySet())
                .add("raw-ref:ghost");

        List<PersistentArtifactMeta> listed = registry.listByRunId("run-stale");
        assertEquals(1, listed.size(), "陈旧索引项必须被滤掉而不是让 list 失败");
    }

    @Test
    void cleanupShouldSkipIndexKeysMatchingMetaScanPattern() throws Exception {
        // run 索引/身份键与 meta 共享前缀，cleanup SCAN 会命中：必须显式跳过、不误删。
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                "run-skip", "user-1", "python_script", "s", "脚本", "keep", 6);
        String metaKey = META_PREFIX + registration.getArtifactId();
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

    // ===== fake redis（线程安全；支持 Lua 值条件 HDEL） =====

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
                    Set<String> all = new HashSet<>(values.keySet());
                    all.addAll(hashes.keySet());
                    all.addAll(sets.keySet());
                    return new SetCursor(all.iterator());
                });

        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);
        when(hashOps.putIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> hashes
                        .computeIfAbsent(invocation.getArgument(0), k -> new ConcurrentHashMap<>())
                        .putIfAbsent(invocation.getArgument(1).toString(),
                                invocation.getArgument(2).toString()) == null);
        org.mockito.Mockito.doAnswer(invocation -> {
            hashes.computeIfAbsent(invocation.getArgument(0), k -> new ConcurrentHashMap<>())
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
        // MUST-FIX ①：registry 值条件 HDEL 走 Lua execute()，fake 以同步块模拟原子 CAS
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            List<String> keys = (List<String>) args[1];
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
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(template.opsForSet()).thenReturn(setOps);
        when(setOps.add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String>any()))
                .thenAnswer(invocation -> sets
                        .computeIfAbsent(invocation.getArgument(0), k -> ConcurrentHashMap.newKeySet())
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
