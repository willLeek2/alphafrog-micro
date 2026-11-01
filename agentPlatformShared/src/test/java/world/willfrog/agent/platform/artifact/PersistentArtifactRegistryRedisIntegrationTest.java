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


    // ===== ② 容量硬上限（FULL 不留幽灵痕迹） =====


    // ===== ③ touch 滑动索引 TTL =====


    // ===== ④ cleanup 原子判定（读回当前 JSON 才删；健康制品同轮存活） =====


    // ===== ⑤ EXISTS 分支 TTL 刷新只取赢家 meta 自身剩余 TTL =====


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
