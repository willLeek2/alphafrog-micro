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
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolOutputRefServiceImplTest {

    @TempDir
    Path tempDir;

    private Map<String, String> redis;
    private Map<String, Map<String, String>> hashStore;
    private Map<String, Set<String>> setStore;
    private StringRedisTemplate redisTemplate;
    private PersistentArtifactRegistry registry;
    private ToolOutputRefServiceImpl service;

    @BeforeEach
    void setUp() {
        redis = new LinkedHashMap<>();
        hashStore = new LinkedHashMap<>();
        setStore = new LinkedHashMap<>();
        redisTemplate = mockRedis(redis, hashStore, setStore);
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
        redis.put("agent:persistent-artifact:" + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));
        Path path = Path.of(meta.getPath());

        registry.cleanupExpiredArtifacts();

        assertFalse(Files.exists(path));
        assertFalse(redis.containsKey("agent:persistent-artifact:" + meta.getArtifactId()));
        // D22-5.1.3：cleanup 同删 run 索引项
        assertTrue(setStore.getOrDefault("agent:persistent-artifact:run-list:run-1", Set.of()).isEmpty());
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
        redis.put("agent:persistent-artifact:" + meta.getArtifactId(), new ObjectMapper().writeValueAsString(meta));

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

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis(Map<String, String> store,
                                          Map<String, Map<String, String>> hashes,
                                          Map<String, Set<String>> sets) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        when(ops.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> store.remove(invocation.getArgument(0)) != null)
                .when(template).delete(org.mockito.ArgumentMatchers.anyString());
        when(template.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class)))
                .thenAnswer(invocation -> new MapCursor(store.keySet().iterator()));

        // TTL 查询 fake：本文件不测滑动过期（归 PersistentArtifactRegistryTest），
        // getExpire 一律返回"剩余充足"，使生产侧 extendTtlIfNeeded 成为无害空操作。
        when(template.getExpire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class))).thenReturn(9999L);

        // D22-5.1.3：registry 新增幂等身份 hash 与 run 索引 SET，fake 同步扩展。
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

        // D22-5.1.3 MUST-FIX：registry 值条件 HDEL 走 Lua execute()，fake 以同步块模拟原子 CAS。
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            java.util.List<String> keys = (java.util.List<String>) args[1];
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
        }).when(template).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        // D22-5.1.3 第二轮 MUST-FIX ①④ + 第三轮 62ad12bd：registry 两段 Lua 脚本——
        // 幂等认领（6 ARGV）与 run 列表加入（5 ARGV）。Mockito 5 varargs 按每元素匹配：
        // ARGV 个数不同必须独立 stub，语义与生产脚本逐条对齐（见 PersistentArtifactRegistryTest
        // 同款 fake：游标轮转幽灵清理、EXISTS 分支列表成员资格修复）。
        // run 列表加入脚本（KEYS=[列表 SET, 清理游标键]；5 ARGV：cap、幽灵预算、meta 前缀、
        // artifactId、TTL 秒数）：游标轮转幽灵清理 → SCARD 容量检查 → SADD，原子；
        // 满则返回 FULL 且不写。
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            java.util.List<String> keys = (java.util.List<String>) args[1];
            int cap = Integer.parseInt(String.valueOf(args[2]));
            int budget = Integer.parseInt(String.valueOf(args[3]));
            String metaPrefix = String.valueOf(args[4]);
            String artifactId = String.valueOf(args[5]);
            purgeWithCursor(sets, store, keys.get(0), keys.get(1), metaPrefix, budget);
            Set<String> list = sets.get(keys.get(0));
            int size = list == null ? 0 : list.size();
            if (size >= cap) {
                return "FULL";
            }
            sets.computeIfAbsent(keys.get(0), k -> new LinkedHashSet<>()).add(artifactId);
            return "ADDED";
        }).when(template).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());

        // 幂等认领脚本（KEYS=[身份 hash, 列表 SET, 清理游标键]；6 ARGV：field、候选 ID、
        // cap、幽灵预算、meta 前缀、TTL 秒数）：已有赢家→meta 仍在则修复列表成员资格
        // （SADD 补回）→ EXISTS:赢家ID；幽灵清理→容量检查；未满→HSET 身份+SADD 列表→CLAIMED。
        org.mockito.Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            @SuppressWarnings("unchecked")
            java.util.List<String> keys = (java.util.List<String>) args[1];
            String field = String.valueOf(args[2]);
            String artifactId = String.valueOf(args[3]);
            int cap = Integer.parseInt(String.valueOf(args[4]));
            int budget = Integer.parseInt(String.valueOf(args[5]));
            String metaPrefix = String.valueOf(args[6]);
            Map<String, String> identity = hashes.get(keys.get(0));
            String existing = identity == null ? null : identity.get(field);
            if (existing != null) {
                if (store.containsKey(metaPrefix + existing)) {
                    // 赢家 meta 仍活：修复列表成员资格（缺失即补回），杜绝采纳不可见赢家
                    sets.computeIfAbsent(keys.get(1), k -> new LinkedHashSet<>()).add(existing);
                }
                return "EXISTS:" + existing;
            }
            purgeWithCursor(sets, store, keys.get(1), keys.get(2), metaPrefix, budget);
            Set<String> list = sets.get(keys.get(1));
            int size = list == null ? 0 : list.size();
            if (size >= cap) {
                return "FULL";
            }
            hashes.computeIfAbsent(keys.get(0), k -> new LinkedHashMap<>()).put(field, artifactId);
            sets.computeIfAbsent(keys.get(1), k -> new LinkedHashSet<>()).add(artifactId);
            return "CLAIMED";
        }).when(template).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Object>>any(),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(),
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

    /**
     * fake 侧幽灵清理：与生产 Lua 脚本同语义的游标轮转版。游标以"下一次扫描的偏移量"
     * 存进 values 表（模拟生产里独立的 run-purge-cursor 键，本 fake 复用 store）；每次
     * 至多检查 budget 个成员（按排序快照取窗口 [from, to)），meta 键不存在者当场 SREM；
     * 扫完一整轮（to 到达末尾）删除游标键，下次从头开始。保证：无论活成员排在前面还是
     * 后面，ghost 都会在有限次写入内被清掉，不会被"每次只重复检查同一批活成员"卡死。
     */
    private static void purgeWithCursor(Map<String, Set<String>> sets, Map<String, String> store,
                                        String listKey, String cursorKey, String metaPrefix, int budget) {
        if (budget <= 0) {
            return;
        }
        int offset = 0;
        String cursorVal = store.get(cursorKey);
        if (cursorVal != null) {
            try {
                offset = Integer.parseInt(cursorVal);
            } catch (NumberFormatException ignored) {
                // 游标值损坏视同从头扫
            }
        }
        Set<String> list = sets.get(listKey);
        if (list == null || list.isEmpty()) {
            store.remove(cursorKey);
            return;
        }
        java.util.List<String> snapshot = new ArrayList<>(list);
        Collections.sort(snapshot);
        int from = Math.min(offset, snapshot.size());
        int to = Math.min(from + budget, snapshot.size());
        for (int i = from; i < to; i++) {
            String member = snapshot.get(i);
            if (!store.containsKey(metaPrefix + member)) {
                list.remove(member);
            }
        }
        if (to >= snapshot.size()) {
            store.remove(cursorKey);
        } else {
            store.put(cursorKey, String.valueOf(to));
        }
    }

    private static class MapCursor implements Cursor<String> {
        private final Iterator<String> iterator;

        private MapCursor(Iterator<String> iterator) {
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
