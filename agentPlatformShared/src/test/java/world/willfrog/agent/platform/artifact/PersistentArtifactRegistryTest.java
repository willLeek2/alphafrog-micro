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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.charset.StandardCharsets;
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
 *       （strict 四值非空且相等，宽容 seam 已删除）</li>
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
 * <h3>第二轮 MUST-FIX 反测（ecdfa704，四组）</h3>
 * <ul>
 *   <li>①认领单条 Lua 原子提交：FULL 不留幽灵身份 / 输家采纳不先于赢家列表提交 ——
 *       {@link #winnerCapFailureShouldNotLeaveGhostIdentityOrIndexTrace}
 *       / {@link #loserAdoptionShouldReturnWinnerAlreadyCommittedToRunList}</li>
 *   <li>②宽容读取 seam 的用户可达性反测归 ToolOutputRefServiceImplTest 与
 *       RereadToolHandlerTest（服务层与工具层合同），本文件不重复</li>
 *   <li>③有界流式读取（权威大小上限）+ 父目录 symlink 换入 ——
 *       {@link #boundedStreamShouldRejectOversizedContentEvenWithoutPreCheck}
 *       / {@link #parentDirectorySymlinkSwapShouldBeRejectedOnRead}</li>
 *   <li>④幽灵成员自愈：幽灵占满 cap 后注册恢复 + 读取侧移除幽灵 ——
 *       {@link #ghostsFillingCapShouldBePurgedSoNewRegistrationRecovers}
 *       / {@link #listByRunIdShouldFilterStaleIndexEntries}</li>
 * </ul>
 *
 * <h3>第三轮 MUST-FIX 反测（62ad12bd，三组）</h3>
 * <ul>
 *   <li>①有界幽灵清理必须真「有界且保证进展」（游标轮转，不得 SMEMBERS 全量取出、
 *       不得前窗全活时幽灵永占名额）——
 *       {@link #ghostPurgeShouldMakeProgressEvenWhenFirstBudgetWindowIsAllLive}
 *       / {@link #claimAndAddScriptsShouldUseSscanCursorRotationNotSmembers}</li>
 *   <li>②短格式 raw_ref 严格 user 归属反测归 RunRawRefStoreImplTest 与
 *       RereadToolHandlerTest（读取链路携带 userId 的四值校验合同），本文件不重复</li>
 *   <li>③统一滑动过期协议：touch 带动索引 TTL 同滑动 / 滑动后索引不得先于 meta 过期、
 *       同一身份不得二次 CLAIMED / EXISTS 分支修复丢失的列表成员资格 ——
 *       {@link #touchShouldSlideIndexTtlsTogetherWithMeta}
 *       / {@link #slidTtlShouldPreventIndexExpiryBeforeMetaAndDoubleClaim}
 *       / {@link #existsBranchShouldRepairMissingRunListEntry}</li>
 * </ul>
 *
 * <p>Redis 用线程安全内存 fake（ConcurrentHashMap/concurrent set，支持真线程并发测试；
 * 三种 Lua 脚本——幂等认领（6 ARGV）、列表加入（5 ARGV）、值条件 HDEL（2 ARGV）——
 * 的 execute() 按 ARGV 个数分发，共用一把锁模拟 Redis 单线程原子执行。fake 另带
 * 可控时钟 + 每键 deadline（惰性过期）、游标轮转 SSCAN 模拟与 getExpire/expire
 * 跟踪，支撑滑动过期与幽灵清理进展保证的反测），文件落 @TempDir；
 * 不碰生产 DB/Redis/Nacos。</p>
 */
class PersistentArtifactRegistryTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";
    private static final String RUN_IDENTITY_PREFIX = META_PREFIX + "run-identity:";
    private static final String RUN_PURGE_CURSOR_PREFIX = META_PREFIX + "run-purge-cursor:";

    @TempDir
    Path tempDir;

    private Map<String, String> values;
    private Map<String, Map<String, String>> hashes;
    private Map<String, Set<String>> sets;
    /**
     * fake 时钟 + 每键过期时刻（millis）。统一滑动过期协议的反测需要可控时间：
     * 注册/读取按 fakeNow 记录 TTL 截止时间，advanceClock 推进后由 sweepExpired
     * 惰性清除过期键（与真实 Redis 惰性/定期过期删除的可观察语义一致）。
     * 直接 values.put 而不记录 deadline 的键视为无 TTL（持久），与 Redis PERSIST 等价。
     */
    private long fakeNow;
    private Map<String, Long> deadlines;
    /**
     * 模拟 Redis 单线程执行：所有 Lua 脚本 fake（认领/加入/值条件 HDEL）共用这一把锁，
     * 保证任一脚本执行期间没有其他脚本插入——这是真实 Redis 原子性的最小等价模拟。
     */
    private final Object redisLock = new Object();
    private PersistentArtifactRegistry registry;
    private Path artifactRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        sets = new ConcurrentHashMap<>();
        fakeNow = System.currentTimeMillis();
        deadlines = new ConcurrentHashMap<>();
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

    // ===== ④ 跨 run/user 拒绝（strict fail-closed，无宽容 seam） =====

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

        // meta 侧缺上下文（历史制品）：严格 matcher fail-closed——宽容 seam 已整体删除，
        // 历史无上下文制品经任何入口都拒绝（第二轮 MUST-FIX ②）
        PersistentArtifactRegistration noContext = registry.registerExplicit(
                null, null, "raw-ref", "t", "旧制品", "old", 6);
        PersistentArtifactMeta legacyMeta = registry.find(noContext.getArtifactId()).orElseThrow();
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(legacyMeta, "run-any", "user-any"),
                "meta 侧空值：严格校验必须拒");
        assertFalse(PersistentArtifactRegistry.matchesOwnerStrict(legacyMeta, null, null),
                "meta 侧与调用方均空：严格校验必须拒");

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
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_PURGE_CURSOR_PREFIX))
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
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_PURGE_CURSOR_PREFIX))
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
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_PURGE_CURSOR_PREFIX))
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

    // ===== 第二轮 MUST-FIX ①：认领单条 Lua 原子提交（FULL 不留幽灵、输家不先于赢家列表提交） =====

    @Test
    void winnerCapFailureShouldNotLeaveGhostIdentityOrIndexTrace() throws Exception {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 1);
        // run 已有一个合法成员，占满唯一容量位
        PersistentArtifactRegistration first = registry.registerExplicit(
                "run-full", "user-1", "raw-ref", "first", "1", "one", 6);

        // 幂等认领撞 FULL：可见失败，且不写任何身份字段——旧实现"先 HSETNX 身份、
        // 后 SADD 列表"在列表容量失败时会留下"身份在、列表没进"的幽灵半成品；
        // 新实现 FULL 路径不写任何索引
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerIdempotent(
                        "run-full", "user-1", "python_script", "blocked", "脚本", "x", 6));
        assertTrue(e.getMessage().contains("capacity exceeded"), e.getMessage());

        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + "run-full");
        assertTrue(identity == null || identity.isEmpty(),
                "FULL 路径不得写身份字段，否则输家会拿到幽灵 ID");
        assertEquals(Set.of(first.getArtifactId()), sets.get(RUN_LIST_PREFIX + "run-full"),
                "索引只含合法成员");
        long metaCount = values.keySet().stream()
                .filter(k -> k.startsWith(META_PREFIX)
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_PURGE_CURSOR_PREFIX))
                .count();
        assertEquals(1, metaCount, "被拒注册的 meta 必须回滚");
        Path typeDir = artifactRoot.resolve("python_script");
        if (Files.exists(typeDir)) {
            try (var paths = Files.list(typeDir)) {
                assertEquals(0, paths.count(), "被拒注册的候选文件必须回滚");
            }
        }
        // 重试同样拿不到幽灵 ID：仍 FULL、仍可见失败
        assertThrows(IllegalStateException.class,
                () -> registry.registerIdempotent(
                        "run-full", "user-1", "python_script", "blocked", "脚本", "x", 6));
    }

    @Test
    void loserAdoptionShouldReturnWinnerAlreadyCommittedToRunList() throws Exception {
        PersistentArtifactRegistration winner = registry.registerIdempotent(
                "run-adopt", "user-1", "python_script", "shared", "脚本", "v1", 6);

        // 同一身份第二次注册是确定性输家：采纳赢家结果
        PersistentArtifactRegistration adopted = registry.registerIdempotent(
                "run-adopt", "user-1", "python_script", "shared", "脚本", "v2", 6);
        assertEquals(winner.getArtifactId(), adopted.getArtifactId());

        // 关键不变量：输家拿到赢家 ID 的时刻，赢家必须已在 run 列表里——新 Lua 协议下
        // EXISTS 只能在赢家身份+列表原子提交之后被观察到，"输家已返回而赢家列表未提交"
        // 的交错不再存在
        assertTrue(sets.get(RUN_LIST_PREFIX + "run-adopt").contains(winner.getArtifactId()),
                "输家采纳不得先于赢家列表提交");
        assertEquals(1, registry.listByRunId("run-adopt").size());
        // 输家候选零残留：type 目录恰一文件
        try (var paths = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, paths.count());
        }
    }

    // ===== 第二轮 MUST-FIX ③：有界流式读取 + 父目录 symlink 换入 =====

    @Test
    void boundedStreamShouldRejectOversizedContentEvenWithoutPreCheck() throws Exception {
        // 直接调用权威执行点 readBounded（绕过 Files.size 预检查）——等价于确定性复现
        // "文件在预检查之后、实读之前增大"：无论预检查当时如何通过，流式读取至多读
        // maxBytes+1 字节后拒绝，绝不把任意大文件整个分配进内存
        Path file = Files.writeString(tempDir.resolve("bounded.bin"), "0123456789"); // 10 字节
        assertEquals("0123456789",
                new String(PersistentArtifactRegistry.readBounded(file, 10), StandardCharsets.UTF_8),
                "恰好等于上限必须完整返回");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PersistentArtifactRegistry.readBounded(file, 9));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        // maxBytes<=0 = 不限制
        assertEquals("0123456789",
                new String(PersistentArtifactRegistry.readBounded(file, -1), StandardCharsets.UTF_8));
    }

    @Test
    void parentDirectorySymlinkSwapShouldBeRejectedOnRead() throws Exception {
        // TOCTOU 变体：不换最终文件，而是把它的父目录换成指向根外的 symlink——
        // 规范化 containment 是纯字符串前缀比较，看不穿中间目录；这正是读取时
        // realpath 复检要拦的情形（旧测试只覆盖最终文件被换）
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-parent-swap", "user-1", "raw-ref", "t", "工具", "payload", 6);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        Path file = Path.of(meta.getPath());
        Path typeDir = file.getParent();

        Path outsideDir = Files.createDirectories(tempDir.resolve("outside-dir"));
        Files.writeString(outsideDir.resolve(file.getFileName().toString()), "evil");

        Files.delete(file);
        Files.delete(typeDir);
        Files.createSymbolicLink(typeDir, outsideDir);

        assertThrows(SecurityException.class,
                () -> registry.readContent(registration.getArtifactId()));
    }

    // ===== 第二轮 MUST-FIX ④：幽灵成员自愈（过期幽灵不得永久占 cap） =====

    @Test
    void ghostsFillingCapShouldBePurgedSoNewRegistrationRecovers() {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 2);
        // 预置 2 个幽灵成员：在 SET 里但 meta 键已不存在（典型成因：meta 的 Redis TTL 到期）——
        // 旧实现 SCARD 把它们计入容量，出现"可见列表没满却持续容量超限"的怪象
        sets.computeIfAbsent(RUN_LIST_PREFIX + "run-ghost", k -> ConcurrentHashMap.newKeySet())
                .addAll(List.of("raw-ref:ghost-1", "raw-ref:ghost-2"));

        // cap"名义已满"（SCARD==2）但全是幽灵：幂等认领经有界幽灵清理后必须恢复
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                "run-ghost", "user-1", "raw-ref", "alive", "alive", "payload", 6);

        assertNotNull(registration.getArtifactId());
        assertEquals(Set.of(registration.getArtifactId()), sets.get(RUN_LIST_PREFIX + "run-ghost"),
                "幽灵必须被清掉，索引只含新赢家");
        assertEquals(1, registry.listByRunId("run-ghost").size());
        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + "run-ghost");
        assertEquals(registration.getArtifactId(),
                identity.get(PersistentArtifactRegistry.identityField("raw-ref", "alive", null)));
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
        // 第二轮 MUST-FIX ④：读取侧不仅过滤，还顺手 SREM 幽灵成员
        assertFalse(sets.get(RUN_LIST_PREFIX + "run-stale").contains("raw-ref:ghost"),
                "幽灵成员必须在读取遍历时被移除");
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
        // 第三轮新增的幽灵清理游标键同样共享前缀：同样必须跳过
        values.put(RUN_PURGE_CURSOR_PREFIX + "run-skip", expiredJson);

        registry.cleanupExpiredArtifacts();

        // 真 meta 被清，索引键原样保留（即使它们误存了可解析 JSON 也不得按 meta 处理）
        assertFalse(values.containsKey(metaKey));
        assertTrue(values.containsKey(RUN_LIST_PREFIX + "run-skip"));
        assertTrue(values.containsKey(RUN_IDENTITY_PREFIX + "run-skip"));
        assertTrue(values.containsKey(RUN_PURGE_CURSOR_PREFIX + "run-skip"));
    }

    // ===== 第三轮 MUST-FIX ①：有界幽灵清理必须真「有界且保证进展」 =====

    @Test
    void ghostPurgeShouldMakeProgressEvenWhenFirstBudgetWindowIsAllLive() {
        // codex 62ad12bd ① 反测：cap > 清理预算（128），前若干个预算窗口全是活成员、
        // 幽灵排在后面——旧实现 SMEMBERS 全量取出后只查前 128 个，每次调用都重复检查
        // 同一批活成员，幽灵永远扫不到、永久占用名额、注册永远 FULL。游标轮转协议必须
        // 让清理状态跨调用推进：至多 ceil(成员总数/预算) 次索引写入内清完所有幽灵。
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 200);
        String runId = "run-progress";
        String listKey = RUN_LIST_PREFIX + runId;
        String cursorKey = RUN_PURGE_CURSOR_PREFIX + runId;
        // 预置 199 个活成员（meta 在 values 表 = exists 为真）+ 1 个幽灵（只有 SET 成员、
        // 无 meta），幽灵命名为 zz- 保证排序后落在最末位（第一预算窗口 0..127 全是活成员）
        for (int i = 0; i < 199; i++) {
            String id = "raw-ref:live-" + String.format("%03d", i);
            values.put(META_PREFIX + id, "{}");
            sets.computeIfAbsent(listKey, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
        sets.get(listKey).add("raw-ref:zz-ghost");
        assertEquals(200, sets.get(listKey).size());

        // 第 1 次注册：扫描窗口 [0,128) 全是活成员、无幽灵可清 → SCARD 仍 200 ≥ cap → FULL；
        // 关键是游标必须前进并持久化（清理状态不再每次从零重复）
        IllegalStateException full = assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit(runId, "user-1", "raw-ref", "new", "new", "payload", 6));
        assertTrue(full.getMessage().contains("capacity exceeded"), full.getMessage());
        assertEquals("128", values.get(cursorKey), "第一次扫描后游标必须推进到 128 并持久化");
        assertTrue(sets.get(listKey).contains("raw-ref:zz-ghost"), "第一窗口扫不到幽灵，不得误清活成员");

        // 第 2 次注册：从游标 128 继续扫描 [128,200) → 扫到末尾的幽灵并当场移除 →
        // 整圈完成游标归零删键 → SCARD 199 < 200 → 注册恢复。
        PersistentArtifactRegistration registration = registry.registerExplicit(
                runId, "user-1", "raw-ref", "new", "new", "payload", 6);
        assertNotNull(registration.getArtifactId());
        Set<String> listed = sets.get(listKey);
        assertEquals(200, listed.size(), "199 活成员 + 新制品，幽灵已清");
        assertTrue(listed.contains(registration.getArtifactId()));
        assertFalse(listed.contains("raw-ref:zz-ghost"), "幽灵必须在有界次数的写入内被清掉");
        assertFalse(values.containsKey(cursorKey), "整圈扫描完成后游标键必须删除");
        // 进展有界性：200 成员、预算 128 → ceil(200/128)=2 次索引写入内必然恢复（本测恰好 2 次）
    }

    @Test
    void claimAndAddScriptsShouldUseSscanCursorRotationNotSmembers() throws Exception {
        // codex 62ad12bd ① 反测（脚本形态守卫）：幽灵清理必须走 SSCAN 游标轮转——
        // 单次脚本执行只取有界数量的成员，绝不先 SMEMBERS 全量取出整个 SET
        // （大 SET 下全量取出 = 无界内存分配 + 阻塞）。脚本文本钉住该形态。
        @SuppressWarnings("unchecked")
        DefaultRedisScript<String> claim = (DefaultRedisScript<String>) ReflectionTestUtils.getField(
                PersistentArtifactRegistry.class, "ATOMIC_CLAIM_SCRIPT");
        @SuppressWarnings("unchecked")
        DefaultRedisScript<String> add = (DefaultRedisScript<String>) ReflectionTestUtils.getField(
                PersistentArtifactRegistry.class, "RUN_LIST_ADD_SCRIPT");
        String claimText = claim.getScriptAsString();
        String addText = add.getScriptAsString();

        assertTrue(claimText.contains("sscan"), "认领脚本必须用 SSCAN 游标轮转清理");
        assertFalse(claimText.toLowerCase().contains("smembers"), "认领脚本不得全量取出 SET");
        assertTrue(addText.contains("sscan"), "加入脚本必须用 SSCAN 游标轮转清理");
        assertFalse(addText.toLowerCase().contains("smembers"), "加入脚本不得全量取出 SET");
        // 游标键以 KEYS 传入且写回旋转（归零删键），证明跨调用的推进状态在脚本内持久化
        assertTrue(claimText.contains("KEYS[3]"), "认领脚本必须把游标键作为 KEYS 传入");
        assertTrue(addText.contains("KEYS[2]"), "加入脚本必须把游标键作为 KEYS 传入");
    }

    // ===== 第三轮 MUST-FIX ③：统一滑动过期协议（touch 同滑动 / 防索引先过期 / EXISTS 修复） =====

    @Test
    void touchShouldSlideIndexTtlsTogetherWithMeta() {
        // codex 62ad12bd ③ 反测：读取 touch 重写 meta（满额 TTL）的同时，必须把 run 列表
        // 与幂等身份两张索引键按同一 ttlHours 一起滑动——否则一次读取就让 meta 活过索引，
        // 索引先过期后 list 丢条目、同一幂等身份可被第二次 CLAIMED。
        String runId = "run-slide";
        long t0 = fakeNow;
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "s", "1", "one", 6);
        String metaKey = META_PREFIX + registration.getArtifactId();

        // 注册后四类过期协议键（meta/列表/身份）的 deadline 完全一致（脚本内同款 TTL 写入）
        assertEquals(t0 + TimeUnit.HOURS.toMillis(6), deadlines.get(metaKey));
        assertEquals(deadlines.get(metaKey), deadlines.get(RUN_LIST_PREFIX + runId),
                "注册后列表键 TTL 必须与 meta 对齐");
        assertEquals(deadlines.get(metaKey), deadlines.get(RUN_IDENTITY_PREFIX + runId),
                "注册后身份键 TTL 必须与 meta 对齐");

        // 2 小时后读取：touch 必须把三类键一起滑回满额 6h（而不是只滑 meta）
        advanceClock(TimeUnit.HOURS.toMillis(2));
        assertEquals("one", registry.readContent(registration.getArtifactId()));
        long expected = t0 + TimeUnit.HOURS.toMillis(8);
        assertEquals(expected, deadlines.get(metaKey), "meta 必须滑动回满额");
        assertEquals(expected, deadlines.get(RUN_LIST_PREFIX + runId), "列表键必须随 touch 同滑动");
        assertEquals(expected, deadlines.get(RUN_IDENTITY_PREFIX + runId), "身份键必须随 touch 同滑动");
    }

    @Test
    void slidTtlShouldPreventIndexExpiryBeforeMetaAndDoubleClaim() throws Exception {
        // codex 62ad12bd ③ 反测（可控时钟/TTL 全链路）：注册后读取一次（touch 滑动），
        // 再推进时钟越过「原始」过期时刻——滑动过的 meta 与索引必须都还活着；此时同一
        // 幂等身份再次注册必须采纳原赢家（EXISTS），绝不允许索引先于 meta 过期导致
        // 同一身份第二次 CLAIMED 出新 ID。最后推进越过滑动后的 deadline，全部过期后
        // 重新认领必须拿到全新 ID（干净重注册，不继承悬挂状态）。
        String runId = "run-drift";
        long t0 = fakeNow;
        PersistentArtifactRegistration first = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v1", 2);

        // 推进 1h 后读取 → touch 把 meta/列表/身份全部滑动到 t0+1h+2h
        advanceClock(TimeUnit.HOURS.toMillis(1));
        assertEquals("v1", registry.readContent(first.getArtifactId()));

        // 再推进 1.5h：fakeNow = t0+2.5h，已越过原始过期时刻 t0+2h，但未越过滑动后的
        // t0+3h——meta 与两张索引键必须全部存活
        advanceClock(TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(30));
        PersistentArtifactRegistration second = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v2", 2);
        assertEquals(first.getArtifactId(), second.getArtifactId(),
                "索引随 meta 滑动后，同一身份必须采纳原赢家，不得第二次 CLAIMED");
        assertEquals(1, registry.listByRunId(runId).size(), "列表不得丢失或重复条目");
        try (var paths = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, paths.count(), "输家候选文件必须回滚，type 目录恰一文件");
        }
        assertEquals("v1", registry.readContent(first.getArtifactId()), "内容仍是首次写入值");

        // 推进越过所有滑动后的 deadline → 全部过期 → 干净重认领拿到全新 ID
        advanceClock(TimeUnit.HOURS.toMillis(3));
        PersistentArtifactRegistration third = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v3", 2);
        assertNotEquals(first.getArtifactId(), third.getArtifactId(),
                "全部过期后重新认领必须产生新 ID（身份已随索引一起过期清除）");
        assertEquals("v3", registry.readContent(third.getArtifactId()));
        assertEquals(1, registry.listByRunId(runId).size());
    }

    @Test
    void existsBranchShouldRepairMissingRunListEntry() {
        // codex 62ad12bd ③ 反测：身份字段在场、赢家的 run 列表成员资格却丢失时
        // （列表键早于身份过期或被外力移除），EXISTS 分支不得只做透传——必须当场校验
        // 并修复列表成员资格，否则输家采纳的赢家在用户列表里不可见。
        String runId = "run-repair";
        PersistentArtifactRegistration winner = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v1", 6);
        // 模拟列表成员资格丢失：SET 成员被移除，但身份字段仍指向赢家
        sets.get(RUN_LIST_PREFIX + runId).remove(winner.getArtifactId());
        assertTrue(sets.get(RUN_LIST_PREFIX + runId).isEmpty());
        assertEquals(winner.getArtifactId(),
                hashes.get(RUN_IDENTITY_PREFIX + runId).get(
                        PersistentArtifactRegistry.identityField("python_script", "shared", null)));

        // 同一身份再次注册 → EXISTS 分支：修复成员资格 + 采纳赢家
        PersistentArtifactRegistration adopted = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v2", 6);
        assertEquals(winner.getArtifactId(), adopted.getArtifactId());
        assertTrue(sets.get(RUN_LIST_PREFIX + runId).contains(winner.getArtifactId()),
                "EXISTS 分支必须把赢家修复回 run 列表");
        assertEquals(1, registry.listByRunId(runId).size(), "用户列表必须重新看见赢家");
    }

    // ===== fake redis（线程安全；支持认领/加入/值条件 HDEL 三种 Lua 脚本） =====

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        // 带 TTL 写：记录 deadline = fakeNow + ttl（统一滑动过期协议的 meta 侧）
        org.mockito.Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long ttl = invocation.getArgument(2);
            TimeUnit unit = invocation.getArgument(3);
            values.put(key, invocation.getArgument(1));
            deadlines.put(key, fakeNow + unit.toMillis(ttl));
            return null;
        }).when(valueOps).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(valueOps.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        return values.get(invocation.getArgument(0));
                    }
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            deadlines.remove(invocation.getArgument(0));
            return values.remove(invocation.getArgument(0)) != null;
        }).when(template).delete(org.mockito.ArgumentMatchers.anyString());
        // SCAN 返回三张表全部键（模拟真实 Redis 中索引键也会被 META_PREFIX* 命中）
        when(template.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class)))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Set<String> all = new HashSet<>(values.keySet());
                        all.addAll(hashes.keySet());
                        all.addAll(sets.keySet());
                        return new SetCursor(all.iterator());
                    }
                });
        // TTL 查询/设置（统一滑动过期协议的 fake 侧）：
        // getExpire 语义与真实 Redis 一致——键不存在/已过期 = -2，无 TTL = -1，否则剩余量；
        // expire 对不存在的键是 no-op（真实 Redis 语义），只在键存在时记录 deadline。
        when(template.getExpire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    TimeUnit unit = invocation.getArgument(1);
                    synchronized (redisLock) {
                        sweepExpired();
                        if (!keyExistsInFake(key)) {
                            return -2L;
                        }
                        Long deadline = deadlines.get(key);
                        if (deadline == null) {
                            return -1L;
                        }
                        long remaining = deadline - fakeNow;
                        if (remaining <= 0) {
                            return -2L;
                        }
                        return unit.convert(remaining, TimeUnit.MILLISECONDS);
                    }
                });
        when(template.expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    long ttl = invocation.getArgument(1);
                    TimeUnit unit = invocation.getArgument(2);
                    synchronized (redisLock) {
                        sweepExpired();
                        if (!keyExistsInFake(key)) {
                            return false;
                        }
                        deadlines.put(key, fakeNow + unit.toMillis(ttl));
                        return true;
                    }
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
        // ===== Lua execute() fake（第三轮 MUST-FIX 后的协议）=====
        // Mockito 5 对 varargs 按"每个匹配器对一个可变参数"匹配，三种脚本 ARGV 个数不同
        // （认领 6 / 加入 5 / 值条件 HDEL 2），各需独立 stub。三个 stub 共用 redisLock，
        // 模拟 Redis 单线程：任一脚本执行期间其他脚本不得插入（原子性最小等价模拟）。

        // 值条件 HDEL（2 个 ARGV：field、期望值）：仅当 field 值仍等于期望 artifactId 时删除
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            String field = String.valueOf(args[2]);
            String expected = String.valueOf(args[3]);
            synchronized (redisLock) {
                sweepExpired();
                Map<String, String> h = hashes.get(keys.get(0));
                if (h == null) {
                    return 0L;
                }
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

        // run 列表加入脚本（KEYS=[列表 SET, 清理游标键]；5 个 ARGV：cap、幽灵预算、
        // meta 前缀、artifactId、TTL 秒数）：游标轮转幽灵清理 → SCARD 容量检查 → SADD
        // → 列表键只延长不缩短 TTL 刷新，原子；满则返回 FULL 且不写
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            int cap = Integer.parseInt(String.valueOf(args[2]));
            int budget = Integer.parseInt(String.valueOf(args[3]));
            String metaPrefix = String.valueOf(args[4]);
            String artifactId = String.valueOf(args[5]);
            long ttlSeconds = Long.parseLong(String.valueOf(args[6]));
            synchronized (redisLock) {
                sweepExpired();
                purgeWithCursor(keys.get(0), keys.get(1), metaPrefix, budget, ttlSeconds);
                Set<String> list = sets.get(keys.get(0));
                int size = list == null ? 0 : list.size();
                if (size >= cap) {
                    return "FULL";
                }
                sets.computeIfAbsent(keys.get(0), k -> ConcurrentHashMap.newKeySet()).add(artifactId);
                extendOnlyTtl(keys.get(0), ttlSeconds);
                return "ADDED";
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        // 幂等认领脚本（KEYS=[身份 hash, 列表 SET, 清理游标键]；6 个 ARGV：field、候选 ID、
        // cap、幽灵预算、meta 前缀、TTL 秒数）：已有赢家→meta 仍在则修复列表成员资格
        // （SISMEMBER 缺失即 SADD 补回）+ 两索引键只延长 TTL → EXISTS:赢家ID；
        // 否则游标轮转幽灵清理→容量检查；未满→HSET 身份+SADD 列表+TTL 刷新→CLAIMED。
        // EXISTS 与写入互斥且整段原子——输家拿到 EXISTS 时赢家身份+列表必然已落盘且可见
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            String field = String.valueOf(args[2]);
            String artifactId = String.valueOf(args[3]);
            int cap = Integer.parseInt(String.valueOf(args[4]));
            int budget = Integer.parseInt(String.valueOf(args[5]));
            String metaPrefix = String.valueOf(args[6]);
            long ttlSeconds = Long.parseLong(String.valueOf(args[7]));
            synchronized (redisLock) {
                sweepExpired();
                Map<String, String> identity = hashes.get(keys.get(0));
                String existing = identity == null ? null : identity.get(field);
                if (existing != null) {
                    if (values.containsKey(metaPrefix + existing)) {
                        // 赢家 meta 仍活：修复列表成员资格 + 只延长不缩短 TTL 刷新
                        sets.computeIfAbsent(keys.get(1), k -> ConcurrentHashMap.newKeySet())
                                .add(existing);
                        extendOnlyTtl(keys.get(0), ttlSeconds);
                        extendOnlyTtl(keys.get(1), ttlSeconds);
                    }
                    return "EXISTS:" + existing;
                }
                purgeWithCursor(keys.get(1), keys.get(2), metaPrefix, budget, ttlSeconds);
                Set<String> list = sets.get(keys.get(1));
                int size = list == null ? 0 : list.size();
                if (size >= cap) {
                    return "FULL";
                }
                hashes.computeIfAbsent(keys.get(0), k -> new ConcurrentHashMap<>()).put(field, artifactId);
                sets.computeIfAbsent(keys.get(1), k -> ConcurrentHashMap.newKeySet()).add(artifactId);
                extendOnlyTtl(keys.get(0), ttlSeconds);
                extendOnlyTtl(keys.get(1), ttlSeconds);
                return "CLAIMED";
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
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

    /**
     * fake 侧游标轮转幽灵清理：与生产 Lua 脚本同语义——从持久化游标（本 fake 用
     * 「排序快照内的偏移量」编码游标位置）继续，至多检查 budget 个成员，meta 键
     * （values 表）不存在者当场 SREM，回写下一游标；游标归零（一整圈扫描完成）则
     * 删除游标键。每次调用只检查有界数量的成员、不全量取出整个 SET；整圈扫描必然
     * 覆盖每一个成员，幽灵不可能永久占用名额。调用方必须已持有 redisLock。
     */
    private void purgeWithCursor(String listKey, String cursorKey, String metaPrefix,
                                 int budget, long ttlSeconds) {
        if (budget <= 0) {
            return;
        }
        int offset = 0;
        String cursorVal = values.get(cursorKey);
        if (cursorVal != null) {
            try {
                offset = Integer.parseInt(cursorVal);
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }
        Set<String> list = sets.get(listKey);
        if (list == null || list.isEmpty()) {
            values.remove(cursorKey);
            deadlines.remove(cursorKey);
            return;
        }
        List<String> snapshot = new ArrayList<>(list);
        Collections.sort(snapshot);
        int from = Math.min(offset, snapshot.size());
        int to = Math.min(from + budget, snapshot.size());
        for (int i = from; i < to; i++) {
            String member = snapshot.get(i);
            if (!values.containsKey(metaPrefix + member)) {
                list.remove(member);
            }
        }
        if (to >= snapshot.size()) {
            // 一整圈扫描完成：游标归零即删键（与生产脚本 del 游标键一致）
            values.remove(cursorKey);
            deadlines.remove(cursorKey);
        } else {
            values.put(cursorKey, String.valueOf(to));
            deadlines.put(cursorKey, fakeNow + TimeUnit.SECONDS.toMillis(Math.max(1L, ttlSeconds)));
        }
    }

    /**
     * fake 侧惰性过期：deadline 已到（相对 fakeNow）的键从三张表与 deadline 表移除。
     * 与真实 Redis 惰性/定期过期删除的可观察语义一致。调用方必须已持有 redisLock。
     */
    private void sweepExpired() {
        Iterator<Map.Entry<String, Long>> it = deadlines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= fakeNow) {
                it.remove();
                values.remove(entry.getKey());
                hashes.remove(entry.getKey());
                sets.remove(entry.getKey());
            }
        }
    }

    /**
     * fake 侧只延长不缩短 TTL 刷新（对应生产脚本内 redis.call('ttl')/expire 段）。
     * 调用方必须已持有 redisLock。
     */
    private void extendOnlyTtl(String key, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        long desired = fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds);
        Long current = deadlines.get(key);
        if (current == null || current < desired) {
            deadlines.put(key, desired);
        }
    }

    private boolean keyExistsInFake(String key) {
        return values.containsKey(key) || hashes.containsKey(key) || sets.containsKey(key);
    }

    /** 推进 fake 时钟（millis）；随后的读取/脚本执行会按新时刻惰性清除过期键。 */
    private void advanceClock(long millis) {
        fakeNow += millis;
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
