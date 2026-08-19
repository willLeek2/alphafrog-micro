package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D22-5.1.3 registry 契约测试：钉住 codex 裁决 f0ee72cb §6 必测项
 * + MUST-FIX f54454fe 五类反测 + 第二轮 ecdfa704 + 第三轮 62ad12bd
 * + 第五轮 fd714484 五项 MUST-FIX 反测与 msg 11764111 四条边界约束。
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
 * <h3>第三轮 MUST-FIX 反测（62ad12bd，三组；第五轮起按 ZSET 窗口轮转协议重写）</h3>
 * <ul>
 *   <li>①有界幽灵清理必须真「有界且保证进展」（窗口轮转，不得 SMEMBERS/SSCAN 提示式游标、
 *       不得前窗全活时幽灵永占名额）——
 *       {@link #ghostPurgeShouldMakeProgressEvenWhenFirstBudgetWindowIsAllLive}
 *       / {@link #claimAndAddScriptsShouldUseZrangeWindowRotationNotSmembers}</li>
 *   <li>②短格式 raw_ref 严格 user 归属反测归 RunRawRefStoreImplTest 与
 *       RereadToolHandlerTest（读取链路携带 userId 的四值校验合同），本文件不重复</li>
 *   <li>③统一滑动过期协议：touch 带动索引 TTL 同滑动 / 滑动后索引不得先于 meta 过期、
 *       同一身份不得二次 CLAIMED / EXISTS 分支修复丢失的列表成员资格 ——
 *       {@link #touchShouldSlideIndexTtlsTogetherWithMeta}
 *       / {@link #slidTtlShouldPreventIndexExpiryBeforeMetaAndDoubleClaim}
 *       / {@link #existsBranchShouldRepairMissingRunListEntry}</li>
 * </ul>
 *
 * <h3>第五轮 MUST-FIX 反测（fd714484 五项 + 边界约束 msg 11764111）</h3>
 * <ul>
 *   <li>①SSCAN COUNT 只是提示、游标不透明（旧 ceil(N/128) 声明不成立）→ 改为 ZSET 窗口轮转：
 *       ZRANGE 带 LIMIT 是构造性硬预算；进展保证两档——成员固定时 ceil(N/budget) 确定性上界
 *       （{@link #ghostPurgeShouldMakeProgressEvenWhenFirstBudgetWindowIsAllLive}），
 *       有并发写入时只保证硬预算 + 持续进展、不承诺圈数
 *       （{@link #ghostPurgeUnderConcurrentMutationShouldKeepHardBudgetAndProgress}）；
 *       脚本形态守卫 {@link #claimAndAddScriptsShouldUseZrangeWindowRotationNotSmembers}</li>
 *   <li>②游标键被短 TTL 候选覆盖 → 游标键整体废除：轮转状态编码在 score 排序本身，
 *       序号键 run-seq 与索引键同滑动过期；序号键丢失不丢数据不报错——第六轮起发号前
 *       先抬 seq 至当前最大 score，排序与持续推进也不降级
 *       （{@link #seqKeyLossShouldDegradeWithoutDataLossOrErrors}）</li>
 *   <li>③测试 fake 不是 Redis 语义 → 本文件 fake 全量重写：ZSET 按 (score 升序, 成员字典序)
 *       排序，INCRBY 保留键 TTL（Redis 语义），extendOnly 对 -2 no-op、对既有永久键（-1）
 *       保持永久绝不缩短、对本次新建键补设有限 TTL、既有有限键只延长，
 *       五种脚本按 ARGV 个数分发且共用一把锁模拟 Redis 单线程原子执行</li>
 *   <li>④TTL 归一化分裂（meta 12h vs 索引 1h → 双重认领）→ 唯一归一化点 effectiveTtlHours
 *       （{@link #ttlNormalizationShouldApplyDefaultBeforeMetaAndAllScriptArgs}）；
 *       EXISTS 分支 TTL 刷新取赢家 meta 自身剩余 TTL、绝不取输家 ARGV
 *       （{@link #existsBranchShouldRefreshIndexTtlFromWinnerMetaNotLoserArgv}）</li>
 *   <li>⑤touch 非原子 / 吞异常 / 不更新 expiresAtMillis → cleanup 误删刚续期制品 →
 *       单条原子 touch 脚本同时更新 lastAccessAtMillis 与 expiresAtMillis、返回状态码、
 *       Java 侧绝不吞异常（{@link #touchShouldUpdateExpiresAtMillisAndSurviveCleanup}
 *       / {@link #touchScriptStatusCodesShouldDriveJavaExceptions}）；cleanup 判定改为
 *       Lua 读回当前 JSON 的原子判决，损坏/无日期 meta 绝不盲删
 *       （{@link #cleanupShouldLeaveMalformedOrUndatedMetaUntouched}）</li>
 *   <li>边界约束 2：score 不是毫秒时间而是每 run 单调序号，同毫秒注册也严格单调 ——
 *       {@link #scoresShouldBeStrictlyMonotonicEvenForSameMillisecondRegistrations}</li>
 *   <li>边界约束 1/3：已检查活成员严格移到未检查成员之后（进展）、v4 原子合同
 *       （身份 CAS → 清理 → 容量检查 → 写入单脚本）保留 —— 见窗口轮转与第二轮各组反测</li>
 * </ul>
 *
 * <h3>第六轮 MUST-FIX 反测（1a74ca02 四项）</h3>
 * <ul>
 *   <li>①touch 状态码 2 必须"失败且零副作用"：身份冲突只读预检先于一切可见写入，
 *       失败前后 meta 原文、meta TTL、run 列表 score、seq 值全量不变 ——
 *       {@link #touchScriptStatusCodesShouldDriveJavaExceptions}（状态码 2 段）</li>
 *   <li>②run-seq 丢失后持续推进不降级：每次发号前把 seq 原子抬到至少当前 ZSET 最大
 *       score（floorSeqToTopScore）再 INCRBY ——
 *       {@link #seqLossWithHighScoresAndGhostTailShouldStillAdvance}（N&gt;128 + 高分 +
 *       删 seq + 后段 ghost：幸存者严格大于未检查最大值、声明轮数内清完）/
 *       {@link #touchAfterSeqLossShouldMoveMemberToTrueTail}（touch 移到真正队尾）</li>
 *   <li>③永久 TTL(-1) 既有保持永久 + 本次新建获得有限 TTL 两向 ——
 *       {@link #extendOnlyShouldKeepPreexistingPersistentKeysAndGiveNewKeysTtl}</li>
 *   <li>④Testcontainers 集成测试默认禁用门禁归 agentPlatformShared/pom.xml
 *       （surefire excludes + redis-integration opt-in profile），本文件不涉及</li>
 * </ul>
 *
 * <h3>v7 MUST-FIX 反测（77a272a7：空 ZSET 自动删 key + 脚本内重建）</h3>
 * <ul>
 *   <li>③延续——真实 Redis 中 ZSET 最后一个成员被 ZREM 时 key 当场自动删除；认领/加入
 *       脚本若在幽灵清理后沿用入口存在性快照，重建的列表键会被误判为既有键而漏掉
 *       TTL（永久键泄漏）。v7 在清理后即时重判，重建键按本次新建处理获有限 TTL ——
 *       {@link #addScriptShouldGiveFiniteTtlToListRebuiltAfterPurgingLastGhost}（加入，
 *       有限起点）/
 *       {@link #addScriptShouldNotInheritPermanenceForListRebuiltAfterPurgingLastGhost}
 *       （加入，永久起点）/
 *       {@link #claimScriptShouldGiveFiniteTtlToListRebuiltAfterPurgingLastGhost}（认领，
 *       有限起点）/
 *       {@link #claimScriptShouldNotInheritPermanenceForListRebuiltAfterPurgingLastGhost}
 *       （认领，永久起点）</li>
 * </ul>
 *
 * <p>Redis 用线程安全内存 fake（ConcurrentHashMap 三张表：values 字符串 / hashes 哈希 /
 * zsets 有序集合，支持真线程并发测试；五种 Lua 脚本——过期清理判定（1 ARGV）、
 * 值条件 HDEL（2 ARGV）、读取 touch（4 ARGV）、列表加入（5 ARGV）、幂等认领（6 ARGV）——
 * 的 execute() 按 ARGV 个数分发，共用一把锁模拟 Redis 单线程原子执行。fake 另带
 * 可控时钟 + 每键 deadline（惰性过期，INCRBY 保留键 TTL 与真实 Redis 一致），
 * 支撑滑动过期、窗口轮转进展与序号降级的反测），文件落 @TempDir；
 * 不碰生产 DB/Redis/Nacos。</p>
 */
class PersistentArtifactRegistryTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";
    private static final String RUN_IDENTITY_PREFIX = META_PREFIX + "run-identity:";
    private static final String RUN_SEQ_PREFIX = META_PREFIX + "run-seq:";

    @TempDir
    Path tempDir;

    private Map<String, String> values;
    private Map<String, Map<String, String>> hashes;
    /**
     * run 列表 fake（ZSET 语义）：键 → (成员 artifactId → score)。score = 每 run 一把
     * 单调序号（run-seq 键 INCRBY 发号），不是毫秒时间。窗口轮转把已检查的活成员重新
     * 打分到所有未检查成员之后——轮转状态编码在 score 排序本身，没有独立游标键。
     */
    private Map<String, Map<String, Double>> zsets;
    /**
     * fake 时钟 + 每键过期时刻（millis）。统一滑动过期协议的反测需要可控时间：
     * 注册/读取按 fakeNow 记录 TTL 截止时间，advanceClock 推进后由 sweepExpired
     * 惰性清除过期键（与真实 Redis 惰性/定期过期删除的可观察语义一致）。
     * 直接 values.put 而不记录 deadline 的键视为无 TTL（持久），与 Redis PERSIST 等价。
     * 注意：meta JSON 内的 expiresAtMillis 字段用真实墙钟（生产 buildMeta/touch/cleanup
     * 都用 System.currentTimeMillis()），与本 fake 的 Redis-TTL 时钟是两套独立语义。
     */
    private long fakeNow;
    private Map<String, Long> deadlines;
    /**
     * 模拟 Redis 单线程执行：所有 Lua 脚本 fake（认领/加入/touch/清理判定/值条件 HDEL）
     * 共用这一把锁，保证任一脚本执行期间没有其他脚本插入——这是真实 Redis 原子性的
     * 最小等价模拟。
     */
    private final Object redisLock = new Object();
    private PersistentArtifactRegistry registry;
    private Path artifactRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        zsets = new ConcurrentHashMap<>();
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

        // 强制过期：回写 meta 的 expiresAtMillis 到过去（模拟生效期已过）
        PersistentArtifactMeta meta = registry.find(artifactId).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        values.put(metaKey, new ObjectMapper().writeValueAsString(meta));

        registry.cleanupExpiredArtifacts();

        assertFalse(values.containsKey(metaKey), "meta 必须删除");
        assertFalse(Files.exists(file), "文件必须删除");
        assertTrue(zsets.getOrDefault(RUN_LIST_PREFIX + "run-1", Map.of()).isEmpty(),
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
                        && !k.startsWith(RUN_SEQ_PREFIX))
                .count();
        assertEquals(2, metaCount, "被拒注册不得残留 meta");
        try (var paths = Files.list(artifactRoot.resolve("raw-ref"))) {
            assertEquals(2, paths.count(), "被拒注册不得残留文件");
        }
        // 索引项与 meta 一一对应（无孤儿索引项）
        for (String id : zsets.getOrDefault(RUN_LIST_PREFIX + "run-cap", Map.of()).keySet()) {
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

        Set<String> listed = new HashSet<>(
                zsets.getOrDefault(RUN_LIST_PREFIX + "run-ccap", Map.of()).keySet());
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
                        && !k.startsWith(RUN_SEQ_PREFIX))
                .map(k -> k.substring(META_PREFIX.length()))
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(okIds), metaIds, "被拒注册不得残留 meta");
        // 竞态后索引未满时注册必须恢复（无永久阻塞）
        if (listed.size() < 3) {
            PersistentArtifactRegistration after = registry.registerExplicit(
                    "run-ccap", "user-1", "raw-ref", "after", "9", "after", 6);
            assertTrue(zsets.get(RUN_LIST_PREFIX + "run-ccap").containsKey(after.getArtifactId()),
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
                        && !k.startsWith(RUN_SEQ_PREFIX))
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

    @Test
    void diagnosticByteReadShouldNotTouchRegistryOrIndexState() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-diagnostic-read", "user-1", "report", "r1", "报告", "payload", 6);

        Map<String, String> valuesBefore = new LinkedHashMap<>(values);
        Map<String, Map<String, String>> hashesBefore = hashes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue()),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<String, Double>> zsetsBefore = zsets.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue()),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Long> deadlinesBefore = new LinkedHashMap<>(deadlines);

        assertEquals("payload", new String(registry.readArtifactBytes(
                registration.getArtifactId(), -1L, false), StandardCharsets.UTF_8));

        assertEquals(valuesBefore, values, "诊断读取不得改 meta 或 seq 值");
        assertEquals(hashesBefore, hashes, "诊断读取不得改 identity hash");
        assertEquals(zsetsBefore, zsets, "诊断读取不得重排 run 索引");
        assertEquals(deadlinesBefore, deadlines, "诊断读取不得刷新任何 Redis TTL");
    }

    @Test
    void diagnosticByteReadShouldStillEnforceSizeAndHashChecks() throws Exception {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-diagnostic-guards", "user-1", "report", "r1", "报告", "payload", 6);

        IllegalStateException oversized = assertThrows(IllegalStateException.class,
                () -> registry.readArtifactBytes(registration.getArtifactId(), 6L, false));
        assertTrue(oversized.getMessage().contains("too large"), oversized.getMessage());

        Path path = Path.of(registry.find(registration.getArtifactId()).orElseThrow().getPath());
        Files.writeString(path, "tampered");
        IllegalStateException tampered = assertThrows(IllegalStateException.class,
                () -> registry.readArtifactBytes(registration.getArtifactId(), -1L, false));
        assertTrue(tampered.getMessage().contains("hash mismatch"), tampered.getMessage());
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
        // 后写列表"在列表容量失败时会留下"身份在、列表没进"的幽灵半成品；
        // 新实现 FULL 路径不写任何索引
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerIdempotent(
                        "run-full", "user-1", "python_script", "blocked", "脚本", "x", 6));
        assertTrue(e.getMessage().contains("capacity exceeded"), e.getMessage());

        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + "run-full");
        assertTrue(identity == null || identity.isEmpty(),
                "FULL 路径不得写身份字段，否则输家会拿到幽灵 ID");
        assertEquals(Set.of(first.getArtifactId()),
                new HashSet<>(zsets.get(RUN_LIST_PREFIX + "run-full").keySet()),
                "索引只含合法成员");
        long metaCount = values.keySet().stream()
                .filter(k -> k.startsWith(META_PREFIX)
                        && !k.startsWith(RUN_LIST_PREFIX) && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_SEQ_PREFIX))
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
        assertTrue(zsets.get(RUN_LIST_PREFIX + "run-adopt").containsKey(winner.getArtifactId()),
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
        String runId = "run-ghost";
        String listKey = RUN_LIST_PREFIX + runId;
        // 预置 2 个幽灵成员：在 ZSET 里但 meta 键已不存在（典型成因：meta 的 Redis TTL 到期）。
        // 直接播种成员时必须同步播种 run-seq 键值 = 已用最大 score（INCRBY 才不会重发旧号）。
        Map<String, Double> zset = zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>());
        zset.put("raw-ref:ghost-1", 1.0);
        zset.put("raw-ref:ghost-2", 2.0);
        values.put(RUN_SEQ_PREFIX + runId, "2");

        // cap"名义已满"（ZCARD==2）但全是幽灵：幂等认领经窗口轮转清理后必须恢复
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "alive", "alive", "payload", 6);

        assertNotNull(registration.getArtifactId());
        assertEquals(Set.of(registration.getArtifactId()),
                new HashSet<>(zsets.get(listKey).keySet()),
                "幽灵必须被清掉，索引只含新赢家");
        assertEquals(1, registry.listByRunId(runId).size());
        Map<String, String> identity = hashes.get(RUN_IDENTITY_PREFIX + runId);
        assertEquals(registration.getArtifactId(),
                identity.get(PersistentArtifactRegistry.identityField("raw-ref", "alive", null)));
    }

    // ===== 有界索引 / 陈旧索引自愈 / cleanup 键跳过 =====

    @Test
    void listByRunIdShouldFilterStaleIndexEntries() {
        registry.registerExplicit("run-stale", "user-1", "raw-ref", "a", "1", "one", 6);
        // 陈旧索引项：指向不存在的 artifactId（如外部直接改过索引）
        zsets.computeIfAbsent(RUN_LIST_PREFIX + "run-stale", k -> new ConcurrentHashMap<>())
                .put("raw-ref:ghost", 2.0);
        values.put(RUN_SEQ_PREFIX + "run-stale", "2");

        List<PersistentArtifactMeta> listed = registry.listByRunId("run-stale");
        assertEquals(1, listed.size(), "陈旧索引项必须被滤掉而不是让 list 失败");
        // 读取侧不仅过滤，还顺手 ZREM 幽灵成员
        assertFalse(zsets.get(RUN_LIST_PREFIX + "run-stale").containsKey("raw-ref:ghost"),
                "幽灵成员必须在读取遍历时被移除");
    }

    @Test
    void diagnosticListShouldFilterGhostWithoutRemovingIt() {
        registry.registerExplicit("run-diagnostic", "user-1", "raw-ref", "a", "1", "one", 6);
        String listKey = RUN_LIST_PREFIX + "run-diagnostic";
        zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>())
                .put("raw-ref:ghost", 2.0);

        List<PersistentArtifactMeta> listed = registry.listByRunId("run-diagnostic", false);

        assertEquals(1, listed.size(), "诊断列表仍应返回存在的 meta");
        assertTrue(zsets.get(listKey).containsKey("raw-ref:ghost"),
                "诊断列表只能过滤幽灵，不得 ZREM 修改 Redis");
    }

    @Test
    void cleanupShouldSkipIndexKeysMatchingMetaScanPattern() throws Exception {
        // run 索引/身份/序号键与 meta 共享前缀，cleanup SCAN 会命中：必须显式跳过、不误删。
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
        // run 序号键同样共享前缀：同样必须跳过
        values.put(RUN_SEQ_PREFIX + "run-skip", expiredJson);

        registry.cleanupExpiredArtifacts();

        // 真 meta 被清，索引键原样保留（即使它们误存了可解析 JSON 也不得按 meta 处理）
        assertFalse(values.containsKey(metaKey));
        assertTrue(values.containsKey(RUN_LIST_PREFIX + "run-skip"));
        assertTrue(values.containsKey(RUN_IDENTITY_PREFIX + "run-skip"));
        assertTrue(values.containsKey(RUN_SEQ_PREFIX + "run-skip"));
    }

    // ===== 第三轮 MUST-FIX ①（第五轮重写）：有界幽灵清理必须真「有界且保证进展」 =====

    @Test
    void ghostPurgeShouldMakeProgressEvenWhenFirstBudgetWindowIsAllLive() {
        // codex 62ad12bd ① + 边界约束 1（msg 11764111）反测：cap > 清理预算（128），
        // 第一个预算窗口全是活成员、幽灵排在后面——旧 SET+游标实现每次重复检查同一批
        // 活成员，幽灵永远扫不到。ZSET 窗口轮转协议：窗口取当前 score 最低的至多 128
        // 个成员（ZRANGE LIMIT 构造性硬上限），活成员用新发序号重打分、严格移到所有
        // 未检查成员之后——轮转状态就在 score 排序里，没有独立游标键。成员集合固定时
        // 进展有确定性上界：至多 ceil(成员总数/预算) 次索引写入内清完所有幽灵。
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 200);
        String runId = "run-progress";
        String listKey = RUN_LIST_PREFIX + runId;
        String seqKey = RUN_SEQ_PREFIX + runId;
        // 播种 199 个活成员（meta 在 values 表 = EXISTS 为真，无 deadline = 持久）+ 1 个
        // 幽灵（只有 ZSET 成员、无 meta，score 最高 = 排在最后）。直接播种必须同步播种
        // seq 键值 = 已用最大 score。
        Map<String, Double> zset = zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>());
        for (int i = 1; i <= 199; i++) {
            String id = "raw-ref:live-" + String.format("%03d", i);
            values.put(META_PREFIX + id, "{}");
            zset.put(id, (double) i);
        }
        zset.put("raw-ref:zz-ghost", 200.0);
        values.put(seqKey, "200");
        assertEquals(200, zset.size());

        // 第 1 次注册：窗口 [score 最低的 128 个] = live-001..live-128，全是活成员、
        // 无幽灵可清 → ZCARD 仍 200 ≥ cap → FULL。关键是轮转必须真实发生并留下可验证
        // 的状态：128 个被检查的活成员重打分到所有未检查成员之后（score 严格 > 200），
        // 未检查成员（live-129..live-199 与幽灵）score 原样不动。
        IllegalStateException full = assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit(runId, "user-1", "raw-ref", "new", "new", "payload", 6));
        assertTrue(full.getMessage().contains("capacity exceeded"), full.getMessage());
        assertTrue(zset.containsKey("raw-ref:zz-ghost"), "第一窗口扫不到幽灵，不得误清活成员");
        assertEquals(201.0, zset.get("raw-ref:live-001"), "被检查活成员必须重打分到未检查成员之后");
        assertEquals(328.0, zset.get("raw-ref:live-128"), "被检查活成员必须重打分到未检查成员之后");
        assertEquals(129.0, zset.get("raw-ref:live-129"), "未检查成员 score 必须原样不动");
        assertEquals(200.0, zset.get("raw-ref:zz-ghost"), "未检查成员 score 必须原样不动");
        long rechecked = zset.values().stream().filter(s -> s > 200.0).count();
        assertEquals(128, rechecked, "单次执行重打分的成员数恰好 = 硬预算 128，不得多检");
        assertEquals("328", values.get(seqKey), "轮转发号必须持久化在 run-seq 键");

        // 第 2 次注册：窗口 = 128 个最低 score = live-129..live-199（71）+ 幽灵（1）
        // + live-001..live-056（56）→ 幽灵当场 ZREM → ZCARD 199 < 200 → 注册恢复。
        PersistentArtifactRegistration registration = registry.registerExplicit(
                runId, "user-1", "raw-ref", "new", "new", "payload", 6);
        assertNotNull(registration.getArtifactId());
        Map<String, Double> after = zsets.get(listKey);
        assertEquals(200, after.size(), "199 活成员 + 新制品，幽灵已清");
        assertTrue(after.containsKey(registration.getArtifactId()));
        assertFalse(after.containsKey("raw-ref:zz-ghost"), "幽灵必须在有界次数的写入内被清掉");
        assertEquals(456.0, after.get(registration.getArtifactId()), "新成员以新发序号入列");
        assertEquals("456", values.get(seqKey));
        // 进展有界性：200 成员、预算 128 → ceil(200/128)=2 次索引写入内必然恢复（本测恰好 2 次）
    }

    @Test
    void ghostPurgeUnderConcurrentMutationShouldKeepHardBudgetAndProgress() {
        // 边界约束 1 第②档反测：有并发写入/删除时不承诺圈数上界，但每次执行的硬预算
        // （至多检查 budget 个成员）与持续进展（窗口内的幽灵当场清除）必须成立。
        // 场景：第 1 次注册 FULL 后、第 2 次注册前，外部注入 30 个新幽灵（score 最低，
        // 模拟并发注册留下的死成员）——第 2 次窗口必须把它们连同原幽灵一起清掉。
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 200);
        String runId = "run-mutate";
        String listKey = RUN_LIST_PREFIX + runId;
        String seqKey = RUN_SEQ_PREFIX + runId;
        Map<String, Double> zset = zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>());
        for (int i = 1; i <= 199; i++) {
            String id = "raw-ref:live-" + String.format("%03d", i);
            values.put(META_PREFIX + id, "{}");
            zset.put(id, (double) i);
        }
        zset.put("raw-ref:zz-ghost", 200.0);
        values.put(seqKey, "200");

        // 第 1 次注册 FULL（窗口全活成员，轮转后 ZCARD 仍 200）
        assertThrows(IllegalStateException.class,
                () -> registry.registerExplicit(runId, "user-1", "raw-ref", "new", "new", "payload", 6));
        assertEquals("328", values.get(seqKey));

        // 并发注入 30 个新幽灵：score 0.1..3.0（低于一切现有成员）、无 meta
        for (int i = 1; i <= 30; i++) {
            zset.put("raw-ref:inj-" + String.format("%02d", i), i / 10.0);
        }

        // 第 2 次注册：窗口 = 30 注入幽灵 + live-129..live-199（71）+ zz-ghost
        // + live-001..live-026（26）= 恰好 128。31 个幽灵当场清除，97 个活成员重打分
        // （硬预算内），ZCARD 169 < 200 → 恢复。
        PersistentArtifactRegistration registration = registry.registerExplicit(
                runId, "user-1", "raw-ref", "new", "new", "payload", 6);
        assertNotNull(registration.getArtifactId(), "注入幽灵后注册必须在硬预算内恢复");
        Map<String, Double> after = zsets.get(listKey);
        assertEquals(200, after.size(), "199 活成员 + 新制品");
        assertFalse(after.containsKey("raw-ref:zz-ghost"));
        for (int i = 1; i <= 30; i++) {
            assertFalse(after.containsKey("raw-ref:inj-" + String.format("%02d", i)),
                    "窗口内的注入幽灵必须当场清除");
        }
        // 硬预算可验证：本轮 seq 增量 = 重打分 97 + 新成员 1 = 98，重打分数 97 ≤ 128
        assertEquals("426", values.get(seqKey), "本轮重打分成员数恰为窗口内活成员数 97（≤ 硬预算 128）");
    }

    @Test
    void claimAndAddScriptsShouldUseZrangeWindowRotationNotSmembers() throws Exception {
        // codex 62ad12bd ① + 第五轮 ① 反测（脚本形态守卫）：幽灵清理必须走 ZRANGE 窗口
        // 轮转——单次脚本执行只取有界数量的成员（LIMIT 是构造性硬上限，不是 COUNT 式提示），
        // 绝不 SMEMBERS/SSCAN 全量或提示式扫描；轮转状态编码在 score 排序，没有游标键。
        // v6 第六轮 ②：所有发号脚本必须带 floorSeqToTopScore（ZREVRANGE 有界读末尾 1 项，
        // 发号前抬 seq 至当前最大 score）；第六轮 ①：touch 的身份冲突判断（HGET）必须
        // 出现在第一笔可见写入（SET meta）之前。
        @SuppressWarnings("unchecked")
        DefaultRedisScript<String> claim = (DefaultRedisScript<String>) ReflectionTestUtils.getField(
                PersistentArtifactRegistry.class, "ATOMIC_CLAIM_SCRIPT");
        @SuppressWarnings("unchecked")
        DefaultRedisScript<String> add = (DefaultRedisScript<String>) ReflectionTestUtils.getField(
                PersistentArtifactRegistry.class, "RUN_LIST_ADD_SCRIPT");
        @SuppressWarnings("unchecked")
        DefaultRedisScript<Long> touch = (DefaultRedisScript<Long>) ReflectionTestUtils.getField(
                PersistentArtifactRegistry.class, "TOUCH_SCRIPT");
        String claimText = claim.getScriptAsString();
        String addText = add.getScriptAsString();
        String touchText = touch.getScriptAsString();

        assertTrue(claimText.contains("zrange"), "认领脚本必须用 ZRANGE 窗口轮转清理");
        assertTrue(claimText.contains("zrem"), "认领脚本必须当场移除幽灵成员");
        assertTrue(claimText.contains("zcard"), "认领脚本必须做 ZCARD 容量检查");
        assertTrue(claimText.contains("incrby"), "认领脚本必须用 INCRBY 单调序号重打分");
        assertFalse(claimText.toLowerCase().contains("smembers"), "认领脚本不得全量取出集合");
        assertFalse(claimText.toLowerCase().contains("sscan"), "认领脚本不得用提示式游标扫描");
        assertFalse(claimText.toLowerCase().contains("scard"), "认领脚本不得用 SCARD");
        assertFalse(claimText.toLowerCase().contains("cursor"), "轮转不得依赖独立游标键");
        assertTrue(claimText.contains("KEYS[3]"), "认领脚本必须把 run-seq 键作为 KEYS 传入");

        assertTrue(addText.contains("zrange"), "加入脚本必须用 ZRANGE 窗口轮转清理");
        assertTrue(addText.contains("zrem"), "加入脚本必须当场移除幽灵成员");
        assertTrue(addText.contains("zcard"), "加入脚本必须做 ZCARD 容量检查");
        assertTrue(addText.contains("incrby"), "加入脚本必须用 INCRBY 单调序号发号");
        assertFalse(addText.toLowerCase().contains("smembers"), "加入脚本不得全量取出集合");
        assertFalse(addText.toLowerCase().contains("sscan"), "加入脚本不得用提示式游标扫描");
        assertFalse(addText.toLowerCase().contains("cursor"), "轮转不得依赖独立游标键");
        assertTrue(addText.contains("KEYS[2]"), "加入脚本必须把 run-seq 键作为 KEYS 传入");

        // v6 第六轮 ②：三个发号脚本都必须带 floorSeqToTopScore（ZREVRANGE 有界读末尾
        // 1 项抬 seq）——seq 单键丢失后持续推进不降级的构造性保证
        assertTrue(claimText.contains("zrevrange"), "认领脚本发号前必须抬 seq 至当前 ZSET 最大 score");
        assertTrue(addText.contains("zrevrange"), "加入脚本发号前必须抬 seq 至当前 ZSET 最大 score");
        assertTrue(touchText.contains("zrevrange"), "touch 发号前必须抬 seq 至当前 ZSET 最大 score");
        // v6 第六轮 ①：touch 的身份冲突判断（hget）必须先于第一笔可见写入（set meta）——
        // 文本序守卫 + 行为零副作用断言见 touchScriptStatusCodesShouldDriveJavaExceptions
        int conflictCheckAt = touchText.indexOf("hget");
        int firstWriteAt = touchText.indexOf("redis.call('set'");
        assertTrue(conflictCheckAt >= 0, "touch 脚本必须做身份冲突判断（HGET）");
        assertTrue(firstWriteAt >= 0, "touch 脚本必须 SET 新 meta");
        assertTrue(conflictCheckAt < firstWriteAt,
                "touch 的身份冲突判断必须先于第一笔可见写入（返回 2 零副作用）");
        assertTrue(touchText.contains("hsetnx"), "touch 仍须以 HSETNX 补建丢失的身份项（严格赢家身份）");
    }

    // ===== 第三轮 MUST-FIX ③（第五轮重写）：统一滑动过期协议（touch 同滑动 / 防索引先过期 / EXISTS 修复） =====

    @Test
    void touchShouldSlideIndexTtlsTogetherWithMeta() {
        // codex 62ad12bd ③ 反测（四类键版）：读取 touch 重写 meta（满额 TTL）的同时，
        // 必须把 run 列表 ZSET、幂等身份 hash、run 序号键按同一 ttlHours 一起滑动——
        // 否则一次读取就让 meta 活过索引，索引先过期后 list 丢条目、同一幂等身份可被
        // 第二次 CLAIMED。
        String runId = "run-slide";
        String seqKey = RUN_SEQ_PREFIX + runId;
        long t0 = fakeNow;
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "s", "1", "one", 6);
        String metaKey = META_PREFIX + registration.getArtifactId();

        // 注册后四类过期协议键（meta/列表/身份/序号）的 deadline 完全一致（脚本内同款 TTL 写入）
        assertEquals(t0 + TimeUnit.HOURS.toMillis(6), deadlines.get(metaKey));
        assertEquals(deadlines.get(metaKey), deadlines.get(RUN_LIST_PREFIX + runId),
                "注册后列表键 TTL 必须与 meta 对齐");
        assertEquals(deadlines.get(metaKey), deadlines.get(RUN_IDENTITY_PREFIX + runId),
                "注册后身份键 TTL 必须与 meta 对齐");
        assertEquals(deadlines.get(metaKey), deadlines.get(seqKey),
                "注册后序号键 TTL 必须与 meta 对齐");

        // 2 小时后读取：touch 必须把四类键一起滑回满额 6h（而不是只滑 meta）
        advanceClock(TimeUnit.HOURS.toMillis(2));
        assertEquals("one", registry.readContent(registration.getArtifactId()));
        long expected = t0 + TimeUnit.HOURS.toMillis(8);
        assertEquals(expected, deadlines.get(metaKey), "meta 必须滑动回满额");
        assertEquals(expected, deadlines.get(RUN_LIST_PREFIX + runId), "列表键必须随 touch 同滑动");
        assertEquals(expected, deadlines.get(RUN_IDENTITY_PREFIX + runId), "身份键必须随 touch 同滑动");
        assertEquals(expected, deadlines.get(seqKey), "序号键必须随 touch 同滑动");
    }

    @Test
    void slidTtlShouldPreventIndexExpiryBeforeMetaAndDoubleClaim() throws Exception {
        // codex 62ad12bd ③ 反测（可控时钟/TTL 全链路）：注册后读取一次（touch 滑动），
        // 再推进时钟越过「原始」过期时刻——滑动过的 meta 与索引必须都还活着；此时同一
        // 幂等身份再次注册必须采纳原赢家（EXISTS），绝不允许索引先于 meta 过期导致
        // 同一身份第二次 CLAIMED 出新 ID。EXISTS 分支的 TTL 刷新只取赢家 meta 键自身
        // 剩余 TTL——即使输家传入更长 TTL，索引 deadline 也不得被拉长（第五轮 ④）。
        // 最后推进越过滑动后的 deadline，全部过期后重新认领必须拿到全新 ID。
        String runId = "run-drift";
        String seqKey = RUN_SEQ_PREFIX + runId;
        long t0 = fakeNow;
        PersistentArtifactRegistration first = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v1", 2);

        // 推进 1h 后读取 → touch 把 meta/列表/身份/序号全部滑动到 t0+1h+2h
        advanceClock(TimeUnit.HOURS.toMillis(1));
        assertEquals("v1", registry.readContent(first.getArtifactId()));
        long slidDeadline = t0 + TimeUnit.HOURS.toMillis(3);
        assertEquals(slidDeadline, deadlines.get(META_PREFIX + first.getArtifactId()));

        // 再推进 1.5h：fakeNow = t0+2.5h，已越过原始过期时刻 t0+2h，但未越过滑动后的
        // t0+3h——meta 与三类索引键必须全部存活
        advanceClock(TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(30));
        PersistentArtifactRegistration second = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v2", 2);
        assertEquals(first.getArtifactId(), second.getArtifactId(),
                "索引随 meta 滑动后，同一身份必须采纳原赢家，不得第二次 CLAIMED");
        // EXISTS 刷新取赢家剩余 0.5h：deadline 保持 t0+3h 原样（输家 2h ARGV 会拉到
        // t0+4.5h，短 TTL 输家也绝不缩短——两个错误方向都被钉死）。必须在任何读取
        // 之前断言：读取自身会 touch 满额滑动四类键，那是合法的另一路行为。
        assertEquals(slidDeadline, deadlines.get(RUN_LIST_PREFIX + runId),
                "EXISTS 刷新只取赢家 meta 剩余 TTL，索引 deadline 不得变化");
        assertEquals(slidDeadline, deadlines.get(RUN_IDENTITY_PREFIX + runId));
        assertEquals(slidDeadline, deadlines.get(seqKey));
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
        // 序号键随索引一起过期后发号从 1 重来：新成员 score = 1、seq 值 "1"
        //（降级不丢数据）。必须在读取之前断言：读取 touch 会把成员 score 同步到
        // 新发序号（队尾），那是合法的另一路行为。
        assertEquals(1.0, zsets.get(RUN_LIST_PREFIX + runId).get(third.getArtifactId()));
        assertEquals("1", values.get(seqKey));
        assertEquals("v3", registry.readContent(third.getArtifactId()));
        assertEquals(1, registry.listByRunId(runId).size());
    }

    @Test
    void existsBranchShouldRepairMissingRunListEntry() {
        // codex 62ad12bd ③ 反测：身份字段在场、赢家的 run 列表成员资格却丢失时
        // （列表键早于身份过期或被外力移除），EXISTS 分支不得只做透传——必须当场校验
        // 并修复列表成员资格（以新发序号 ZADD 补回），否则输家采纳的赢家在用户列表里
        // 不可见。
        String runId = "run-repair";
        String seqKey = RUN_SEQ_PREFIX + runId;
        PersistentArtifactRegistration winner = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v1", 6);
        // 模拟列表成员资格丢失：真实 Redis 不存在「空 ZSET 仍存在」的状态——最后一个
        // 成员被移除时 key 当场自动删除（v7 严格语义），故等价状态是「列表键整体缺失」
        // （列表键早于身份过期或被外力移除），身份字段仍指向赢家
        zsets.remove(RUN_LIST_PREFIX + runId);
        deadlines.remove(RUN_LIST_PREFIX + runId);
        assertFalse(zsets.containsKey(RUN_LIST_PREFIX + runId));
        assertEquals(winner.getArtifactId(),
                hashes.get(RUN_IDENTITY_PREFIX + runId).get(
                        PersistentArtifactRegistry.identityField("python_script", "shared", null)));

        // 同一身份再次注册 → EXISTS 分支：修复成员资格 + 采纳赢家
        PersistentArtifactRegistration adopted = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v2", 6);
        assertEquals(winner.getArtifactId(), adopted.getArtifactId());
        assertTrue(zsets.get(RUN_LIST_PREFIX + runId).containsKey(winner.getArtifactId()),
                "EXISTS 分支必须把赢家修复回 run 列表");
        // 修复用新发序号（单调链不断）：原 score 1 已随成员移除，补回 score = 2
        assertEquals(2.0, zsets.get(RUN_LIST_PREFIX + runId).get(winner.getArtifactId()),
                "修复成员资格必须以新发序号 ZADD，不得复用旧 score");
        assertEquals("2", values.get(seqKey));
        assertEquals(1, registry.listByRunId(runId).size(), "用户列表必须重新看见赢家");
    }

    // ===== 第五轮 MUST-FIX ④：TTL 归一化唯一权威点 + EXISTS 刷新取赢家 TTL =====

    @Test
    void ttlNormalizationShouldApplyDefaultBeforeMetaAndAllScriptArgs() {
        // 第五轮 ④ 反测：修复前 buildMeta 用 defaultTtlHours 归一、脚本只 max(1) 不补
        // 默认值——ttlHours=0 的制品 meta 记 12h 而索引键只设 1h，索引先过期后同一身份
        // 可被双重认领。修复后唯一归一化点 effectiveTtlHours 先补默认再 clamp：
        // meta.expiresAtMillis / meta.ttlHours / meta 键 TTL / 全部脚本 TTL ARGV 同源。
        String runId = "run-ttl-norm";
        String seqKey = RUN_SEQ_PREFIX + runId;
        long t0 = fakeNow;
        long defaultMillis = TimeUnit.HOURS.toMillis(12);

        // ttlHours=0 → 生效时长 = defaultTtlHours = 12，四类键 deadline 全部 t0+12h
        PersistentArtifactRegistration zero = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "zero", "1", "one", 0);
        String zeroMetaKey = META_PREFIX + zero.getArtifactId();
        PersistentArtifactMeta zeroMeta = registry.find(zero.getArtifactId()).orElseThrow();
        assertEquals(12L, zeroMeta.getTtlHours(), "meta.ttlHours 必须是归一化后的生效时长");
        assertEquals(TimeUnit.HOURS.toMillis(12),
                zeroMeta.getExpiresAtMillis() - zeroMeta.getCreatedAtMillis(),
                "expiresAtMillis 必须与 createdAtMillis 恰好差一个生效时长（同源零漂移）");
        assertEquals(t0 + defaultMillis, deadlines.get(zeroMetaKey), "meta 键 TTL 必须 = 12h");
        assertEquals(t0 + defaultMillis, deadlines.get(RUN_LIST_PREFIX + runId), "列表键 TTL 必须 = 12h");
        assertEquals(t0 + defaultMillis, deadlines.get(RUN_IDENTITY_PREFIX + runId), "身份键 TTL 必须 = 12h");
        assertEquals(t0 + defaultMillis, deadlines.get(seqKey), "序号键 TTL 必须 = 12h");

        // ttlHours 为负同样落入默认值（绝不允许 <=0 变成"永不过期"或 1h）
        PersistentArtifactRegistration neg = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "neg", "2", "two", -5);
        PersistentArtifactMeta negMeta = registry.find(neg.getArtifactId()).orElseThrow();
        assertEquals(12L, negMeta.getTtlHours(), "负 ttlHours 必须归一化为默认 12h");
        assertEquals(t0 + defaultMillis, deadlines.get(META_PREFIX + neg.getArtifactId()));
    }

    @Test
    void existsBranchShouldRefreshIndexTtlFromWinnerMetaNotLoserArgv() {
        // 第五轮 ④ 反测：EXISTS 分支的索引 TTL 刷新时长必须取赢家 meta 键自身的剩余
        // TTL（TTL 命令读回），绝不取输家传入的 ARGV——否则短 TTL 输家改短赢家索引、
        // 长 TTL 输家又把赢家索引拉得比 meta 更久，两个方向都产生漂移。
        // 构造：赢家 3h 注册、推进 1h（赢家 meta 剩余 2h），人为把三类索引键的
        // deadline 压到只剩 30 分钟（模拟「索引 TTL 比 meta 短」的漂移状态——这正是
        // 本反测要消灭的故障：索引先于 meta 过期 → 双重认领窗口），短 TTL 输家（1h）
        // 走 EXISTS 采纳——只延长不缩短的刷新必须把三类键全部延长回「赢家 meta
        // 剩余 2h」= 与 meta deadline 重新对齐。
        // v6 第六轮调整（1a74ca02 ③）：旧构造是摘掉 deadline 模拟 TTL 丢失后期望补回，
        // 但第六轮冻结口径是「既有永久键（无 TTL ≙ -1）保持永久、绝不缩短」，TTL 丢失
        // 场景归 {@link #extendOnlyShouldKeepPreexistingPersistentKeysAndGiveNewKeysTtl}
        // 两向反测；本测试改用「索引 deadline 比 meta 短」构造漂移，只延长语义下同样
        // 能区分刷新时长取自赢家剩余还是输家 ARGV。
        String runId = "run-winner-ttl";
        String seqKey = RUN_SEQ_PREFIX + runId;
        long t0 = fakeNow;
        PersistentArtifactRegistration winner = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v1", 3);
        long metaDeadline = t0 + TimeUnit.HOURS.toMillis(3);
        assertEquals(metaDeadline, deadlines.get(META_PREFIX + winner.getArtifactId()));

        advanceClock(TimeUnit.HOURS.toMillis(1));
        // 模拟索引键 TTL 漂移到比 meta 短（键仍在、deadline 只剩 30 分钟）
        long driftedDeadline = fakeNow + TimeUnit.MINUTES.toMillis(30);
        deadlines.put(RUN_LIST_PREFIX + runId, driftedDeadline);
        deadlines.put(RUN_IDENTITY_PREFIX + runId, driftedDeadline);
        deadlines.put(seqKey, driftedDeadline);

        // 输家传 1h TTL：若刷新取输家 ARGV，三类键 deadline 会落在 fakeNow+1h = t0+2h
        // （仍比 meta 短 1h，索引先于 meta 过期 → 双重认领窗口）；取赢家剩余 2h 才
        // 对齐回 t0+3h。
        PersistentArtifactRegistration loser = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v2", 1);
        assertEquals(winner.getArtifactId(), loser.getArtifactId(), "输家必须采纳赢家");
        assertEquals(metaDeadline, deadlines.get(RUN_LIST_PREFIX + runId),
                "EXISTS 刷新必须取赢家 meta 剩余 TTL（2h），列表键与 meta 重新对齐");
        assertEquals(metaDeadline, deadlines.get(RUN_IDENTITY_PREFIX + runId),
                "EXISTS 刷新必须取赢家 meta 剩余 TTL（2h），身份键与 meta 重新对齐");
        assertEquals(metaDeadline, deadlines.get(seqKey),
                "EXISTS 刷新必须取赢家 meta 剩余 TTL（2h），序号键与 meta 重新对齐");
        assertEquals("v1", registry.readContent(winner.getArtifactId()), "内容仍是赢家首次写入值");
    }

    // ===== 第五轮 MUST-FIX ⑤：touch 原子 + 更新 expiresAtMillis + 状态码绝不吞 =====

    @Test
    void touchShouldUpdateExpiresAtMillisAndSurviveCleanup() throws Exception {
        // 第五轮 ⑤ 反测：修复前 touch 只刷 Redis TTL 与 lastAccessAtMillis、不改
        // expiresAtMillis——cleanup 按 expiresAtMillis 判定，刚被读取续期的制品会被误删。
        // 修复后 touch 单条原子脚本同时把 expiresAtMillis 滑动到未来，cleanup 的 Lua
        // 判定读回当前 JSON 看到的正是新值 → 不删。
        String runId = "run-touch-cleanup";
        ObjectMapper mapper = new ObjectMapper();
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "keep", "1", "keep-me", 2);
        String artifactId = registration.getArtifactId();
        String metaKey = META_PREFIX + artifactId;
        Path file = Path.of(registration.getMeta().getPath());

        // 模拟"内容层面已到龄"：把 meta JSON 的 expiresAtMillis 改到过去。
        // （此时若直接 cleanup 会被删——这正是下一步读取必须扭转的局面。）
        PersistentArtifactMeta aged = registry.find(artifactId).orElseThrow();
        aged.setExpiresAtMillis(System.currentTimeMillis() - 1);
        values.put(metaKey, mapper.writeValueAsString(aged));

        // 读取（touch）必须把 expiresAtMillis 一并更新到未来——这是 ⑤ 的核心回归点
        assertEquals("keep-me", registry.readContent(artifactId));
        PersistentArtifactMeta touched = mapper.readValue(values.get(metaKey), PersistentArtifactMeta.class);
        assertTrue(touched.getExpiresAtMillis() > System.currentTimeMillis(),
                "touch 必须把 expiresAtMillis 滑动到未来，否则 cleanup 会误删刚读取的制品");
        assertTrue(touched.getLastAccessAtMillis() >= aged.getLastAccessAtMillis(),
                "lastAccessAtMillis 必须同步更新");

        // cleanup 此刻不得删除（Lua 判定读回的是 touch 后的新值）
        registry.cleanupExpiredArtifacts();
        assertTrue(values.containsKey(metaKey), "刚被 touch 续期的制品绝不被 cleanup 误删");
        assertTrue(Files.exists(file), "文件必须保留");
        assertEquals(1, registry.listByRunId(runId).size(), "run 索引项必须保留");

        // 真正到龄后（expiresAtMillis 改回过去），cleanup 才删除——同删 meta/文件/索引
        touched.setExpiresAtMillis(System.currentTimeMillis() - 1);
        values.put(metaKey, mapper.writeValueAsString(touched));
        registry.cleanupExpiredArtifacts();
        assertFalse(values.containsKey(metaKey), "真正到龄后 meta 必须删除");
        assertFalse(Files.exists(file), "真正到龄后文件必须删除");
        assertTrue(zsets.getOrDefault(RUN_LIST_PREFIX + runId, Map.of()).isEmpty(),
                "真正到龄后 run 索引项必须同删");
        assertTrue(hashes.getOrDefault(RUN_IDENTITY_PREFIX + runId, Map.of()).isEmpty(),
                "真正到龄后身份字段必须同删");
    }

    @Test
    void touchScriptStatusCodesShouldDriveJavaExceptions() throws Exception {
        // 第五轮 ⑤ 反测（状态码合同 0/1/2）：touch 脚本返回 0 = meta 已消失、1 = 成功、
        // 2 = 身份槽位被其他 artifactId 占用；Java 侧对 0/2 一律外抛、绝不吞异常报成功。
        // 同时钉住：非幂等制品 touch 绝不顺手创建身份项（field='' 整步跳过），幂等制品
        // 丢失的身份项由 touch 以 HSETNX 补建（严格赢家身份，绝不覆盖他人槽位）。
        String runId = "run-touch-status";
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        ObjectMapper mapper = new ObjectMapper();

        // —— 非幂等制品：touch 不得创建任何身份项（它们本就没有身份项，顺手创建会让
        //    后来同身份的幂等认领错误采纳它）
        PersistentArtifactRegistration explicit = registry.registerExplicit(
                runId, "user-1", "raw-ref", "plain", "1", "payload", 6);
        assertEquals("payload", registry.readContent(explicit.getArtifactId()));
        Map<String, String> identityAfterExplicit = hashes.get(identityKey);
        assertTrue(identityAfterExplicit == null || identityAfterExplicit.isEmpty(),
                "非幂等制品 touch 绝不创建身份项");

        // —— 幂等制品身份项丢失：touch 以 HSETNX 补建回本 artifactId
        PersistentArtifactRegistration idem = registry.registerIdempotent(
                runId, "user-1", "python_script", "repair-me", "脚本", "v", 6);
        String field = PersistentArtifactRegistry.identityField("python_script", "repair-me", null);
        hashes.remove(identityKey); // 模拟身份 hash 意外丢失
        assertEquals("v", registry.readContent(idem.getArtifactId()));
        assertEquals(idem.getArtifactId(), hashes.get(identityKey).get(field),
                "幂等制品丢失的身份项必须由 touch 以 HSETNX 补建");

        // —— 状态码 2：身份槽位被其他 artifactId 占用 → IllegalStateException，绝不覆盖。
        //    手工构造入侵者 meta B：与赢家 A 同身份 field、不同 artifactId。
        String intruderId = "python_script:intruder";
        String seqKey = RUN_SEQ_PREFIX + runId;
        PersistentArtifactMeta winnerMeta = registry.find(idem.getArtifactId()).orElseThrow();
        PersistentArtifactMeta intruder = registry.find(idem.getArtifactId()).orElseThrow();
        intruder.setArtifactId(intruderId);
        intruder.setIdempotent(Boolean.TRUE);
        values.put(META_PREFIX + intruderId, mapper.writeValueAsString(intruder));
        deadlines.put(META_PREFIX + intruderId, fakeNow + TimeUnit.HOURS.toMillis(6));
        // v6 第六轮 ①：失败读取必须"零副作用"——先把失败前的世界拍快照
        String intruderRawBefore = values.get(META_PREFIX + intruderId);
        Long intruderDeadlineBefore = deadlines.get(META_PREFIX + intruderId);
        Map<String, Double> listScoresBefore = new LinkedHashMap<>(zsets.get(listKey));
        String seqBefore = values.get(seqKey);
        IllegalStateException occupied = assertThrows(IllegalStateException.class,
                () -> registry.readContent(intruderId));
        assertTrue(occupied.getMessage().contains("identity slot occupied"), occupied.getMessage());
        assertEquals(idem.getArtifactId(), hashes.get(identityKey).get(field),
                "状态码 2 路径绝不覆盖他人已占用的身份槽位");
        assertFalse(zsets.get(listKey).containsKey(intruderId),
                "状态码 2 在成员同步之前返回，入侵者不得进入 run 列表");
        assertNotNull(winnerMeta);
        // v6 第六轮 ①：零副作用全量断言——冲突预检先于一切写入，失败前后世界逐字节不变。
        // 旧实现先 SET 新 meta 再查身份槽，返回 2 不回滚 → 失效制品可被失败读取反复续命。
        assertEquals(intruderRawBefore, values.get(META_PREFIX + intruderId),
                "状态码 2 零副作用：入侵者 meta 原文绝不被重写（lastAccess/expires 不得刷新）");
        assertEquals(intruderDeadlineBefore, deadlines.get(META_PREFIX + intruderId),
                "状态码 2 零副作用：入侵者 meta TTL 绝不被滑动（失效制品不得被失败读取续命）");
        assertEquals(listScoresBefore, zsets.get(listKey),
                "状态码 2 零副作用：run 列表所有成员 score 全量不变");
        assertEquals(seqBefore, values.get(seqKey),
                "状态码 2 零副作用：run-seq 值绝不前进（失败读取不得消耗序号）");

        // —— 状态码 0：meta 在 find 与 touch 之间消失 → IllegalArgumentException，绝不吞。
        //    （单线程 fake 无法在 find/touch 之间插入过期，直接对私有 touch 注入已消失的 meta）
        PersistentArtifactRegistration gone = registry.registerExplicit(
                runId, "user-1", "raw-ref", "gone", "2", "x", 6);
        PersistentArtifactMeta goneMeta = registry.find(gone.getArtifactId()).orElseThrow();
        values.remove(META_PREFIX + gone.getArtifactId());
        deadlines.remove(META_PREFIX + gone.getArtifactId());
        IllegalArgumentException notFound = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(registry, "touch", goneMeta));
        assertTrue(notFound.getMessage().contains("Artifact not found"), notFound.getMessage());
    }

    // ===== 第五轮 ②：序号键降级（丢失不丢数据、不报错） =====

    @Test
    void seqKeyLossShouldDegradeWithoutDataLossOrErrors() {
        // 第五轮 ② 反测（替代被废除的游标键）+ v6 第六轮 ②：run-seq 键若因 Redis
        // 重启/逐出丢失，绝不丢数据、绝不报错，硬预算与持续进展仍成立。第六轮起每次
        // 发号前先把 seq 原子抬到至少当前 ZSET 最大 score 再 INCRBY——排序语义与
        // 「幸存者严格移到未检查成员之后」的持续推进完全不降级。（旧游标键方案的对应
        // 故障是游标被短 TTL 候选覆盖，本方案没有游标键，这类漂移从构造上消失。）
        String runId = "run-seqloss";
        String seqKey = RUN_SEQ_PREFIX + runId;
        registry.registerExplicit(runId, "user-1", "raw-ref", "a", "1", "one", 6);
        registry.registerExplicit(runId, "user-1", "raw-ref", "b", "2", "two", 6);
        registry.registerExplicit(runId, "user-1", "raw-ref", "c", "3", "three", 6);
        assertNotNull(values.get(seqKey), "注册后 run-seq 键必须在场");
        double maxScoreBeforeLoss = zsets.get(RUN_LIST_PREFIX + runId).values().stream()
                .mapToDouble(Double::doubleValue).max().orElseThrow();

        // 模拟序号键丢失（重启/逐出）
        values.remove(seqKey);
        deadlines.remove(seqKey);

        // 下一次注册必须正常成功：轮转重打分前先把 seq 抬到当前最大 score 再继续发号
        PersistentArtifactRegistration after = registry.registerExplicit(
                runId, "user-1", "raw-ref", "d", "4", "four", 6);
        assertNotNull(after.getArtifactId(), "序号键丢失后注册不得失败");
        Map<String, Double> zset = zsets.get(RUN_LIST_PREFIX + runId);
        assertEquals(4, zset.size(), "四个成员一个都不能丢");
        assertEquals(4, registry.listByRunId(runId).size(), "列表必须完整可读");
        // 三次注册共发号 6（每轮轮转重打分也发号），丢 seq 后抬到 6 → 重打分 +3 → 9
        // → 新成员 +1 → 10：seq 严格接在旧最大 score 之后，绝不回到 0
        assertEquals("10", values.get(seqKey), "丢 seq 后发号必须先抬到当前最大 score 再继续");
        double minScore = zset.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertTrue(minScore > maxScoreBeforeLoss,
                "丢 seq 后重打分的所有成员必须严格大于丢失前的最大 score（持续推进不降级）");
    }

    // ===== 第六轮 ②：seq 丢失后持续推进不降级（N>128 + 高分 + 尾部 ghost） =====

    @Test
    void seqLossWithHighScoresAndGhostTailShouldStillAdvance() {
        // 第六轮 ② 反测（codex 1a74ca02）：成员数超过幽灵清理预算（128）时，窗口轮转
        // 只覆盖得分最低的 128 个成员，尾部高分成员留在未检查段。此时若 run-seq 键丢失，
        // 旧实现的轮转从 0 重新 INCRBY，幸存者会被重打分到未检查段之前，直接破坏
        // 「幸存者严格移到未检查成员之后」的前提（msg 11764111）与 c2f64dbe 承诺。v6
        // 每次发号前先把 seq 原子抬到至少当前 ZSET 最大 score（floorSeqToTopScore）。
        // 本测试构造 N=130 > 128 + 高 score + 窗口头 ghost + 尾部 ghost + 删 seq，断言：
        // 第一轮窗口头 ghost 被清、尾部 ghost（在窗口外）存活、所有幸存者 score 严格大于
        // 丢失前最大 score（未检查最大值）；第二轮尾部 ghost 也被清，固定集在声明轮数内
        // 清到零 ghost，且全程一个活成员都不丢。
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 1000);
        String runId = "run-seqloss-advance";
        String seqKey = RUN_SEQ_PREFIX + runId;
        String listKey = RUN_LIST_PREFIX + runId;
        int n = 130; // > GHOST_PURGE_BUDGET（128）：尾部成员必然落在轮转窗口之外
        for (int i = 0; i < n; i++) {
            registry.registerExplicit(runId, "user-1", "raw-ref", "adv-" + i,
                    "n" + i, "payload-" + i, 6);
        }
        Map<String, Double> zset = zsets.get(listKey);
        assertEquals(n, zset.size(), "130 个成员必须全部入列");
        long seqBeforeLoss = Long.parseLong(values.get(seqKey));
        double maxScoreBeforeLoss = zset.values().stream()
                .mapToDouble(Double::doubleValue).max().orElseThrow();
        assertEquals((double) seqBeforeLoss, maxScoreBeforeLoss,
                "floor 不变量：正常注册下 seq 恒等于当前最大 score");
        List<String> sorted = sortedMembers(zset);
        String windowGhost = sorted.get(0);     // 最低分：落在第一轮轮转窗口内
        String secondToLast = sorted.get(n - 2); // 第一轮仍在未检查段（窗口外高分成员）
        String tailGhost = sorted.get(n - 1);   // 最高分：第一轮窗口外的尾部 ghost
        values.remove(META_PREFIX + windowGhost);
        deadlines.remove(META_PREFIX + windowGhost);
        values.remove(META_PREFIX + tailGhost);
        deadlines.remove(META_PREFIX + tailGhost);
        values.remove(seqKey); // 模拟序号键单键丢失（重启/逐出）
        deadlines.remove(seqKey);

        // 第一轮：窗口头 ghost 当场清除；尾部 ghost 在窗口外，本轮存活；所有幸存者
        // 在 seq 抬高后重打分，严格大于丢失前最大 score（即未检查段最大值）
        PersistentArtifactRegistration round1 = registry.registerExplicit(
                runId, "user-1", "raw-ref", "adv-new1", "n1", "new-1", 6);
        assertFalse(zset.containsKey(windowGhost), "窗口头 ghost 必须在第一轮被清除");
        assertTrue(zset.containsKey(tailGhost), "尾部 ghost 在窗口外，第一轮必须存活");
        for (Map.Entry<String, Double> entry : zset.entrySet()) {
            String member = entry.getKey();
            if (member.equals(tailGhost) || member.equals(secondToLast)
                    || member.equals(round1.getArtifactId())) {
                continue; // 未检查段两个成员与新成员分别断言
            }
            assertTrue(entry.getValue() > maxScoreBeforeLoss,
                    "幸存者 " + member + " 必须严格移到未检查段之后：score=" + entry.getValue()
                            + "，未检查最大值=" + maxScoreBeforeLoss);
        }
        assertTrue(zset.get(round1.getArtifactId()) > maxScoreBeforeLoss,
                "新成员也必须严格大于丢失前最大 score");

        // 第二轮：尾部 ghost 转入窗口被清除；固定集在声明轮数内清到零 ghost，
        // 且一个活成员都不丢
        registry.registerExplicit(runId, "user-1", "raw-ref", "adv-new2", "n2", "new-2", 6);
        assertFalse(zset.containsKey(tailGhost), "尾部 ghost 必须在第二轮被清除");
        assertEquals(n, zset.size(), "128 个活成员 + 2 个新成员，一个成员都不丢");
        for (String member : zset.keySet()) {
            assertTrue(values.containsKey(META_PREFIX + member),
                    "幽灵清完：列表成员 " + member + " 必须有 meta 键");
        }
        assertEquals(n, registry.listByRunId(runId).size(), "列表必须完整可读");
    }

    @Test
    void touchAfterSeqLossShouldMoveMemberToTrueTail() {
        // 第六轮 ② 反测（codex 1a74ca02）：读取 touch 同样是发号点。run-seq 键丢失后，
        // touch 必须先把 seq 抬到当前 ZSET 最大 score 再发号 +1，把被读取成员移到真正
        // 队尾——绝不从 0 发号把成员塞回其他成员之前（那会让"最近读取"排在"最久未查"
        // 之前，破坏清理窗口从最旧查起的语义）。
        String runId = "run-seqloss-touch";
        String seqKey = RUN_SEQ_PREFIX + runId;
        PersistentArtifactRegistration a = registry.registerExplicit(
                runId, "user-1", "raw-ref", "t-a", "1", "pa", 6);
        PersistentArtifactRegistration b = registry.registerExplicit(
                runId, "user-1", "raw-ref", "t-b", "2", "pb", 6);
        PersistentArtifactRegistration c = registry.registerExplicit(
                runId, "user-1", "raw-ref", "t-c", "3", "pc", 6);
        values.remove(seqKey); // 模拟序号键单键丢失（重启/逐出）
        deadlines.remove(seqKey);

        assertEquals("pa", registry.readContent(a.getArtifactId()));
        Map<String, Double> zset = zsets.get(RUN_LIST_PREFIX + runId);
        assertTrue(zset.get(a.getArtifactId()) > zset.get(b.getArtifactId()),
                "seq 丢失后被读取成员必须移到真正队尾（严格大于其他成员）");
        assertTrue(zset.get(a.getArtifactId()) > zset.get(c.getArtifactId()),
                "seq 丢失后被读取成员必须移到真正队尾（严格大于其他成员）");
        assertEquals((long) Math.rint(zset.get(a.getArtifactId())), Long.parseLong(values.get(seqKey)),
                "seq 必须等于队尾 score：先抬到旧最大值再发号 +1");
    }

    // ===== 第六轮 ③：永久 TTL(-1) 两向（既有保持永久 + 本次新建获有限 TTL） =====

    @Test
    void extendOnlyShouldKeepPreexistingPersistentKeysAndGiveNewKeysTtl() {
        // 第六轮 ③ 两向反测（codex 1a74ca02）：旧 extendOnly 的 `t == -1 or t < ttl →
        // EXPIRE` 会把永久键（TTL = -1）转成有限 TTL，与已冻结的 c2f64dbe 承诺
        // 「-1 视为无限、永不缩短」直接相反。v6 区分「本次调用新建的键」（必须获得有限
        // TTL，防止永久键泄漏）与「既有键」（只延长：有限键只延长不缩短，永久键保持
        // 永久）。方向一：既有永久索引键经读取 touch 后仍永久，meta 照常满额滑动；
        // 方向二：三类索引键整体消失后 touch 重建，重建键全部获得有限 TTL。
        String runId = "run-persist-ttl";
        long t0 = fakeNow;
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "python_script", "pt", "脚本", "v", 6);
        String metaKey = META_PREFIX + registration.getArtifactId();
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        String seqKey = RUN_SEQ_PREFIX + runId;
        // 注册后四类键 deadline 对齐（脚本内同款 TTL 写入）——钉住初始状态
        assertEquals(t0 + TimeUnit.HOURS.toMillis(6), deadlines.get(metaKey));
        assertEquals(deadlines.get(metaKey), deadlines.get(listKey));
        assertEquals(deadlines.get(metaKey), deadlines.get(identityKey));
        assertEquals(deadlines.get(metaKey), deadlines.get(seqKey));

        // 方向一：既有永久键（无 deadline ≙ 真实 Redis TTL = -1）保持永久，绝不缩短
        deadlines.remove(listKey);
        deadlines.remove(identityKey);
        deadlines.remove(seqKey);
        advanceClock(TimeUnit.HOURS.toMillis(1));
        assertEquals("v", registry.readContent(registration.getArtifactId()));
        assertNull(deadlines.get(listKey), "既有永久列表键不得被缩短为有限 TTL");
        assertNull(deadlines.get(identityKey), "既有永久身份键不得被缩短为有限 TTL");
        assertNull(deadlines.get(seqKey), "既有永久序号键不得被缩短为有限 TTL");
        assertEquals(t0 + TimeUnit.HOURS.toMillis(7), deadlines.get(metaKey),
                "meta 自身按读取语义照旧满额滑动，不受索引键永久化影响");

        // 方向二：本次调用新建的键必须获得有限 TTL（防止永久键泄漏）
        zsets.remove(listKey);
        values.remove(seqKey);
        hashes.remove(identityKey);
        deadlines.remove(listKey);
        deadlines.remove(identityKey);
        deadlines.remove(seqKey);
        assertEquals("v", registry.readContent(registration.getArtifactId()));
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "本次新建的列表键必须获得有限 TTL");
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(identityKey),
                "本次新建的身份键必须获得有限 TTL");
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(seqKey),
                "本次新建的序号键必须获得有限 TTL");
        assertTrue(zsets.get(listKey).containsKey(registration.getArtifactId()),
                "重建的列表必须把成员补回");
    }

    // ===== v7 ③：空 ZSET 自动删 key + 脚本内重建（重建键必须获有限 TTL） =====

    @Test
    void addScriptShouldGiveFiniteTtlToListRebuiltAfterPurgingLastGhost() {
        // v7 反测（codex 77a272a7，方向一·加入脚本）：真实 Redis 中 ZSET 最后一个成员
        // 被 ZREM 时 key 当场自动删除。RUN_LIST_ADD_SCRIPT 若在幽灵清理后沿用入口
        // listExisted 快照，重建的列表键会被 extendOnly 误判为既有键（TTL=-1 落在
        // createdHere=0 分支既不返回也不 EXPIRE）→ 永久键泄漏。v7 在清理后即时重判：
        // 重建键按本次新建处理，必须获得与滑动过期协议对齐的有限 TTL。
        String runId = "run-rebuild-add";
        long t0 = fakeNow;
        String listKey = RUN_LIST_PREFIX + runId;
        String seqKey = RUN_SEQ_PREFIX + runId;
        PersistentArtifactRegistration ghost = registry.registerExplicit(
                runId, "user-1", "raw-ref", "g1", "1", "ghost-payload", 6);
        assertEquals(t0 + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "初始状态：列表键带有限 TTL（与 meta 同协议对齐）");
        // 构造单幽灵：meta 消失、成员仍挂在列表里；列表键自身仍有有限 TTL
        values.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(META_PREFIX + ghost.getArtifactId());
        advanceClock(TimeUnit.HOURS.toMillis(1));

        // 非幂等新注册 → 清理删掉最后一个 ghost 成员 → key 自动删除 → ZADD 重建
        PersistentArtifactRegistration fresh = registry.registerExplicit(
                runId, "user-1", "raw-ref", "m1", "2", "fresh-payload", 6);
        assertNotNull(fresh.getArtifactId(), "重建场景下注册不得失败");
        Map<String, Double> zset = zsets.get(listKey);
        assertNotNull(zset, "重建后的列表键必须在场");
        assertEquals(1, zset.size(), "ghost 被清掉，只剩新成员");
        assertTrue(zset.containsKey(fresh.getArtifactId()));
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "重建的列表键属本次新建：必须获得有限 TTL，绝不允留永久键");
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(seqKey),
                "seq 键调用中未被删除，属既有键：只延长到新满额");
        assertEquals(1, registry.listByRunId(runId).size(), "列表必须完整可读");
    }

    @Test
    void addScriptShouldNotInheritPermanenceForListRebuiltAfterPurgingLastGhost() {
        // v7 反测（codex 77a272a7，方向二·加入脚本）：列表键起点是永久（无 deadline ≙
        // 真实 Redis TTL = -1）时，「既有永久保持永久」只适用于调用全程存在的键。
        // 旧键在调用中途被清空删除后，它的永久属性已随键消失；重建键是本次新建，
        // 必须获得有限 TTL，绝不继承已消失旧键的永久属性。
        String runId = "run-rebuild-add-persistent";
        String listKey = RUN_LIST_PREFIX + runId;
        PersistentArtifactRegistration ghost = registry.registerExplicit(
                runId, "user-1", "raw-ref", "g1", "1", "ghost-payload", 6);
        values.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(listKey); // 构造永久起点
        advanceClock(TimeUnit.HOURS.toMillis(1));

        PersistentArtifactRegistration fresh = registry.registerExplicit(
                runId, "user-1", "raw-ref", "m1", "2", "fresh-payload", 6);
        assertNotNull(fresh.getArtifactId());
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "重建键不得继承已消失旧键的永久属性：必须按本次新建获得有限 TTL");
        assertEquals(1, zsets.get(listKey).size(), "ghost 被清掉，只剩新成员");
    }

    @Test
    void claimScriptShouldGiveFiniteTtlToListRebuiltAfterPurgingLastGhost() {
        // v7 反测（codex 77a272a7，方向一·认领脚本）：ATOMIC_CLAIM_SCRIPT 的 CLAIMED
        // 路径同款边界——清理删空列表后重建的键必须按本次新建获有限 TTL。构造：首个
        // 幂等身份注册后 meta 变 ghost（身份字段仍悬挂旧 ID），第二个不同身份走
        // CLAIMED 路径（EXISTS 分支无删除操作、入口快照精确，不受本修正影响）。
        String runId = "run-rebuild-claim";
        long t0 = fakeNow;
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        PersistentArtifactRegistration ghost = registry.registerIdempotent(
                runId, "user-1", "python_script", "pt", "脚本", "v", 6);
        assertEquals(t0 + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "初始状态：列表键带有限 TTL（与 meta 同协议对齐）");
        values.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(META_PREFIX + ghost.getArtifactId());
        advanceClock(TimeUnit.HOURS.toMillis(1));

        PersistentArtifactRegistration fresh = registry.registerIdempotent(
                runId, "user-1", "python_script", "pt2", "脚本2", "v", 6);
        assertNotEquals(ghost.getArtifactId(), fresh.getArtifactId(),
                "第二个身份必须走 CLAIMED 认领新 ID，不得采纳 ghost");
        Map<String, Double> zset = zsets.get(listKey);
        assertNotNull(zset, "重建后的列表键必须在场");
        assertEquals(1, zset.size(), "ghost 被清掉，只剩新认领成员");
        assertTrue(zset.containsKey(fresh.getArtifactId()));
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "重建的列表键属本次新建：必须获得有限 TTL，绝不允留永久键");
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(identityKey),
                "身份 hash 调用中未被删除（HSET 只增字段）：既有键延长到新满额");
        assertEquals(1, registry.listByRunId(runId).size(), "列表必须完整可读");
    }

    @Test
    void claimScriptShouldNotInheritPermanenceForListRebuiltAfterPurgingLastGhost() {
        // v7 反测（codex 77a272a7，方向二·认领脚本）：永久起点的列表键在调用中途被
        // 清空删除后，重建键必须按本次新建获有限 TTL，不继承已消失旧键的永久属性。
        String runId = "run-rebuild-claim-persistent";
        String listKey = RUN_LIST_PREFIX + runId;
        PersistentArtifactRegistration ghost = registry.registerIdempotent(
                runId, "user-1", "python_script", "pt", "脚本", "v", 6);
        values.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(META_PREFIX + ghost.getArtifactId());
        deadlines.remove(listKey); // 构造永久起点
        advanceClock(TimeUnit.HOURS.toMillis(1));

        PersistentArtifactRegistration fresh = registry.registerIdempotent(
                runId, "user-1", "python_script", "pt2", "脚本2", "v", 6);
        assertNotEquals(ghost.getArtifactId(), fresh.getArtifactId());
        assertEquals(fakeNow + TimeUnit.HOURS.toMillis(6), deadlines.get(listKey),
                "重建键不得继承已消失旧键的永久属性：必须按本次新建获得有限 TTL");
        assertEquals(1, zsets.get(listKey).size(), "ghost 被清掉，只剩新认领成员");
    }

    // ===== 边界约束 2：score 是单调序号不是毫秒时间 =====

    @Test
    void scoresShouldBeStrictlyMonotonicEvenForSameMillisecondRegistrations() {
        // 边界约束 2 反测：score 绝不取毫秒时间戳——同一毫秒内的多次注册也必须拿到
        // 严格互不相同的序号（INCRBY 单调发号），且按注册顺序严格递增。时间戳方案在
        // 快循环里必然撞同毫秒同分，破坏"已检查成员严格在未检查成员之后"的排序前提。
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 100);
        String runId = "run-monotonic";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PersistentArtifactRegistration r = registry.registerExplicit(
                    runId, "user-1", "raw-ref", "m-" + i, "n" + i, "payload-" + i, 6);
            ids.add(r.getArtifactId());
        }
        Map<String, Double> zset = zsets.get(RUN_LIST_PREFIX + runId);
        assertEquals(10, zset.size());
        // 全部 score 互不相同（撞分 = 时间戳语义的必然故障，序号语义下不可能）
        assertEquals(10, new HashSet<>(zset.values()).size(), "同毫秒注册的 score 必须严格互异");
        // 按注册顺序严格递增（窗口轮转保持相对顺序，每次重打分都发更大的号）
        double previous = Double.NEGATIVE_INFINITY;
        for (String id : ids) {
            double score = zset.get(id);
            assertTrue(score > previous,
                    "注册顺序必须与 score 顺序一致: id=" + id + " score=" + score + " prev=" + previous);
            previous = score;
        }
    }

    // ===== 第五轮 ⑤：cleanup Lua 原子判定（损坏/无日期 meta 绝不盲删） =====

    @Test
    void cleanupShouldLeaveMalformedOrUndatedMetaUntouched() throws Exception {
        // 第五轮 ⑤ 反测（cleanup 判定侧）：cleanup 不再用 Java 预读的 expiresAtMillis
        // 直接删，而是每条 meta 走 Lua 原子判定——键缺失/无 expiresAtMillis/非数字一律
        // 保留，JSON 损坏绝不盲删（Java 预解析失败同样跳过）。判定与 DEL 同脚本原子，
        // touch-then-cleanup 无 TOCTOU 窗口。
        String runId = "run-malformed";
        ObjectMapper mapper = new ObjectMapper();

        // 健康对照品：不过期，必须活过 cleanup
        PersistentArtifactRegistration healthy = registry.registerExplicit(
                runId, "user-1", "raw-ref", "healthy", "1", "healthy", 6);
        // 损坏 JSON：既不是合法 JSON 也无法解析成 meta——绝不盲删
        PersistentArtifactRegistration corrupt = registry.registerExplicit(
                runId, "user-1", "raw-ref", "corrupt", "2", "corrupt", 6);
        values.put(META_PREFIX + corrupt.getArtifactId(), "{oops not json");
        // 可解析但无 expiresAtMillis（永不过期语义，与历史判空逻辑一致）——保留
        PersistentArtifactRegistration undated = registry.registerExplicit(
                runId, "user-1", "raw-ref", "undated", "3", "undated", 6);
        values.put(META_PREFIX + undated.getArtifactId(), "{}");
        // expiresAtMillis 是字符串不是数字——Java 预解析即失败，同样保留
        PersistentArtifactRegistration stringDated = registry.registerExplicit(
                runId, "user-1", "raw-ref", "string-dated", "4", "string-dated", 6);
        values.put(META_PREFIX + stringDated.getArtifactId(), "{\"expiresAtMillis\":\"123\"}");
        // 真正到龄的对照品：必须被删（证明 cleanup 确实在跑）
        PersistentArtifactRegistration expired = registry.registerExplicit(
                runId, "user-1", "raw-ref", "expired", "5", "expired", 6);
        PersistentArtifactMeta expiredMeta = registry.find(expired.getArtifactId()).orElseThrow();
        expiredMeta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        values.put(META_PREFIX + expired.getArtifactId(), mapper.writeValueAsString(expiredMeta));

        registry.cleanupExpiredArtifacts();

        assertTrue(values.containsKey(META_PREFIX + healthy.getArtifactId()), "未到期制品必须保留");
        assertTrue(values.containsKey(META_PREFIX + corrupt.getArtifactId()),
                "损坏 JSON 绝不盲删（Java 预解析失败即跳过）");
        assertTrue(values.containsKey(META_PREFIX + undated.getArtifactId()),
                "无 expiresAtMillis = 永不过期语义，必须保留");
        assertTrue(values.containsKey(META_PREFIX + stringDated.getArtifactId()),
                "expiresAtMillis 非数字必须保留");
        assertFalse(values.containsKey(META_PREFIX + expired.getArtifactId()),
                "真正到龄的制品必须被原子判定删除");
    }

    // ===== fake redis（线程安全；支持认领/加入/touch/清理判定/值条件 HDEL 五种 Lua 脚本） =====

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
            synchronized (redisLock) {
                sweepExpired();
                values.put(key, invocation.getArgument(1));
                deadlines.put(key, fakeNow + unit.toMillis(ttl));
            }
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
            synchronized (redisLock) {
                deadlines.remove(invocation.getArgument(0));
                return values.remove(invocation.getArgument(0)) != null;
            }
        }).when(template).delete(org.mockito.ArgumentMatchers.anyString());
        // SCAN 返回三张表全部键（模拟真实 Redis 中索引键也会被 META_PREFIX* 命中）
        when(template.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class)))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Set<String> all = new HashSet<>(values.keySet());
                        all.addAll(hashes.keySet());
                        all.addAll(zsets.keySet());
                        return new SetCursor(all.iterator());
                    }
                });

        ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
        when(template.opsForZSet()).thenReturn(zsetOps);
        // ZRANGE 语义：按 (score 升序, 成员字典序) 返回 [start, end] 区间（负索引从末尾数）
        when(zsetOps.range(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        return zrange(invocation.getArgument(0),
                                invocation.getArgument(1), invocation.getArgument(2));
                    }
                });
        when(zsetOps.remove(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Map<String, Double> zset = zsets.get(invocation.getArgument(0));
                        if (zset == null || zset.remove(invocation.getArgument(1).toString()) == null) {
                            return 0L;
                        }
                        if (zset.isEmpty()) {
                            // v7：真实 Redis 语义——ZSET 最后一个成员被 ZREM 时 key 当场
                            // 自动删除（连同 TTL）
                            zsets.remove(invocation.getArgument(0));
                            deadlines.remove(invocation.getArgument(0));
                        }
                        return 1L;
                    }
                });

        // ===== Lua execute() fake =====
        // Mockito 5 对 varargs 按"每个匹配器对一个可变参数"匹配，五种脚本 ARGV 个数不同
        // （清理判定 1 / 值条件 HDEL 2 / touch 4 / 列表加入 5 / 幂等认领 6），各需独立 stub。
        // 五个 stub 共用 redisLock，模拟 Redis 单线程：任一脚本执行期间其他脚本不得插入。

        // 过期清理判定脚本（1 个 ARGV：now 毫秒）：读回当前 JSON，expiresAtMillis 是数字
        // 且 <= now 才 DEL 返回 1；键缺失返回 0；JSON 损坏/非对象返回 -1；无日期返回 0
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            long now = Long.parseLong(String.valueOf(args[2]));
            synchronized (redisLock) {
                sweepExpired();
                return fakeCleanupVerdict(keys.get(0), now);
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any());

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

        // 读取 touch 脚本（KEYS=[meta, 列表 ZSET, 身份 hash, run-seq]；4 个 ARGV：
        // 新 meta JSON、TTL 秒数、身份 field（非幂等传空串）、artifactId）：
        // meta 缺失 → 0；身份冲突只读预检（先于一切写入，他人占用 → 2 且零副作用）；
        // SET 新 meta + 满额 EXPIRE；身份步（空槽 HSETNX 补建、field='' 整步跳过）；
        // 抬 seq 至当前最大 score 后成员 score 以新序号同步（缺失 ZADD NX 补回）；
        // 三类索引键 TTL 刷新（新建获 TTL、既有只延长、永久保持）；成功 → 1
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            String metaJson = String.valueOf(args[2]);
            long ttlSeconds = Long.parseLong(String.valueOf(args[3]));
            String field = String.valueOf(args[4]);
            String artifactId = String.valueOf(args[5]);
            synchronized (redisLock) {
                sweepExpired();
                return fakeTouch(keys.get(0), keys.get(1), keys.get(2), keys.get(3),
                        metaJson, ttlSeconds, field, artifactId);
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        // run 列表加入脚本（KEYS=[列表 ZSET, run-seq]；5 个 ARGV：cap、幽灵预算、
        // meta 前缀、artifactId、TTL 秒数）：窗口轮转幽灵清理 → ZCARD 容量检查 →
        // 抬 seq 至当前最大 score → INCRBY 发号 + ZADD → 列表键与序号键 TTL 刷新
        // （本次新建获 TTL、既有只延长不缩短、既有永久保持永久），原子；满则 FULL 不写
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
                purgeWindow(keys.get(0), keys.get(1), metaPrefix, budget);
                // v7（镜像生产）：清理完成后即时判定存在性，不取入口快照——清理刚删空
                // ZSET 时 key 已被真实 Redis 语义自动删除，随后 ZADD 属重建（本次新建）
                boolean listExisted = keyExistsInFake(keys.get(0));
                boolean seqExisted = keyExistsInFake(keys.get(1));
                Map<String, Double> zset = zsets.get(keys.get(0));
                int size = zset == null ? 0 : zset.size();
                if (size >= cap) {
                    return "FULL";
                }
                floorSeqToTopScore(keys.get(0), keys.get(1));
                long seq = incrBy(keys.get(1), 1);
                zsets.computeIfAbsent(keys.get(0), k -> new ConcurrentHashMap<>())
                        .put(artifactId, (double) seq);
                if (ttlSeconds > 0) {
                    extendOnlyTtl(keys.get(0), ttlSeconds, !listExisted);
                    extendOnlyTtl(keys.get(1), ttlSeconds, !seqExisted);
                }
                return "ADDED";
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        // 幂等认领脚本（KEYS=[身份 hash, 列表 ZSET, run-seq]；6 个 ARGV：field、候选 ID、
        // cap、幽灵预算、meta 前缀、TTL 秒数）：已有赢家 → meta 仍在则修复列表成员资格
        // （ZSCORE 缺失即抬 seq 后以新序号 ZADD 补回）+ 按赢家 meta 键自身剩余 TTL 做
        // 三类索引键 TTL 刷新（绝不取输家 ARGV）→ EXISTS:赢家ID；否则窗口轮转幽灵清理
        // → ZCARD 容量检查 → HSET 身份 + 抬 seq 发号 + ZADD + TTL 刷新（新建获 TTL、
        // 既有只延长、永久保持）→ CLAIMED。EXISTS 与写入互斥且整段原子——输家拿到
        // EXISTS 时赢家身份+列表必然已落盘且可见
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
                return fakeClaim(keys.get(0), keys.get(1), keys.get(2),
                        field, artifactId, cap, budget, metaPrefix, ttlSeconds);
            }
        }).when(template).execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        return template;
    }

    /**
     * fake 侧幂等认领脚本（与生产 ATOMIC_CLAIM_SCRIPT 逐步同语义）。v6 第六轮 ②：
     * 三处发号（EXISTS 修复 / 轮转重打分 / 认领入列）前都先抬 seq 至当前 ZSET 最大
     * score；③：TTL 刷新区分本次新建与既有键（既有永久保持永久）。v7：CLAIMED 路径
     * 的「本次新建」判定在幽灵清理完成后即时重判（清理可能删空 ZSET 使 key 自动删除，
     * 随后 ZADD 属重建）；EXISTS 分支无删除操作，入口快照仍精确。
     * 调用方必须已持有 redisLock。
     */
    private String fakeClaim(String identityKey, String listKey, String seqKey,
                             String field, String artifactId, int cap, int budget,
                             String metaPrefix, long ttlSeconds) {
        boolean identityExisted = keyExistsInFake(identityKey);
        boolean listExisted = keyExistsInFake(listKey);
        boolean seqExisted = keyExistsInFake(seqKey);
        Map<String, String> identity = hashes.get(identityKey);
        String existing = identity == null ? null : identity.get(field);
        if (existing != null) {
            if (values.containsKey(metaPrefix + existing)) {
                // 赢家 meta 仍活：修复列表成员资格（ZSCORE 缺失即抬 seq 后以新序号 ZADD 补回）
                Map<String, Double> zset = zsets.get(listKey);
                Double score = zset == null ? null : zset.get(existing);
                if (score == null) {
                    floorSeqToTopScore(listKey, seqKey);
                    long repairSeq = incrBy(seqKey, 1);
                    zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>())
                            .put(existing, (double) repairSeq);
                }
                // 索引 TTL 刷新只取赢家 meta 键自身剩余 TTL（绝不取输家传入的 ttlSeconds）
                long winnerTtl = ttlOfSeconds(metaPrefix + existing);
                if (winnerTtl > 0) {
                    extendOnlyTtl(identityKey, winnerTtl, !identityExisted);
                    extendOnlyTtl(listKey, winnerTtl, !listExisted);
                    extendOnlyTtl(seqKey, winnerTtl, !seqExisted);
                }
            }
            return "EXISTS:" + existing;
        }
        purgeWindow(listKey, seqKey, metaPrefix, budget);
        // v7（镜像生产）：CLAIMED 路径在清理完成后即时重判 list/seq 存在性，不取入口
        // 快照——清理刚删空 ZSET 时 key 已自动删除，随后 ZADD 属重建（本次新建）。
        // EXISTS 分支没有任何成员删除，入口快照在那里仍然精确，不受本重判影响。
        listExisted = keyExistsInFake(listKey);
        seqExisted = keyExistsInFake(seqKey);
        Map<String, Double> zset = zsets.get(listKey);
        int size = zset == null ? 0 : zset.size();
        if (size >= cap) {
            return "FULL";
        }
        hashes.computeIfAbsent(identityKey, k -> new ConcurrentHashMap<>()).put(field, artifactId);
        floorSeqToTopScore(listKey, seqKey);
        long claimSeq = incrBy(seqKey, 1);
        zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>()).put(artifactId, (double) claimSeq);
        if (ttlSeconds > 0) {
            extendOnlyTtl(identityKey, ttlSeconds, !identityExisted);
            extendOnlyTtl(listKey, ttlSeconds, !listExisted);
            extendOnlyTtl(seqKey, ttlSeconds, !seqExisted);
        }
        return "CLAIMED";
    }

    /**
     * fake 侧读取 touch 脚本（与生产 TOUCH_SCRIPT 逐步同语义，状态码合同 0/1/2）。
     * v6 第六轮 ①：身份冲突判断（只读）先于一切可见写入——返回 2 路径零副作用；
     * ②：发号前抬 seq 至当前 ZSET 最大 score；③：TTL 刷新区分本次新建与既有键。
     * 调用方必须已持有 redisLock。
     */
    private long fakeTouch(String metaKey, String listKey, String identityKey, String seqKey,
                           String metaJson, long ttlSeconds, String field, String artifactId) {
        if (!values.containsKey(metaKey)) {
            return 0L; // meta 已在 find 与 touch 之间消失：读取必须失败，绝不复活
        }
        if (!field.isEmpty()) {
            // 身份冲突预检（只读，先于一切写入）：他人占用 → 2，零副作用
            Map<String, String> identity = hashes.get(identityKey);
            String holder = identity == null ? null : identity.get(field);
            if (holder != null && !holder.equals(artifactId)) {
                return 2L; // meta 原文/TTL、list score、seq 值全部原样，失败读取绝不续命
            }
        }
        boolean listExisted = keyExistsInFake(listKey);
        boolean identityExisted = keyExistsInFake(identityKey);
        boolean seqExisted = keyExistsInFake(seqKey);
        values.put(metaKey, metaJson);
        if (ttlSeconds > 0) {
            deadlines.put(metaKey, fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds)); // 满额滑动
        }
        if (!field.isEmpty()) {
            // 身份步（仅幂等制品）：预检通过后单线程内槽位不可能被抢走，HSETNX 必然成功
            hashes.computeIfAbsent(identityKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent(field, artifactId);
        }
        floorSeqToTopScore(listKey, seqKey); // seq 丢失也严格移到真正队尾
        long seq = incrBy(seqKey, 1);
        Map<String, Double> zset = zsets.get(listKey);
        Double currentScore = zset == null ? null : zset.get(artifactId);
        if (currentScore == null) {
            zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent(artifactId, (double) seq); // ZADD NX：绝不覆盖并发写入
        } else {
            zset.put(artifactId, (double) seq); // 成员 score 同步到新序号（真正队尾）
        }
        if (ttlSeconds > 0) {
            extendOnlyTtl(listKey, ttlSeconds, !listExisted);
            extendOnlyTtl(identityKey, ttlSeconds, !identityExisted);
            extendOnlyTtl(seqKey, ttlSeconds, !seqExisted);
        }
        return 1L;
    }

    /**
     * fake 侧过期清理判定（与生产 CLEANUP_META_SCRIPT 同语义：判定与 DEL 原子）。
     * 返回 1 = 已删，0 = 保留（键缺失/无日期），-1 = 损坏（绝不盲删）。
     * 调用方必须已持有 redisLock。
     */
    private long fakeCleanupVerdict(String metaKey, long now) {
        String raw = values.get(metaKey);
        if (raw == null) {
            return 0L;
        }
        Object parsed;
        try {
            parsed = new ObjectMapper().readValue(raw, Object.class);
        } catch (Exception e) {
            return -1L; // cjson.decode 失败 → 损坏
        }
        if (!(parsed instanceof Map)) {
            return -1L; // 非对象（数字/字符串等）→ 损坏
        }
        Object expiresAt = ((Map<?, ?>) parsed).get("expiresAtMillis");
        if (!(expiresAt instanceof Number)) {
            return 0L; // 缺失/null/非数字 → 永不过期语义，保守保留
        }
        if (((Number) expiresAt).longValue() <= now) {
            deadlines.remove(metaKey);
            values.remove(metaKey);
            return 1L;
        }
        return 0L;
    }

    /**
     * fake 侧窗口轮转幽灵清理（与生产认领/加入脚本内的清理段同语义）：取当前 score
     * 最低的至多 budget 个成员（ZRANGE LIMIT 构造性硬上限），meta 键（values 表）不存在
     * 者当场 ZREM；重打分发号前先把 seq 原子抬到至少当前 ZSET 最大 score（v6 第六轮
     * ②，seq 键单键丢失也不降级），窗口内活成员再用 INCRBY 新发的连续序号重新打分、
     * 整体移到所有未检查成员之后（严格大于任何未检查成员的得分）。轮转状态编码在
     * score 排序本身，不存在独立游标键。v7：清理后 ZSET 变空时按真实 Redis「空 ZSET
     * 自动删 key」语义当场删除键与 deadline（随后 ZADD 属重建，按本次新建获有限 TTL）。
     * 调用方必须已持有 redisLock。
     */
    private void purgeWindow(String listKey, String seqKey, String metaPrefix, int budget) {
        if (budget <= 0) {
            return;
        }
        Map<String, Double> zset = zsets.get(listKey);
        if (zset == null || zset.isEmpty()) {
            return;
        }
        List<String> sorted = sortedMembers(zset);
        List<String> window = sorted.subList(0, Math.min(budget, sorted.size()));
        List<String> live = new ArrayList<>();
        for (String member : window) {
            if (values.containsKey(metaPrefix + member)) {
                live.add(member);
            } else {
                zset.remove(member); // 幽灵当场清除
            }
        }
        if (zset.isEmpty()) {
            // v7：真实 Redis 语义——ZSET 最后一个成员被 ZREM 时 key 当场自动删除（连同
            // TTL）。严格模拟：条目与 deadline 一并删除；随后任何 ZADD 都属重建（本次
            // 新建），必须获得有限 TTL。zset 空时 live 必空，无重打分可做。
            zsets.remove(listKey);
            deadlines.remove(listKey);
            return;
        }
        if (!live.isEmpty()) {
            floorSeqToTopScore(listKey, seqKey); // 发号前抬 seq：幸存者严格大于未检查最大值
            long base = incrBy(seqKey, live.size());
            for (int i = 0; i < live.size(); i++) {
                zset.put(live.get(i), (double) (base - live.size() + i + 1));
            }
        }
    }

    /**
     * fake 侧 INCRBY（run-seq 发号）：键缺失从 0 起算。真实 Redis INCRBY 保留键自身
     * TTL——fake 同样不触碰 deadlines（序号键的过期只由脚本内 extendOnly 管理）。
     * 调用方必须已持有 redisLock。
     */
    private long incrBy(String seqKey, long delta) {
        long current = 0L;
        String raw = values.get(seqKey);
        if (raw != null) {
            try {
                current = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                current = 0L;
            }
        }
        long next = current + delta;
        values.put(seqKey, String.valueOf(next));
        return next;
    }

    /**
     * fake 侧 ZRANGE：按 (score 升序, 成员字典序) 返回 [start, end] 闭区间，
     * 负索引从末尾数（与真实 Redis ZRANGE 一致）。调用方必须已持有 redisLock。
     */
    private Set<String> zrange(String key, long start, long end) {
        Map<String, Double> zset = zsets.get(key);
        if (zset == null || zset.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<String> sorted = sortedMembers(zset);
        int size = sorted.size();
        int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
        int to = (int) (end < 0 ? Math.max(from, size + end + 1) : Math.min(end + 1, size));
        return new LinkedHashSet<>(sorted.subList(from, to));
    }

    /** ZSET 成员按 (score 升序, 成员字典序) 排序——与真实 Redis ZSET 排序一致。 */
    private static List<String> sortedMembers(Map<String, Double> zset) {
        return zset.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * fake 侧 TTL 刷新（对应生产脚本内 extendOnly，v6 第六轮 ③：区分「本次调用新建
     * 的键」与「既有键」）。createdHere = 本次脚本调用新建了该键 → 必须获得有限 TTL
     * （防止永久键泄漏）；既有键只延长不缩短；既有永久键（无 deadline，对应 TTL = -1）
     * 保持永久，绝不缩短为有限值；键不存在（-2）时 EXPIRE 是 no-op——绝不给已消失的
     * 键凭空造 deadline。调用方必须已持有 redisLock。
     */
    private void extendOnlyTtl(String key, long ttlSeconds, boolean createdHere) {
        if (!keyExistsInFake(key)) {
            return; // ttl == -2
        }
        long desired = fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds);
        Long current = deadlines.get(key);
        if (createdHere) { // 本次新建的键：获得有限 TTL（与索引键同滑动过期协议对齐）
            deadlines.put(key, desired);
            return;
        }
        if (current != null && current < desired) { // 既有带 TTL 键：只延长不缩短
            deadlines.put(key, desired);
        }
        // current == null：既有永久键（-1）保持永久，绝不缩短
    }

    /**
     * fake 侧发号前抬 seq（对应生产脚本内 floorSeqToTopScore，v6 第六轮 ②）：把 seq
     * 原子抬到至少当前 ZSET 最大 score（ZREVRANGE 0 0 WITHSCORES 的等价物——有界读
     * 末尾 1 项，不全量遍历生产语义；fake 在内存表上取最大值），再交给调用方 INCRBY。
     * seq 键单键丢失（重启/逐出）时从当前最大 score 继续发号，绝不回到 0——幸存者与
     * 新成员严格落在所有未检查成员之后，持续推进不降级。INCRBY 保留键自身 TTL，fake
     * 同样不触碰 deadlines。调用方必须已持有 redisLock。
     */
    private void floorSeqToTopScore(String listKey, String seqKey) {
        Map<String, Double> zset = zsets.get(listKey);
        if (zset == null || zset.isEmpty()) {
            return;
        }
        long topScore = (long) zset.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);
        long current = 0L;
        String raw = values.get(seqKey);
        if (raw != null) {
            try {
                current = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                current = 0L; // 损坏的 seq 值按丢失处理，抬到最大 score 自愈
            }
        }
        if (topScore > current) {
            incrBy(seqKey, topScore - current);
        }
    }

    /**
     * fake 侧 TTL 命令读回（秒）：键不存在/已过期 = -2，无 TTL = -1，否则剩余量。
     * 与真实 Redis TTL 语义一致（EXISTS 分支按赢家 meta 键自身剩余 TTL 刷新索引用）。
     * 调用方必须已持有 redisLock。
     */
    private long ttlOfSeconds(String key) {
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
        return TimeUnit.MILLISECONDS.toSeconds(remaining);
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
                zsets.remove(entry.getKey());
            }
        }
    }

    private boolean keyExistsInFake(String key) {
        return values.containsKey(key) || hashes.containsKey(key) || zsets.containsKey(key);
    }

    /** 推进 fake 时钟（millis）；随后的读取/脚本执行会按新时刻惰性清除过期键。 */
    private void advanceClock(long millis) {
        fakeNow += millis;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
