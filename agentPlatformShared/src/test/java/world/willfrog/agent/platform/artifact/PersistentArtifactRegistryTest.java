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


    // ===== ② 旧 ID 可读（legacy 入口兼容） =====



    // ===== ③ 同一 logical artifact 多次注册不重复 =====




    // ===== ④ 跨 run/user 拒绝（strict fail-closed，无宽容 seam） =====


    // ===== ⑥ 路径逃逸拒绝 =====



    // ===== ⑦ 过期清理同删 meta + run index =====


    // ===== MUST-FIX ②：run 索引原子有界（硬上限、可见失败、fail-closed） =====




    // ===== MUST-FIX ①：幂等抢占单一赢家（latch/interleaving） =====



    // ===== MUST-FIX ④：TOCTOU（注册后 symlink swap / 内容篡改） =====





    // ===== MUST-FIX ⑤：身份 collision-free 编码 =====


    // ===== 第二轮 MUST-FIX ①：认领单条 Lua 原子提交（FULL 不留幽灵、输家不先于赢家列表提交） =====



    // ===== 第二轮 MUST-FIX ③：有界流式读取 + 父目录 symlink 换入 =====



    // ===== 第二轮 MUST-FIX ④：幽灵成员自愈（过期幽灵不得永久占 cap） =====


    // ===== 有界索引 / 陈旧索引自愈 / cleanup 键跳过 =====




    // ===== 第三轮 MUST-FIX ①（第五轮重写）：有界幽灵清理必须真「有界且保证进展」 =====




    // ===== 第三轮 MUST-FIX ③（第五轮重写）：统一滑动过期协议（touch 同滑动 / 防索引先过期 / EXISTS 修复） =====




    // ===== 第五轮 MUST-FIX ④：TTL 归一化唯一权威点 + EXISTS 刷新取赢家 TTL =====



    // ===== 第五轮 MUST-FIX ⑤：touch 原子 + 更新 expiresAtMillis + 状态码绝不吞 =====



    // ===== 第五轮 ②：序号键降级（丢失不丢数据、不报错） =====


    // ===== 第六轮 ②：seq 丢失后持续推进不降级（N>128 + 高分 + 尾部 ghost） =====



    // ===== 第六轮 ③：永久 TTL(-1) 两向（既有保持永久 + 本次新建获有限 TTL） =====


    // ===== v7 ③：空 ZSET 自动删 key + 脚本内重建（重建键必须获有限 TTL） =====





    // ===== 边界约束 2：score 是单调序号不是毫秒时间 =====


    // ===== 第五轮 ⑤：cleanup Lua 原子判定（损坏/无日期 meta 绝不盲删） =====


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
