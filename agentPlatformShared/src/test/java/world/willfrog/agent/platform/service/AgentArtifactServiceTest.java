package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.artifact.PersistentArtifactMeta;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistration;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D22-5.1.3：AgentArtifactService 降级为 user API 门面 + legacy 适配器后的行为钉住。
 *
 * <ul>
 *   <li>①list 走 registry：已注册制品映射到原 DTO（字段零漂移）</li>
 *   <li>②惰性幂等：事件派生制品首次 list 注册，重复 list 同 ID、零新文件、run 索引不膨胀</li>
 *   <li>③load Registry-first：registry 命中直读，不触碰 legacy 解析</li>
 *   <li>④Base64 回退只读：旧位置可读，回退后 registry 仍无该 ID（不得注册）</li>
 *   <li>⑤跨 run 拒绝沿用 IllegalArgumentException("artifact not found")</li>
 *   <li>⑥@Value 存储键摘除：构造器不再持有 datasetPath/artifactStoragePath 字段，
 *       根路径一律经 AgentStoragePaths 注入（键面 grep 另证）</li>
 *   <li>⑦retention 两档 + download max-bytes 保护保持</li>
 * </ul>
 *
 * <p>Redis 用线程安全内存 fake（values/hashes/zsets 三张表 + 可控时钟与每键 deadline
 * 惰性过期；run 级索引为 ZSET：成员 = artifactId，score = 每 run 一把单调序号，
 * 没有任何游标键；五种 Lua 脚本——过期清理判定（1 ARGV）、值条件 HDEL（2 ARGV）、
 * 读取 touch（4 ARGV）、列表加入（5 ARGV）、幂等认领（6 ARGV）——的 execute() 按
 * ARGV 个数分发，共用一把锁模拟 Redis 单线程原子执行），registry 为真实
 * {@link PersistentArtifactRegistry} 实例，文件落 @TempDir；不起 Spring 上下文。
 * mock 风格与 {@code PersistentArtifactRegistryTest} / {@code ToolOutputRefServiceImplTest}
 * 一致：不起 MockitoExtension，纯 {@code mock()} 手工装配。</p>
 */
class AgentArtifactServiceTest {

    private static final String RUN_LIST_PREFIX = "agent:persistent-artifact:run-list:";
    private static final String META_PREFIX = "agent:persistent-artifact:";

    private AgentRunEventService eventService;
    private AgentRunEventMapper agentRunEventMapper;

    private AgentArtifactService service;
    private PersistentArtifactRegistry registry;

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
     * fake 时钟 + 每键过期时刻（millis）：带 TTL 的写入按 fakeNow 记录截止时间，
     * 所有 fake 操作入口先 sweepExpired 惰性清除过期键（与真实 Redis 惰性/定期过期
     * 删除的可观察语义一致）。直接 values.put 而不记录 deadline 的键视为无 TTL（持久），
     * 与 Redis PERSIST 等价。本文件不推进时钟（滑动过期归 PersistentArtifactRegistryTest
     * 钉住），时钟机制在场只为与权威 fake 语义逐条一致。
     */
    private long fakeNow;
    private Map<String, Long> deadlines;
    /**
     * 模拟 Redis 单线程执行：所有 Lua 脚本 fake（认领/加入/touch/清理判定/值条件 HDEL）
     * 共用这一把锁，保证任一脚本执行期间没有其他脚本插入——这是真实 Redis 原子性的
     * 最小等价模拟。
     */
    private final Object redisLock = new Object();
    private Path artifactRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        eventService = mock(AgentRunEventService.class);
        agentRunEventMapper = mock(AgentRunEventMapper.class);
        values = new ConcurrentHashMap<>();
        hashes = new ConcurrentHashMap<>();
        zsets = new ConcurrentHashMap<>();
        fakeNow = System.currentTimeMillis();
        deadlines = new ConcurrentHashMap<>();
        artifactRoot = tempDir.resolve("artifacts");
        datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                artifactRoot.toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(mockRedis(), new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 1000);

        service = new AgentArtifactService(eventService, agentRunEventMapper, new ObjectMapper(),
                registry, storagePaths);
        ReflectionTestUtils.setField(service, "normalRetentionDays", 7);
        ReflectionTestUtils.setField(service, "adminRetentionDays", 30);
        ReflectionTestUtils.setField(service, "downloadMaxBytes", 1024L * 1024L);
    }

    // ===== ① list 走 registry：已注册制品映射 DTO =====


    // ===== ② 惰性幂等注册（含原 TODO 事件流 DTO 契约） =====



    // ===== ③ load Registry-first =====



    // ===== ④ Base64 回退只读 =====


    // ===== ⑤ 跨 run 拒绝 =====


    // ===== ⑦ retention 两档 + success-only + download 保护 =====




    // ===== helpers =====

    private static AgentRun run(String runId, String userId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private static AgentArtifactMessage findById(List<AgentArtifactMessage> artifacts, String artifactId) {
        return artifacts.stream().filter(a -> artifactId.equals(a.getArtifactId()))
                .findFirst().orElseThrow();
    }

    private static String findId(List<AgentArtifactMessage> artifacts, String type) {
        return artifacts.stream().filter(a -> type.equals(a.getType()))
                .map(AgentArtifactMessage::getArtifactId).findFirst().orElseThrow();
    }

    private static String legacyId(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<AgentRunEvent> todoEvents(String runId, OffsetDateTime eventTime) {
        AgentRunEvent e1 = new AgentRunEvent();
        e1.setRunId(runId);
        e1.setSeq(1);
        e1.setEventType("TODO_LIST_CREATED");
        e1.setPayloadJson("{\"plan\":{\"items\":[{\"id\":\"todo_1\",\"toolName\":\"executePython\",\"params\":{\"code\":\"print(1)\",\"dataset_ids\":\"ds1\"}}]}}");
        e1.setCreatedAt(eventTime);

        AgentRunEvent e2 = new AgentRunEvent();
        e2.setRunId(runId);
        e2.setSeq(2);
        e2.setEventType("TODO_STARTED");
        e2.setPayloadJson("{\"todo_id\":\"todo_1\",\"tool\":\"executePython\"}");
        e2.setCreatedAt(eventTime);

        AgentRunEvent e3 = new AgentRunEvent();
        e3.setRunId(runId);
        e3.setSeq(3);
        e3.setEventType("TODO_FINISHED");
        e3.setPayloadJson("{\"todo_id\":\"todo_1\",\"success\":true,\"output_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"ds1\\\"}}\"}");
        e3.setCreatedAt(eventTime);
        return List.of(e1, e2, e3);
    }

    private static List<AgentRunEvent> toolCallEvents(String runId, OffsetDateTime eventTime, String datasetId) {
        AgentRunEvent start = new AgentRunEvent();
        start.setRunId(runId);
        start.setSeq(1);
        start.setEventType("TOOL_CALL_STARTED");
        start.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"print(1)\",\"dataset_ids\":\"" + datasetId + "\"}}");
        start.setCreatedAt(eventTime);

        AgentRunEvent finish = new AgentRunEvent();
        finish.setRunId(runId);
        finish.setSeq(2);
        finish.setEventType("TOOL_CALL_FINISHED");
        finish.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":true,\"result_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"" + datasetId + "\\\"}}\"}");
        finish.setCreatedAt(eventTime);
        return List.of(start, finish);
    }

    private static List<AgentRunEvent> mixedSuccessToolCallEvents(String runId, OffsetDateTime eventTime) {
        AgentRunEvent start1 = new AgentRunEvent();
        start1.setRunId(runId);
        start1.setSeq(1);
        start1.setEventType("TOOL_CALL_STARTED");
        start1.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"print(1)\"}}");
        start1.setCreatedAt(eventTime);

        AgentRunEvent finish1 = new AgentRunEvent();
        finish1.setRunId(runId);
        finish1.setSeq(2);
        finish1.setEventType("TOOL_CALL_FINISHED");
        finish1.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":true,\"result_preview\":\"{\\\"ok\\\":true}\"}");
        finish1.setCreatedAt(eventTime);

        AgentRunEvent start2 = new AgentRunEvent();
        start2.setRunId(runId);
        start2.setSeq(3);
        start2.setEventType("TOOL_CALL_STARTED");
        start2.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"boom()\"}}");
        start2.setCreatedAt(eventTime.plusSeconds(1));

        AgentRunEvent finish2 = new AgentRunEvent();
        finish2.setRunId(runId);
        finish2.setSeq(4);
        finish2.setEventType("TOOL_CALL_FINISHED");
        finish2.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":false,\"result_preview\":\"{\\\"ok\\\":false}\"}");
        finish2.setCreatedAt(eventTime.plusSeconds(1));
        return List.of(start1, finish1, start2, finish2);
    }

    // ===== fake redis（values/hashes/zsets 三张表 + 可控时钟；五种 Lua 脚本按 ARGV 个数分发；
    //       模式同 PersistentArtifactRegistryTest） =====

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        // 带 TTL 写：记录 deadline = fakeNow + ttl（统一滑动过期协议的 meta 侧）
        Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long ttl = invocation.getArgument(2);
            TimeUnit unit = invocation.getArgument(3);
            synchronized (redisLock) {
                sweepExpired();
                values.put(key, invocation.getArgument(1));
                deadlines.put(key, fakeNow + unit.toMillis(ttl));
            }
            return null;
        }).when(valueOps).set(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(TimeUnit.class));
        when(valueOps.get(ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        return values.get(invocation.getArgument(0));
                    }
                });
        Mockito.doAnswer(invocation -> {
            synchronized (redisLock) {
                deadlines.remove(invocation.getArgument(0));
                return values.remove(invocation.getArgument(0)) != null;
            }
        }).when(template).delete(ArgumentMatchers.anyString());

        ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
        when(template.opsForZSet()).thenReturn(zsetOps);
        // ZRANGE 语义：按 (score 升序, 成员字典序) 返回 [start, end] 区间（负索引从末尾数）
        when(zsetOps.range(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        return zrange(invocation.getArgument(0),
                                invocation.getArgument(1), invocation.getArgument(2));
                    }
                });
        when(zsetOps.remove(ArgumentMatchers.anyString(), ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    synchronized (redisLock) {
                        sweepExpired();
                        Map<String, Double> zset = zsets.get(invocation.getArgument(0));
                        return zset != null && zset.remove(invocation.getArgument(1).toString()) != null
                                ? 1L : 0L;
                    }
                });

        // ===== Lua execute() fake =====
        // Mockito 5 对 varargs 按"每个匹配器对一个可变参数"匹配，五种脚本 ARGV 个数不同
        // （清理判定 1 / 值条件 HDEL 2 / touch 4 / 列表加入 5 / 幂等认领 6），各需独立 stub。
        // 五个 stub 共用 redisLock，模拟 Redis 单线程：任一脚本执行期间其他脚本不得插入。
        // stub 按 arity 3、4、6、7、8 的顺序注册（与 PersistentArtifactRegistryTest 一致）。

        // 过期清理判定脚本（1 个 ARGV：now 毫秒）：读回当前 JSON，expiresAtMillis 是数字
        // 且 <= now 才 DEL 返回 1；键缺失返回 0；JSON 损坏/非对象返回 -1；无日期返回 0
        Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) args[1];
            long now = Long.parseLong(String.valueOf(args[2]));
            synchronized (redisLock) {
                sweepExpired();
                return fakeCleanupVerdict(keys.get(0), now);
            }
        }).when(template).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any());

        // 值条件 HDEL（2 个 ARGV：field、期望值）：仅当 field 值仍等于期望 artifactId 时删除
        Mockito.doAnswer(invocation -> {
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
        }).when(template).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any());

        // 读取 touch 脚本（KEYS=[meta, 列表 ZSET, 身份 hash, run-seq]；4 个 ARGV：
        // 新 meta JSON、TTL 秒数、身份 field（非幂等传空串）、artifactId）：
        // meta 缺失 → 0；SET 新 meta + 满额 EXPIRE；身份步（空槽 HSETNX 补建、他人占用 → 2、
        // field='' 整步跳过）；成员 score 以新序号同步（缺失 ZADD NX 补回）；三类索引键
        // 只延长不缩短 EXPIRE；成功 → 1
        Mockito.doAnswer(invocation -> {
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
        }).when(template).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any());

        // run 列表加入脚本（KEYS=[列表 ZSET, run-seq]；5 个 ARGV：cap、幽灵预算、
        // meta 前缀、artifactId、TTL 秒数）：窗口轮转幽灵清理 → ZCARD 容量检查 →
        // INCRBY 发号 + ZADD → 列表键与序号键只延长不缩短 TTL 刷新，原子；满则 FULL 不写
        Mockito.doAnswer(invocation -> {
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
        }).when(template).execute(ArgumentMatchers.<RedisScript<Object>>any(),
                ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any());

        // 幂等认领脚本（KEYS=[身份 hash, 列表 ZSET, run-seq]；6 个 ARGV：field、候选 ID、
        // cap、幽灵预算、meta 前缀、TTL 秒数）：已有赢家 → meta 仍在则修复列表成员资格
        // （ZSCORE 缺失即以新发序号 ZADD 补回）+ 按赢家 meta 键自身剩余 TTL 做三类索引键
        // 只延长不缩短刷新（绝不取输家 ARGV）→ EXISTS:赢家ID；否则窗口轮转幽灵清理 →
        // ZCARD 容量检查 → HSET 身份 + INCRBY 发号 + ZADD + TTL 刷新 → CLAIMED。
        // EXISTS 与写入互斥且整段原子——输家拿到 EXISTS 时赢家身份+列表必然已落盘且可见
        Mockito.doAnswer(invocation -> {
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
        }).when(template).execute(ArgumentMatchers.<RedisScript<Object>>any(),
                ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any());

        return template;
    }

    /**
     * fake 侧幂等认领脚本（与生产 ATOMIC_CLAIM_SCRIPT 逐步同语义）。
     * 调用方必须已持有 redisLock。
     */
    private String fakeClaim(String identityKey, String listKey, String seqKey,
                             String field, String artifactId, int cap, int budget,
                             String metaPrefix, long ttlSeconds) {
        Map<String, String> identity = hashes.get(identityKey);
        String existing = identity == null ? null : identity.get(field);
        if (existing != null) {
            if (values.containsKey(metaPrefix + existing)) {
                // 赢家 meta 仍活：修复列表成员资格（ZSCORE 缺失即以新发序号 ZADD 补回）
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
}
