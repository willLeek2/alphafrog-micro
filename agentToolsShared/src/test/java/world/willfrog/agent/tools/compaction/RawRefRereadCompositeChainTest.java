package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.artifact.PersistentArtifactMeta;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistration;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.artifact.RunRawRefStoreImpl;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * raw_ref 全链路组合测试：真实的 RereadToolHandler（工具层）+ 真实的 RunRawRefStoreImpl
 * （短 ID 映射层）+ 真实的 PersistentArtifactRegistry（制品存储层）叠在同一片 fake Redis
 * 上跑完整读写链——三层之间没有任何 mock 隔断，钉住的是「组合后的行为」而不是单层合同
 * （单层合同各归其位：registry 契约归 agentPlatformShared 的 PersistentArtifactRegistryTest
 * 38 例，服务层归 ToolOutputRefServiceImplTest/AgentArtifactServiceTest，工具层参数门禁归
 * RereadToolHandlerTest）。
 *
 * <p>fake Redis 与权威契约测试同款语义（values/hashes/zsets 三张表 + 可控时钟 fakeNow +
 * 每键 deadline 惰性过期 + 五种 Lua 脚本按 ARGV 个数分发共用一把锁模拟 Redis 单线程），
 * 另按本链路触达面补齐三类直操作：INCR（raw-ref 计数器原子发号）、EXPIRE（计数器/映射键
 * TTL）、hash 直读写（shortId→artifactId 映射）。ZSET 索引、run-seq 序号键、touch 状态码、
 * cleanup Lua 判定等 v5 语义与权威测试逐条一致。</p>
 *
 * <p>两钟分离：fakeNow 只管 Redis 键的 TTL deadline；meta JSON 内的 expiresAtMillis 用真实
 * 墙钟（生产 cleanup 用 System.currentTimeMillis()）。模拟「内容时间流逝」靠改写 meta JSON
 * 的 expiresAtMillis 字段（cleanup 的 Lua 会读回当前 JSON），而不是把 fakeNow 推过 Redis
 * deadline。</p>
 *
 * <p>钉住的组合行为：</p>
 * <ul>
 *   <li>①注册→短 ID→reread 全链路往返（计数器 INCR 发号、映射落哈希、制品进 ZSET 索引、
 *       内容原样读回）—— {@link #fullChainRegisterAndRereadShouldRoundTripContent}</li>
 *   <li>②keyword/range 两种读取模式经全链路后的切片正确性 ——
 *       {@link #rereadKeywordAndRangeModesShouldSliceCorrectlyThroughChain}</li>
 *   <li>③内容层严格归属：映射只能证明 shortId 属于该 run，userId 错误/空白必须在
 *       registry.readContentStrict 处 fail-closed —— {@link #wrongOrBlankUserShouldBeRejectedAtContentLayer}</li>
 *   <li>④跨 run 短 ID 不可解析 —— {@link #crossRunShortIdShouldNotResolve}</li>
 *   <li>⑤belongsToRun 按 run 隔离 —— {@link #belongsToRunShouldDistinguishRuns}</li>
 *   <li>⑥经全链路读取触发的 touch 必须把 meta 与三类索引键一起滑动 ——
 *       {@link #touchThroughChainShouldSlideAllFourKeys}</li>
 *   <li>⑦映射键与制品键生命周期解耦：cleanup 删掉 meta/文件/索引后映射仍在（不同前缀），
 *       但内容读取必须随 meta 消失而 fail-closed —— {@link #mappingOutlivingMetaShouldStillFailClosedOnContent}</li>
 * </ul>
 */
class RawRefRereadCompositeChainTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";
    private static final String RUN_IDENTITY_PREFIX = META_PREFIX + "run-identity:";
    private static final String RUN_SEQ_PREFIX = META_PREFIX + "run-seq:";
    private static final String COUNTER_PREFIX = "agent:raw-ref-counter:";
    private static final String MAPPING_PREFIX = "agent:raw-ref-mapping:";

    @TempDir
    Path tempDir;

    private Map<String, String> values;
    private Map<String, Map<String, String>> hashes;
    /** run 列表 fake（ZSET 语义）：键 → (成员 artifactId → score)，score = 每 run 一把单调序号。 */
    private Map<String, Map<String, Double>> zsets;
    private long fakeNow;
    private Map<String, Long> deadlines;
    /** 所有脚本 fake 共用一把锁，模拟 Redis 单线程原子执行。 */
    private final Object redisLock = new Object();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PersistentArtifactRegistry registry;
    private RunRawRefStoreImpl store;
    private RereadToolHandler handler;
    private Path artifactRoot;

    @BeforeEach
    void setUp() {
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        zsets = new ConcurrentHashMap<>();
        fakeNow = System.currentTimeMillis();
        deadlines = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mockRedis();
        artifactRoot = tempDir.resolve("artifacts");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                artifactRoot.toString(),
                tempDir.resolve("datasets").toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(redisTemplate, new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 100);
        store = new RunRawRefStoreImpl(registry, redisTemplate);
        handler = new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper,
                Optional.empty(), Optional.of(store));
        AgentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    // ===== ① 全链路往返 =====

    @Test
    void fullChainRegisterAndRereadShouldRoundTripContent() throws Exception {
        AgentContext.setRunId("run-chain");
        AgentContext.setUserId("user-chain");
        String content = "line1\nline2-needle\nline3";

        String shortId = store.register("run-chain", "user-chain", "大输出", content, 7200);
        assertEquals("raw_ref_001", shortId, "计数器 INCR 首发号必须生成 raw_ref_001");
        assertEquals("1", values.get(COUNTER_PREFIX + "run-chain"), "计数器必须是原子 INCR 出来的 1");
        assertNotNull(deadlines.get(COUNTER_PREFIX + "run-chain"), "计数器键必须被 EXPIRE 上 TTL");

        // 映射落哈希：shortId → artifactId，且映射键有 TTL
        Map<String, String> mapping = hashes.get(MAPPING_PREFIX + "run-chain");
        assertNotNull(mapping, "映射哈希必须在场");
        String artifactId = mapping.get("raw_ref_001");
        assertNotNull(artifactId, "raw_ref_001 必须映射到一个真实 artifactId");
        assertNotNull(deadlines.get(MAPPING_PREFIX + "run-chain"), "映射键必须被 EXPIRE 上 TTL");

        // 制品必须真实落进 registry：列表恰一项、meta 在场、ZSET 成员在场
        List<PersistentArtifactMeta> listed = registry.listByRunId("run-chain");
        assertEquals(1, listed.size());
        assertEquals(artifactId, listed.get(0).getArtifactId());
        assertEquals(1.0, zsets.get(RUN_LIST_PREFIX + "run-chain").get(artifactId),
                "首个制品的 ZSET score 必须是序号 1");

        // 工具层 reread 全链路读回：ok=true、内容一字不差
        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_001", null, null, null), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(content, data.get("content"), "全链路读回内容必须与注册原文一致");
        assertEquals(Boolean.FALSE, data.get("hasMore"));

        // 第二次注册：计数器 +1、短 ID 递增、列表两项
        String shortId2 = store.register("run-chain", "user-chain", "大输出2", "second", 7200);
        assertEquals("raw_ref_002", shortId2);
        assertEquals("2", values.get(COUNTER_PREFIX + "run-chain"));
        assertEquals(2, registry.listByRunId("run-chain").size());
    }

    // ===== ② keyword / range 切片 =====

    @Test
    void rereadKeywordAndRangeModesShouldSliceCorrectlyThroughChain() throws Exception {
        AgentContext.setRunId("run-slice");
        AgentContext.setUserId("user-slice");
        String longContent = "abcdefghij".repeat(300); // 3000 字符
        store.register("run-slice", "user-slice", "长输出", longContent, 7200);

        // range 模式第一段：offset=0 limit=1500（无 keyword 时 limit 必须 ≥1001，1500 合法）
        Map<String, Object> first = objectMapper.readValue(
                handler.reread("raw_ref_001", null, 0, 1500), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, first.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) first.get("data");
        assertEquals(longContent.substring(0, 1500), firstData.get("content"));
        assertEquals(Boolean.TRUE, firstData.get("hasMore"), "3000 字符读 1500 必须还有剩余");
        assertEquals(1500, firstData.get("nextOffset"));
        assertEquals(3000, firstData.get("totalLength"));

        // range 模式续读：从 nextOffset 继续读完
        Map<String, Object> second = objectMapper.readValue(
                handler.reread("raw_ref_001", null, 1500, 1500), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> secondData = (Map<String, Object>) second.get("data");
        assertEquals(longContent.substring(1500), secondData.get("content"));
        assertEquals(Boolean.FALSE, secondData.get("hasMore"));
        assertEquals(3000, secondData.get("nextOffset"));

        // keyword 模式：只回匹配行（经映射→制品→内容→逐行过滤全链）
        AgentContext.setRunId("run-kw");
        store.register("run-kw", "user-slice", "kw", "alpha\nneedle-one\nbeta\nneedle-two\n", 7200);
        Map<String, Object> kw = objectMapper.readValue(
                handler.reread("raw_ref_001", "needle", null, null), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, kw.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kwData = (Map<String, Object>) kw.get("data");
        assertEquals("needle-one\nneedle-two", kwData.get("content"));
        assertEquals("needle", kwData.get("keyword"));
    }

    // ===== ③ 内容层严格归属（映射不足以放行） =====

    @Test
    void wrongOrBlankUserShouldBeRejectedAtContentLayer() {
        AgentContext.setRunId("run-guard");
        AgentContext.setUserId("user-guard");
        store.register("run-guard", "user-guard", "秘密", "top-secret-content", 7200);

        // 同 run 但 userId 错误：映射层解析成功（shortId 确实属于该 run），
        // 内容层 readContentStrict 四值校验 fail-closed——绝不放行。
        // 注意归属拒绝有独立消息（"does not belong to current run/user"），
        // 与 meta 缺失的 "Artifact not found" 区分——两者都是 IllegalArgumentException fail-closed
        AgentContext.setUserId("intruder");
        IllegalArgumentException wrongUser = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(wrongUser.getMessage().contains("Artifact does not belong to current run/user"),
                wrongUser.getMessage());

        // userId 空白同样 fail-closed
        AgentContext.setUserId("");
        assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
    }

    // ===== ④ 跨 run 短 ID 不可解析 =====

    @Test
    void crossRunShortIdShouldNotResolve() {
        AgentContext.setRunId("run-A");
        AgentContext.setUserId("user-A");
        store.register("run-A", "user-A", "A 的输出", "content-A", 7200);

        // 另一个 run 拿着同样的短 ID：映射键按 run 隔离，解析必须直接失败
        AgentContext.setRunId("run-B");
        IllegalArgumentException notFound = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(notFound.getMessage().contains("rawRef not found"), notFound.getMessage());
    }

    // ===== ⑤ belongsToRun 按 run 隔离 =====

    @Test
    void belongsToRunShouldDistinguishRuns() {
        store.register("run-owner", "user-1", "输出", "content", 7200);
        assertTrue(store.belongsToRun("run-owner", "raw_ref_001"));
        assertFalse(store.belongsToRun("run-other", "raw_ref_001"), "别的 run 不得认领该 shortId");
        assertFalse(store.belongsToRun(null, "raw_ref_001"));
        assertFalse(store.belongsToRun("run-owner", null));
    }

    // ===== ⑥ 全链路读取的 touch：存活索引键同滑动，不创建缺失的身份键 =====

    @Test
    void touchThroughChainShouldSlideLiveKeysAndLeaveIdentityAbsent() throws Exception {
        AgentContext.setRunId("run-slide-chain");
        AgentContext.setUserId("user-slide-chain");
        long t0 = fakeNow;
        // ttlSeconds=7200 → RunRawRefStoreImpl 折算 ttlHours=2 → 注册侧键 deadline = t0+2h
        store.register("run-slide-chain", "user-slide-chain", "滑动", "slide-content", 7200);
        List<PersistentArtifactMeta> listed = registry.listByRunId("run-slide-chain");
        String metaKey = META_PREFIX + listed.get(0).getArtifactId();
        long original = t0 + TimeUnit.HOURS.toMillis(2);
        assertEquals(original, deadlines.get(metaKey));
        assertEquals(original, deadlines.get(RUN_LIST_PREFIX + "run-slide-chain"));
        assertEquals(original, deadlines.get(RUN_SEQ_PREFIX + "run-slide-chain"));
        // 非幂等 registerExplicit 不走身份路径（doRegisterContent 仅 addToRunList，
        // 只延长 list+seq）：身份哈希键从头到尾不应被创建
        assertNull(deadlines.get(RUN_IDENTITY_PREFIX + "run-slide-chain"),
                "非幂等 raw-ref 不得创建身份键");
        // raw-ref 两个辅助键按 ttlSeconds 持有自己的 deadline（折算成整小时后与注册侧同值）
        assertEquals(original, deadlines.get(COUNTER_PREFIX + "run-slide-chain"));
        assertEquals(original, deadlines.get(MAPPING_PREFIX + "run-slide-chain"));

        // 1 小时后经工具层读一次：touch 必须把 meta/list/seq 一起滑回满额 2h；
        // 身份键保持缺席（extend-only EXPIRE 不创建缺失键）；两个辅助键不参与 touch 滑动
        advanceClock(TimeUnit.HOURS.toMillis(1));
        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_001", null, null, null), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, response.get("ok"));
        long slid = t0 + TimeUnit.HOURS.toMillis(3);
        assertEquals(slid, deadlines.get(metaKey), "meta 必须滑动回满额");
        assertEquals(slid, deadlines.get(RUN_LIST_PREFIX + "run-slide-chain"), "列表键必须随 touch 同滑动");
        assertEquals(slid, deadlines.get(RUN_SEQ_PREFIX + "run-slide-chain"), "序号键必须随 touch 同滑动");
        assertNull(deadlines.get(RUN_IDENTITY_PREFIX + "run-slide-chain"),
                "touch 不得创建缺失的身份键");
        assertEquals(original, deadlines.get(COUNTER_PREFIX + "run-slide-chain"),
                "计数器键不参与 touch 滑动，保持注册时 deadline");
        assertEquals(original, deadlines.get(MAPPING_PREFIX + "run-slide-chain"),
                "映射键不参与 touch 滑动，保持注册时 deadline");
    }

    // ===== ⑦ 映射比制品活得久，内容读取仍必须 fail-closed =====

    @Test
    void mappingOutlivingMetaShouldStillFailClosedOnContent() throws Exception {
        AgentContext.setRunId("run-decay");
        AgentContext.setUserId("user-decay");
        store.register("run-decay", "user-decay", "会过期的", "decay-content", 7200);
        List<PersistentArtifactMeta> listed = registry.listByRunId("run-decay");
        PersistentArtifactMeta meta = listed.get(0);
        String metaKey = META_PREFIX + meta.getArtifactId();

        // 改写 meta JSON 的 expiresAtMillis 到过去（两钟分离：不动 Redis 键的 deadline，
        // 只改 cleanup Lua 读回判定的内容时间）
        Map<String, Object> metaJson = objectMapper.readValue(
                values.get(metaKey), new TypeReference<>() {});
        metaJson.put("expiresAtMillis", System.currentTimeMillis() - 1000);
        values.put(metaKey, objectMapper.writeValueAsString(metaJson));

        registry.cleanupExpiredArtifacts();

        // 制品侧全清：meta 键、ZSET 成员、身份 field、文件
        assertFalse(values.containsKey(metaKey), "过期 meta 必须被 cleanup 删除");
        Map<String, Double> zset = zsets.get(RUN_LIST_PREFIX + "run-decay");
        assertTrue(zset == null || !zset.containsKey(meta.getArtifactId()), "ZSET 成员必须同删");
        assertFalse(java.nio.file.Files.exists(artifactRoot.resolve("raw-ref")
                .resolve(meta.getArtifactId() + ".txt")), "制品文件必须同删");

        // 映射键不同前缀（agent:raw-ref-mapping:），cleanup 的 SCAN 不命中——映射仍在，
        // belongsToRun 仍 true；但内容读取必须随 meta 消失 fail-closed
        assertTrue(store.belongsToRun("run-decay", "raw_ref_001"),
                "映射与制品生命周期解耦：cleanup 不清映射");
        IllegalArgumentException gone = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(gone.getMessage().contains("Artifact not found"), gone.getMessage());
    }

    // ===== fake redis（权威契约测试同款语义 + 本链路三类直操作） =====

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        // 带 TTL 写：记录 deadline = fakeNow + ttl（meta 侧统一滑动过期协议）
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
        // INCR（raw-ref 计数器原子发号）：键缺失从 0 起算；真实 INCR 保留键 TTL——不碰 deadlines
        when(valueOps.increment(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        return incrBy(invocation.getArgument(0), 1);
                    }
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            synchronized (redisLock) {
                deadlines.remove(invocation.getArgument(0));
                return values.remove(invocation.getArgument(0)) != null;
            }
        }).when(template).delete(org.mockito.ArgumentMatchers.anyString());
        // EXPIRE：键存在才设置 deadline 并返回 true（与真实 Redis 一致）
        when(template.expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        String key = invocation.getArgument(0);
                        if (!keyExistsInFake(key)) {
                            return Boolean.FALSE;
                        }
                        deadlines.put(key, fakeNow + ((TimeUnit) invocation.getArgument(2))
                                .toMillis(invocation.getArgument(1)));
                        return Boolean.TRUE;
                    }
                });
        // SCAN 返回三张表全部键（模拟真实 Redis 中索引键也会被 META_PREFIX* 命中；
        // 非 meta 前缀键由生产 cleanup 按前缀跳过/判定保守保留）
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

        // hash 直操作（shortId→artifactId 映射；registry 的身份哈希不经过这里——全在脚本内）
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);
        org.mockito.Mockito.doAnswer(invocation -> {
            synchronized (redisLock) {
                sweepExpired();
                hashes.computeIfAbsent(invocation.getArgument(0), k -> new ConcurrentHashMap<>())
                        .put(String.valueOf((Object) invocation.getArgument(1)),
                                String.valueOf((Object) invocation.getArgument(2)));
            }
            return null;
        }).when(hashOps).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(hashOps.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Map<String, String> h = hashes.get(invocation.getArgument(0));
                        return h == null ? null : h.get(String.valueOf((Object) invocation.getArgument(1)));
                    }
                });
        when(hashOps.hasKey(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Map<String, String> h = hashes.get(invocation.getArgument(0));
                        return h != null && h.containsKey(String.valueOf((Object) invocation.getArgument(1)));
                    }
                });

        ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
        when(template.opsForZSet()).thenReturn(zsetOps);
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
                        return zset != null && zset.remove(invocation.getArgument(1).toString()) != null
                                ? 1L : 0L;
                    }
                });

        // ===== Lua execute() fake：五种脚本按 ARGV 个数分发（3=清理判定 / 4=值条件 HDEL /
        // 6=touch / 7=列表加入 / 8=幂等认领），共用 redisLock 模拟 Redis 单线程 =====

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

    // ===== fake 助手（与权威契约测试逐条同语义） =====

    /** fake 侧幂等认领脚本（与生产 ATOMIC_CLAIM_SCRIPT 逐步同语义）。调用方必须已持有 redisLock。 */
    private String fakeClaim(String identityKey, String listKey, String seqKey,
                             String field, String artifactId, int cap, int budget,
                             String metaPrefix, long ttlSeconds) {
        Map<String, String> identity = hashes.get(identityKey);
        String existing = identity == null ? null : identity.get(field);
        if (existing != null) {
            if (values.containsKey(metaPrefix + existing)) {
                Map<String, Double> zset = zsets.get(listKey);
                Double score = zset == null ? null : zset.get(existing);
                if (score == null) {
                    long repairSeq = incrBy(seqKey, 1);
                    zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>())
                            .put(existing, (double) repairSeq);
                }
                // 索引 TTL 刷新只取赢家 meta 键自身剩余 TTL（绝不取输家传入的 ttlSeconds）
                long winnerTtl = ttlOfSeconds(metaPrefix + existing);
                if (winnerTtl > 0) {
                    extendOnlyTtl(identityKey, winnerTtl);
                    extendOnlyTtl(listKey, winnerTtl);
                    extendOnlyTtl(seqKey, winnerTtl);
                }
            }
            return "EXISTS:" + existing;
        }
        purgeWindow(listKey, seqKey, metaPrefix, budget);
        Map<String, Double> zset = zsets.get(listKey);
        int size = zset == null ? 0 : zset.size();
        if (size >= cap) {
            return "FULL";
        }
        hashes.computeIfAbsent(identityKey, k -> new ConcurrentHashMap<>()).put(field, artifactId);
        long claimSeq = incrBy(seqKey, 1);
        zsets.computeIfAbsent(listKey, k -> new ConcurrentHashMap<>()).put(artifactId, (double) claimSeq);
        if (ttlSeconds > 0) {
            extendOnlyTtl(identityKey, ttlSeconds);
            extendOnlyTtl(listKey, ttlSeconds);
            extendOnlyTtl(seqKey, ttlSeconds);
        }
        return "CLAIMED";
    }

    /** fake 侧读取 touch 脚本（与生产 TOUCH_SCRIPT 逐步同语义，状态码合同 0/1/2）。调用方必须已持有 redisLock。 */
    private long fakeTouch(String metaKey, String listKey, String identityKey, String seqKey,
                           String metaJson, long ttlSeconds, String field, String artifactId) {
        if (!values.containsKey(metaKey)) {
            return 0L; // meta 已消失：读取必须失败，绝不复活
        }
        values.put(metaKey, metaJson);
        if (ttlSeconds > 0) {
            deadlines.put(metaKey, fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds)); // 满额滑动
        }
        if (!field.isEmpty()) {
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
                    .putIfAbsent(artifactId, (double) seq);
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

    /** fake 侧过期清理判定（与生产 CLEANUP_META_SCRIPT 同语义：判定与 DEL 原子）。调用方必须已持有 redisLock。 */
    private long fakeCleanupVerdict(String metaKey, long now) {
        String raw = values.get(metaKey);
        if (raw == null) {
            return 0L;
        }
        Object parsed;
        try {
            parsed = new ObjectMapper().readValue(raw, Object.class);
        } catch (Exception e) {
            return -1L; // cjson.decode 失败 → 损坏，绝不盲删
        }
        if (!(parsed instanceof Map)) {
            return -1L; // 非对象 → 损坏
        }
        Object expiresAt = ((Map<?, ?>) parsed).get("expiresAtMillis");
        if (!(expiresAt instanceof Number)) {
            return 0L; // 缺失/非数字 → 保守保留
        }
        if (((Number) expiresAt).longValue() <= now) {
            deadlines.remove(metaKey);
            values.remove(metaKey);
            return 1L;
        }
        return 0L;
    }

    /** fake 侧窗口轮转幽灵清理（与生产认领/加入脚本内的清理段同语义）。调用方必须已持有 redisLock。 */
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
                zset.remove(member);
            }
        }
        if (!live.isEmpty()) {
            long base = incrBy(seqKey, live.size());
            for (int i = 0; i < live.size(); i++) {
                zset.put(live.get(i), (double) (base - live.size() + i + 1));
            }
        }
    }

    /** fake 侧 INCRBY：键缺失从 0 起算；保留键 TTL（不碰 deadlines），与真实 Redis 一致。 */
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

    /** fake 侧 ZRANGE：按 (score 升序, 成员字典序) 返回 [start, end] 闭区间，负索引从末尾数。 */
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

    /** fake 侧只延长不缩短 TTL 刷新（t == -2 no-op，t == -1 补设，t < ttl 延长）。 */
    private void extendOnlyTtl(String key, long ttlSeconds) {
        if (!keyExistsInFake(key)) {
            return;
        }
        long desired = fakeNow + TimeUnit.SECONDS.toMillis(ttlSeconds);
        Long current = deadlines.get(key);
        if (current == null || current < desired) {
            deadlines.put(key, desired);
        }
    }

    /** fake 侧 TTL 命令读回（秒）：键不存在/已过期 = -2，无 TTL = -1，否则剩余量。 */
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

    /** fake 侧惰性过期：deadline 已到的键从三张表与 deadline 表移除。 */
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

    /** 推进 fake 时钟；随后的读取/脚本执行按新时刻惰性清除过期键。 */
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
