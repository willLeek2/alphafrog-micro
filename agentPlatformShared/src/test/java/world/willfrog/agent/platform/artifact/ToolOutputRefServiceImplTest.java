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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolOutputRefServiceImpl 服务层契约测试（D22-5.1.3 严格归属校验、显式上下文 overload、
 * 分页/过滤读取、过期清理同删 run 索引项、路径逃逸拒绝）。
 *
 * <h3>v5 转换说明</h3>
 * <p>PersistentArtifactRegistry 已完成 v5 重写：run 级制品索引从「集合 SET + SSCAN
 * 提示式游标清理」改成「有序集合 ZSET（成员=制品ID，score=每 run 一把单调递增序号）
 * + 窗口轮转幽灵清理」，新增 run-seq 计数器键；读取 touch 改成单条原子 Lua 脚本
 * （返回状态码 0/1/2，同时更新 meta 与索引键 TTL）；过期清理改成每条 meta 走一条
 * Lua 判定脚本（读回当前 JSON 的 expiresAtMillis 才决定删不删）。本文件的 fake Redis
 * 按 PersistentArtifactRegistryTest 的 v5 全量 fake 裁剪而来，只保留被测服务实际触达
 * 的操作面，按 execute() 的 ARGV 个数分发（Mockito 5 对 varargs 按每元素匹配）：</p>
 * <ul>
 *   <li>3 参数（1 KEY + 1 ARGV nowMillis）→ 过期清理判定 CLEANUP_META_SCRIPT
 *       （cleanupExpiredArtifacts 逐条 meta 执行）；</li>
 *   <li>4 参数（1 KEY + 2 ARGV field/期望值）→ 值条件 HDEL CONDITIONAL_HDEL_SCRIPT
 *       （cleanup 同删幂等身份字段时原子清除）；</li>
 *   <li>6 参数（4 KEYS + 4 ARGV）→ 读取 touch TOUCH_SCRIPT（read / locatorFor 链路）；</li>
 *   <li>7 参数（2 KEYS + 5 ARGV）→ run 列表加入 RUN_LIST_ADD_SCRIPT（本服务的注册
 *       全部走非幂等 registerExplicit）。</li>
 * </ul>
 * <p>8 参数的幂等认领脚本 ATOMIC_CLAIM_SCRIPT 本服务触达不到（没有任何
 * registerIdempotent / registerExternalIdempotent 调用），故不设 stub。同样被裁掉的
 * 还有权威 fake 里只服务于 listByRunId 的 ZSetOperations.range stub 与 zrange 助手、
 * 只服务于 EXISTS 分支 TTL 读回的 ttlOfSeconds 助手、以及推进假时钟的 advanceClock
 * （本文件不测滑动过期，归 PersistentArtifactRegistryTest）。</p>
 *
 * <p>两钟分离：meta JSON 里的 expiresAtMillis/createdAtMillis/lastAccessAtMillis 用
 * 真实墙钟（生产用 System.currentTimeMillis()），fake 的 fakeNow 只管 Redis 键的 TTL
 * deadline。清理场景模拟「内容时间流逝」靠改写 meta JSON 的 expiresAtMillis 字段
 * （cleanup 的 Lua 判定会读回当前 JSON，所以合法），而不是把 fakeNow 推过 Redis
 * deadline。旧 v4 fake 里「SET 索引 + purgeWithCursor 游标轮转幽灵清理」已随游标机制
 * 整体废除，替换为 ZSET + purgeWindow 窗口轮转。</p>
 */
class ToolOutputRefServiceImplTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    /** run 级 artifactId 索引键前缀（v5：ZSET，成员=artifactId，score=每 run 一把单调序号）。 */
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";

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
     * fake 时钟 + 每键过期时刻（millis）。注册/读取按 fakeNow 记录 TTL 截止时间，
     * 各 fake 操作入口由 sweepExpired 惰性清除过期键（与真实 Redis 惰性/定期过期删除
     * 的可观察语义一致）。直接 values.put 而不改动 deadlines 的键保留既有 deadline。
     * 注意：meta JSON 内的 expiresAtMillis 字段用真实墙钟（生产 buildMeta/touch/cleanup
     * 都用 System.currentTimeMillis()），与本 fake 的 Redis-TTL 时钟是两套独立语义。
     * 本文件不推进假时钟（滑动过期反测归 PersistentArtifactRegistryTest）。
     */
    private long fakeNow;
    private Map<String, Long> deadlines;
    /**
     * 模拟 Redis 单线程执行：所有 Lua 脚本 fake（加入/touch/清理判定/值条件 HDEL）
     * 共用这一把锁，保证任一脚本执行期间没有其他脚本插入——这是真实 Redis 原子性的
     * 最小等价模拟。
     */
    private final Object redisLock = new Object();
    private StringRedisTemplate redisTemplate;
    private PersistentArtifactRegistry registry;
    private ToolOutputRefServiceImpl service;

    @BeforeEach
    void setUp() {
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        zsets = new ConcurrentHashMap<>();
        fakeNow = System.currentTimeMillis();
        deadlines = new ConcurrentHashMap<>();
        redisTemplate = mockRedis();
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
    void legacyReadAndLocatorShouldRejectContextlessMetaLikeExplicit() {
        // 复审修复第②项：历史无上下文制品（meta 的 runId/userId 为空）现在经任何入口
        // 都拒绝读取/定位（fail-closed）——旧的"legacy 入口宽容放行"合同作废。旧入口
        // （从 AgentContext 补齐上下文）与显式入口一律走同一套严格归属校验。
        AgentContext.clear();
        PersistentArtifactRegistration registration =
                service.registerRawOutput("tool-legacy", "旧输出", "legacy-payload");
        String rawRef = registration.getArtifactId();
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");

        // ① 旧入口 read：meta 无 runId/userId，严格校验拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.read(rawRef, 0, 100, null));
        // ② 旧入口 locatorFor：同样拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.locatorFor(rawRef));
        // ③ 显式入口 read：即使调用方四值齐全，meta 侧为空也拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.read("run-1", "user-1", rawRef, 0, 100, null));
    }

    @Test
    void cleanupExpiredArtifactsShouldDeleteOwnedFileAndMeta() throws Exception {
        PersistentArtifactRegistration registration = registry.register("raw-ref", "tool-1", "工具输出", "payload", 1);
        PersistentArtifactMeta meta = registry.find(registration.getArtifactId()).orElseThrow();
        meta.setExpiresAtMillis(System.currentTimeMillis() - 1);
        // 两钟分离：直接改写 values 表里的 meta JSON 的 expiresAtMillis 到过去来模拟内容
        // 时间流逝——cleanup 的 Lua 判定读回的就是当前 JSON，所以合法；values.put 不改
        // deadlines，键自身 Redis TTL deadline 不变（不是把 fakeNow 推过 deadline）。
        values.put(META_PREFIX + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));
        Path path = Path.of(meta.getPath());

        registry.cleanupExpiredArtifacts();

        assertFalse(Files.exists(path));
        assertFalse(values.containsKey(META_PREFIX + meta.getArtifactId()));
        // D22-5.1.3：cleanup 同删 run 索引项（v5：run 索引是 ZSET，同删 = ZREM 掉该成员）
        Map<String, Double> runList = zsets.get(RUN_LIST_PREFIX + "run-1");
        assertTrue(runList == null || runList.isEmpty());
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
        // 两钟分离（同上）：改写 meta JSON 的 expiresAtMillis 到过去模拟内容时间流逝
        values.put(META_PREFIX + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));

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

    // ===== fake redis（线程安全；支持本服务触达的四种 Lua 脚本：
    //       列表加入 / 读取 touch / 过期清理判定 / 值条件 HDEL） =====

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
        // ZREM：cleanup 同删 run 索引项（removeFromIndices）触达
        when(zsetOps.remove(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Map<String, Double> zset = zsets.get(invocation.getArgument(0));
                        return zset != null && zset.remove(invocation.getArgument(1).toString()) != null
                                ? 1L : 0L;
                    }
                });

        // ===== Lua execute() fake =====
        // Mockito 5 对 varargs 按"每个匹配器对一个可变参数"匹配，脚本 ARGV 个数不同
        // 必须独立 stub。本服务触达四种脚本，按 arity 3、4、6、7 顺序注册（与权威文件
        // PersistentArtifactRegistryTest 同款顺序）；全部 stub 共用 redisLock，模拟
        // Redis 单线程原子执行。8 参数的幂等认领脚本（6 ARGV）本服务触达不到（注册
        // 全走非幂等 registerExplicit），不设 stub。

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
        // meta 缺失 → 0；SET 新 meta + 满额 EXPIRE；身份步（空槽 HSETNX 补建、他人占用 → 2、
        // field='' 整步跳过）；成员 score 以新序号同步（缺失 ZADD NX 补回）；三类索引键
        // 只延长不缩短 EXPIRE；成功 → 1
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
        // INCRBY 发号 + ZADD → 列表键与序号键只延长不缩短 TTL 刷新，原子；满则 FULL 不写
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
                Map<String, Double> zset = zsets.get(keys.get(0));
                int size = zset == null ? 0 : zset.size();
                if (size >= cap) {
                    return "FULL";
                }
                long seq = incrBy(keys.get(1), 1);
                zsets.computeIfAbsent(keys.get(0), k -> new ConcurrentHashMap<>())
                        .put(artifactId, (double) seq);
                if (ttlSeconds > 0) {
                    extendOnlyTtl(keys.get(0), ttlSeconds);
                    extendOnlyTtl(keys.get(1), ttlSeconds);
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

        return template;
    }

    /**
     * fake 侧读取 touch 脚本（与生产 TOUCH_SCRIPT 逐步同语义，状态码合同 0/1/2）。
     * 调用方必须已持有 redisLock。
     */
    private long fakeTouch(String metaKey, String listKey, String identityKey, String seqKey,
                           String metaJson, long ttlSeconds, String field, String artifactId) {
        if (!values.containsKey(metaKey)) {
            return 0L; // meta 已在 find 与 touch 之间消失：读取必须失败，绝不复活
        }
        values.put(metaKey, metaJson);
        if (ttlSeconds > 0) {
            deadlines.put(metaKey, fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds)); // 满额滑动
        }
        if (!field.isEmpty()) {
            // 身份步（仅幂等制品）：空槽 HSETNX 补建；他人占用 → 2（绝不覆盖）
            Map<String, String> identity = hashes.get(identityKey);
            String holder = identity == null ? null : identity.get(field);
            if (holder == null) {
                boolean added = hashes.computeIfAbsent(identityKey, k -> new ConcurrentHashMap<>())
                        .putIfAbsent(field, artifactId) == null;
                if (!added) {
                    return 2L;
                }
            } else if (!holder.equals(artifactId)) {
                return 2L;
            }
        }
        long seq = incrBy(seqKey, 1);
        Map<String, Double> zset = zsets.get(listKey);
        Double currentScore = zset == null ? null : zset.get(artifactId);
        if (currentScore == null) {
            zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent(artifactId, (double) seq); // ZADD NX：绝不覆盖并发写入
        } else {
            zset.put(artifactId, (double) seq); // 成员 score 同步到新序号（队尾）
        }
        if (ttlSeconds > 0) {
            extendOnlyTtl(listKey, ttlSeconds);
            extendOnlyTtl(identityKey, ttlSeconds);
            extendOnlyTtl(seqKey, ttlSeconds);
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
     * 者当场 ZREM；窗口内活成员用 INCRBY 新发的连续序号重新打分、整体移到所有未检查
     * 成员之后（严格大于任何未检查成员的得分）。轮转状态编码在 score 排序本身，不存在
     * 独立游标键。调用方必须已持有 redisLock。
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
        if (!live.isEmpty()) {
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

    /** ZSET 成员按 (score 升序, 成员字典序) 排序——与真实 Redis ZSET 排序一致。 */
    private static List<String> sortedMembers(Map<String, Double> zset) {
        return zset.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * fake 侧只延长不缩短 TTL 刷新（对应生产脚本内 extendOnly：t == -2 no-op，
     * t == -1 补设，t < ttl 延长）。键不存在（-2）时 EXPIRE 是 no-op——绝不给已消失
     * 的键凭空造 deadline。调用方必须已持有 redisLock。
     */
    private void extendOnlyTtl(String key, long ttlSeconds) {
        if (!keyExistsInFake(key)) {
            return; // ttl == -2
        }
        long desired = fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds);
        Long current = deadlines.get(key);
        if (current == null || current < desired) { // ttl == -1（无 TTL）补设；否则只延长
            deadlines.put(key, desired);
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
                zsets.remove(entry.getKey());
            }
        }
    }

    private boolean keyExistsInFake(String key) {
        return values.containsKey(key) || hashes.containsKey(key) || zsets.containsKey(key);
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
