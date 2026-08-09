package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PersistentArtifactRegistry v5 契约的真 Redis 集成门禁（Testcontainers）。
 *
 * <h3>用途</h3>
 * <p>权威契约测试 {@code PersistentArtifactRegistryTest} 用的是线程安全内存 fake——
 * fake 再忠实也是 fake，它的 ZSET/TTL/Lua 语义都是模拟出来的；真 Redis 才是未来
 * 部署环境的门禁。本类在真 Redis（redis:7-alpine 容器）上重放 v5 的关键契约，
 * 每个测试都用 {@link StringRedisTemplate} 直查键状态做断言，不依赖 registry 自身
 * 的读取路径自证。</p>
 *
 * <h3>红线声明（必读）</h3>
 * <p><b>本类在本机绝不运行。</b>依据 frog 2026-08-09 的明确红线：本机禁止拉起
 * Docker。因此：</p>
 * <ul>
 *   <li>本类已被加入 {@code agentPlatformShared/pom.xml} 中 surefire 的
 *       {@code <excludes>} 排除清单，默认 {@code mvn test} 不会触达它；</li>
 *   <li>只有在具备 Docker + 真 Redis 的<b>受批准环境</b>（CI / 其他开发机）显式执行
 *       {@code mvn test -Predis-integration} 时，本类才会运行；</li>
 *   <li>开发者本机绝不以任何方式运行本类（包括「跑一下看看」的手动执行）；</li>
 *   <li>在上述受批准环境真正运行之前，本类保持<b>未运行/未验证</b>状态，不计入任何
 *       「已验证」结论——v5 的已验证结论仍以
 *       {@code PersistentArtifactRegistryTest}（内存 fake，38 例全绿）为准。</li>
 * </ul>
 *
 * <h3>测试清单（每项钉住的契约）</h3>
 * <ul>
 *   <li>① {@link #idempotentClaimShouldProduceSingleWinnerOnRealRedis}——幂等认领单一
 *       赢家：同身份注册两次返回同一 artifactId，run-list ZSET 恰一成员、
 *       run-identity hash 恰一 field、run-seq 键在场；</li>
 *   <li>② {@link #capacityOverflowShouldRejectAndLeaveNoGhostTrace}——容量硬上限：
 *       cap=2 时第三个注册抛容量异常，ZCARD/HLEN 不留幽灵痕迹，候选 meta 与文件回滚；</li>
 *   <li>③ {@link #touchShouldSlideIndexTtlsOnRealRedis}——touch 滑动索引 TTL：读取一次后
 *       列表/身份/序号三键 TTL 均被刷新且不小于 meta 键 TTL，meta 的 expiresAtMillis
 *       同步滑动到未来；</li>
 *   <li>④ {@link #cleanupShouldAtomicallyDeleteExpiredAndKeepHealthy}——cleanup 原子判定：
 *       把 meta JSON 的 expiresAtMillis 改到过去后，一轮 cleanup 同删 meta/文件/ZSET 成员/
 *       身份 field，同轮注册的健康制品必须存活，共享前缀的索引键不被误删；</li>
 *   <li>⑤ {@link #existsBranchShouldRefreshIndexTtlFromWinnerTtlOnly}——EXISTS 分支 TTL
 *       刷新只取赢家：短 TTL 输家采纳赢家后，索引键 TTL 不被输家改短（仍对齐赢家 meta
 *       自身剩余 TTL）。</li>
 * </ul>
 *
 * <h3>构造方式</h3>
 * <p>不引入 Spring 上下文：registry 构造照抄权威测试 setUp——
 * {@code new PersistentArtifactRegistry(redisTemplate, new ObjectMapper(),
 * new AgentStoragePaths(...))}（路径用 {@code @TempDir}），再用
 * {@link ReflectionTestUtils} 设 defaultTtlHours/cleanupScanCount/maxRunListEntries。</p>
 */
@Testcontainers
class PersistentArtifactRegistryRedisIntegrationTest {

    private static final String META_PREFIX = "agent:persistent-artifact:";
    private static final String RUN_LIST_PREFIX = META_PREFIX + "run-list:";
    private static final String RUN_IDENTITY_PREFIX = META_PREFIX + "run-identity:";
    private static final String RUN_SEQ_PREFIX = META_PREFIX + "run-seq:";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @TempDir
    Path tempDir;

    private PersistentArtifactRegistry registry;
    private Path artifactRoot;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        // 每个测试独立：清空容器内所有键
        Set<String> keys = template.keys("*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
        artifactRoot = tempDir.resolve("artifacts");
        Path datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                artifactRoot.toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(template, new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 5);
    }

    // ===== ① 幂等认领单一赢家 =====

    @Test
    void idempotentClaimShouldProduceSingleWinnerOnRealRedis() {
        String runId = "run-it-winner";
        PersistentArtifactRegistration first = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v1", 6);
        PersistentArtifactRegistration second = registry.registerIdempotent(
                runId, "user-1", "python_script", "dup", "脚本", "v2", 6);

        assertEquals(first.getArtifactId(), second.getArtifactId(), "同一幂等身份必须返回同一 artifactId");
        assertEquals("v1", registry.readContent(first.getArtifactId()), "零重写：内容保持首次写入值");

        // 直查真 Redis 键状态：ZSET 恰一成员、身份 hash 恰一 field、序号键在场
        ZSetOperations<String, String> zsetOps = template.opsForZSet();
        HashOperations<String, String, String> hashOps = template.opsForHash();
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        assertEquals(1L, zsetOps.zCard(listKey).longValue(), "run-list ZSET 恰好一个成员");
        assertEquals(first.getArtifactId(), zsetOps.range(listKey, 0, -1).iterator().next());
        assertEquals(1L, hashOps.size(identityKey).longValue(), "run-identity hash 恰好一个 field");
        assertEquals(first.getArtifactId(), hashOps.get(identityKey,
                PersistentArtifactRegistry.identityField("python_script", "dup", null)),
                "身份 field 必须指向赢家 artifactId");
        assertNotNull(template.opsForValue().get(RUN_SEQ_PREFIX + runId), "run-seq 计数器键必须在场");
    }

    // ===== ② 容量硬上限（FULL 不留幽灵痕迹） =====

    @Test
    void capacityOverflowShouldRejectAndLeaveNoGhostTrace() throws Exception {
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 2);
        String runId = "run-it-cap";
        registry.registerIdempotent(runId, "user-1", "raw-ref", "a", "1", "one", 6);
        registry.registerIdempotent(runId, "user-1", "raw-ref", "b", "2", "two", 6);

        // 容量满后的第三个注册：原子拒绝并回滚（可见失败，禁止 meta-only 静默成功）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerIdempotent(runId, "user-1", "raw-ref", "c", "3", "three", 6));
        assertTrue(e.getMessage().contains("capacity exceeded"), e.getMessage());

        // 直查真 Redis：列表不超 cap、身份不留幽灵 field
        ZSetOperations<String, String> zsetOps = template.opsForZSet();
        HashOperations<String, String, String> hashOps = template.opsForHash();
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        assertEquals(2L, zsetOps.zCard(listKey).longValue(), "ZSET 成员数不得突破 cap，不得留下幽灵成员");
        assertEquals(2L, hashOps.size(identityKey).longValue(),
                "FULL 路径从不写身份字段，不得留下幽灵身份");
        // 被拒注册的候选 meta 必须回滚：共享前缀键中恰好只剩两条真 meta
        assertEquals(2, metaKeyCount(), "被拒注册不得残留 meta");
        try (var paths = Files.list(artifactRoot.resolve("raw-ref"))) {
            assertEquals(2, paths.count(), "被拒注册的候选文件必须回滚");
        }
        assertEquals(2, registry.listByRunId(runId).size(), "列表读取结果仍恰为两个合法制品");
    }

    // ===== ③ touch 滑动索引 TTL =====

    @Test
    void touchShouldSlideIndexTtlsOnRealRedis() throws Exception {
        String runId = "run-it-touch";
        long fullTtlSeconds = TimeUnit.HOURS.toSeconds(1);
        PersistentArtifactRegistration registration = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "slide", "1", "payload", 1);
        String metaKey = META_PREFIX + registration.getArtifactId();
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        String seqKey = RUN_SEQ_PREFIX + runId;

        // 等 TTL 实际消耗超过 1 秒，使 touch 前后差异在秒级 getExpire 下可观察
        Thread.sleep(2000L);
        Long listExpireBefore = template.getExpire(listKey);
        assertNotNull(listExpireBefore);
        assertTrue(listExpireBefore < fullTtlSeconds, "读取前列表键 TTL 应已被消耗（< 满额）");

        // 读取触发 touch：单条原子 Lua 内 meta 满额滑动 + 三类索引键只延长不缩短
        assertEquals("payload", registry.readContent(registration.getArtifactId()));

        // 观测顺序有意为先索引键、后 meta 键：touch 脚本内索引键的 EXPIRE 在 meta 之后执行，
        // 索引键的绝对到期时刻不早于 meta；先读索引、后读 meta 即可排除整秒边界造成的 1s
        // 观测误差，使「索引 TTL >= meta TTL」成为确定性断言。
        Long listExpire = template.getExpire(listKey);
        Long identityExpire = template.getExpire(identityKey);
        Long seqExpire = template.getExpire(seqKey);
        Long metaExpire = template.getExpire(metaKey);
        assertNotNull(metaExpire);
        assertNotNull(listExpire);
        assertNotNull(identityExpire);
        assertNotNull(seqExpire);
        assertTrue(metaExpire >= fullTtlSeconds - 5, "meta 键 TTL 必须滑动回近满额");
        assertTrue(listExpire >= metaExpire, "列表键 TTL 不得小于 meta 键 TTL");
        assertTrue(identityExpire >= metaExpire, "身份键 TTL 不得小于 meta 键 TTL");
        assertTrue(seqExpire >= metaExpire, "序号键 TTL 不得小于 meta 键 TTL");
        assertTrue(listExpire > listExpireBefore, "列表键 TTL 必须被 touch 刷新");

        // touch 同时把 meta JSON 内的 expiresAtMillis 滑动到未来（cleanup 判定读到的正是新值）
        PersistentArtifactMeta touched = registry.find(registration.getArtifactId()).orElseThrow();
        assertTrue(touched.getExpiresAtMillis() > System.currentTimeMillis(),
                "touch 必须把 expiresAtMillis 滑动到未来");
    }

    // ===== ④ cleanup 原子判定（读回当前 JSON 才删；健康制品同轮存活） =====

    @Test
    void cleanupShouldAtomicallyDeleteExpiredAndKeepHealthy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String runId = "run-it-cleanup";
        PersistentArtifactRegistration expiredReg = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "expired", "1", "gone", 6);
        PersistentArtifactRegistration healthyReg = registry.registerIdempotent(
                runId, "user-1", "raw-ref", "healthy", "2", "stay", 6);
        String expiredMetaKey = META_PREFIX + expiredReg.getArtifactId();
        String listKey = RUN_LIST_PREFIX + runId;
        String identityKey = RUN_IDENTITY_PREFIX + runId;
        Path expiredFile = Path.of(expiredReg.getMeta().getPath());
        assertTrue(Files.exists(expiredFile));

        // 把到期制品 meta JSON 的 expiresAtMillis 改写到过去（保持其余字段原样）
        ObjectNode metaJson = (ObjectNode) mapper.readTree(template.opsForValue().get(expiredMetaKey));
        metaJson.put("expiresAtMillis", System.currentTimeMillis() - 1);
        template.opsForValue().set(expiredMetaKey, mapper.writeValueAsString(metaJson));

        registry.cleanupExpiredArtifacts();

        // 到期制品：meta 键、文件、ZSET 成员、身份 field 全部消失
        ZSetOperations<String, String> zsetOps = template.opsForZSet();
        HashOperations<String, String, String> hashOps = template.opsForHash();
        assertFalse(Boolean.TRUE.equals(template.hasKey(expiredMetaKey)), "meta 键必须被 Lua 原子判定删除");
        assertFalse(Files.exists(expiredFile), "文件必须同删");
        assertNull(zsetOps.score(listKey, expiredReg.getArtifactId()), "ZSET 成员必须被 ZREM");
        assertFalse(Boolean.TRUE.equals(hashOps.hasKey(identityKey,
                PersistentArtifactRegistry.identityField("raw-ref", "expired", null))),
                "身份 field 必须经值条件 HDEL 同删");

        // 同轮注册的健康制品必须存活（cleanup 的 Lua 判定读回的是未来 expiresAtMillis）
        assertTrue(Boolean.TRUE.equals(template.hasKey(META_PREFIX + healthyReg.getArtifactId())),
                "健康制品必须在同轮清理后仍在");
        assertTrue(Files.exists(Path.of(healthyReg.getMeta().getPath())), "健康制品文件必须保留");
        assertEquals(1L, zsetOps.zCard(listKey).longValue(), "列表应只剩健康制品");
        assertEquals(1L, hashOps.size(identityKey).longValue(), "身份应只剩健康制品的 field");
        assertEquals(1, registry.listByRunId(runId).size(), "清理后列表应只返回健康制品");

        // 与 meta 共享前缀的索引键必须被 cleanup SCAN 显式跳过、不误删
        assertTrue(Boolean.TRUE.equals(template.hasKey(listKey)), "run 索引键不得被 cleanup 误删");
        assertTrue(Boolean.TRUE.equals(template.hasKey(identityKey)), "身份键不得被 cleanup 误删");
        assertTrue(Boolean.TRUE.equals(template.hasKey(RUN_SEQ_PREFIX + runId)), "序号键不得被 cleanup 误删");
    }

    // ===== ⑤ EXISTS 分支 TTL 刷新只取赢家 meta 自身剩余 TTL =====

    @Test
    void existsBranchShouldRefreshIndexTtlFromWinnerTtlOnly() {
        String runId = "run-it-exists";
        long winnerTtlSeconds = TimeUnit.HOURS.toSeconds(6);
        long loserTtlSeconds = TimeUnit.HOURS.toSeconds(1);
        PersistentArtifactRegistration winner = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v1", 6);

        // 输家以更短 TTL 再注册同一身份 → EXISTS 路径采纳赢家
        PersistentArtifactRegistration loser = registry.registerIdempotent(
                runId, "user-1", "python_script", "shared", "脚本", "v2", 1);
        assertEquals(winner.getArtifactId(), loser.getArtifactId(), "输家必须采纳赢家");

        // 若 EXISTS 刷新误取输家 ARGV，索引 TTL 会落到 1h≈3600s；取赢家 meta 自身剩余 TTL
        // 则必须远大于输家值、且不超过赢家注册时的满额
        Long listExpire = template.getExpire(RUN_LIST_PREFIX + runId);
        Long identityExpire = template.getExpire(RUN_IDENTITY_PREFIX + runId);
        Long seqExpire = template.getExpire(RUN_SEQ_PREFIX + runId);
        assertNotNull(listExpire);
        assertNotNull(identityExpire);
        assertNotNull(seqExpire);
        assertTrue(listExpire > loserTtlSeconds,
                "列表键 TTL 不得被短 TTL 输家改短（实际=" + listExpire + "s）");
        assertTrue(identityExpire > loserTtlSeconds,
                "身份键 TTL 不得被短 TTL 输家改短（实际=" + identityExpire + "s）");
        assertTrue(seqExpire > loserTtlSeconds,
                "序号键 TTL 不得被短 TTL 输家改短（实际=" + seqExpire + "s）");
        assertTrue(listExpire <= winnerTtlSeconds, "列表键 TTL 不得超过赢家注册时的满额");
        assertTrue(identityExpire <= winnerTtlSeconds, "身份键 TTL 不得超过赢家注册时的满额");
        assertTrue(seqExpire <= winnerTtlSeconds, "序号键 TTL 不得超过赢家注册时的满额");

        // 输家候选零残留：只剩赢家一条 meta；列表恰一成员；内容仍是赢家首写值
        assertEquals(1, metaKeyCount(), "输家候选 meta 必须回滚");
        assertEquals(1L, template.opsForZSet().zCard(RUN_LIST_PREFIX + runId).longValue(),
                "run-list ZSET 恰一成员");
        assertEquals("v1", registry.readContent(winner.getArtifactId()), "内容保持赢家首次写入值");
    }

    // ===== 工具方法 =====

    /** 统计共享前缀下的真 meta 键数量（排除 run 索引/身份/序号三类索引键）。 */
    private long metaKeyCount() {
        Set<String> keys = template.keys(META_PREFIX + "*");
        if (keys == null) {
            return 0L;
        }
        return keys.stream()
                .filter(k -> !k.startsWith(RUN_LIST_PREFIX)
                        && !k.startsWith(RUN_IDENTITY_PREFIX)
                        && !k.startsWith(RUN_SEQ_PREFIX))
                .count();
    }
}
