package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunRawRefStoreImplTest {

    @Mock
    private PersistentArtifactRegistry registry;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RunRawRefStoreImpl store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        PersistentArtifactRegistration reg = PersistentArtifactRegistration.builder()
                .artifactId("raw-ref:test-uuid")
                .build();
        // D22-5.1.3：store 改走显式上下文 registerExplicit（runId/userId 显式传递，
        // logicalId 固定为 runId，非幂等——同 run 多条 rawRef 不撞身份）。
        when(registry.registerExplicit(anyString(), anyString(), eq("raw-ref"),
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(reg);

        store = new RunRawRefStoreImpl(registry, redisTemplate);
    }

    @Test
    void register_shouldReturnRawRef001() {
        when(valueOps.increment("agent:raw-ref-counter:run_001")).thenReturn(1L);
        String shortId = store.register("run_001", "user_001", "test", "hello world", 3600);
        assertEquals("raw_ref_001", shortId);
    }

    @Test
    void register_shouldIncrementSequence() {
        when(valueOps.increment("agent:raw-ref-counter:run_seq")).thenReturn(1L, 2L, 3L);
        assertEquals("raw_ref_001", store.register("run_seq", "user_001", "test", "a", 3600));
        assertEquals("raw_ref_002", store.register("run_seq", "user_001", "test", "b", 3600));
        assertEquals("raw_ref_003", store.register("run_seq", "user_001", "test", "c", 3600));
    }

    @Test
    void register_shouldNotCollideAcrossRuns() {
        when(valueOps.increment("agent:raw-ref-counter:run_a")).thenReturn(1L);
        when(valueOps.increment("agent:raw-ref-counter:run_b")).thenReturn(1L);
        assertEquals("raw_ref_001", store.register("run_a", "user_001", "test", "a", 3600));
        assertEquals("raw_ref_001", store.register("run_b", "user_001", "test", "b", 3600));
    }

    @Test
    void read_shouldReturnFullContent() {
        when(valueOps.increment("agent:raw-ref-counter:run_read")).thenReturn(1L);
        store.register("run_read", "user_001", "test", "hello from raw ref", 3600);
        when(hashOps.get("agent:raw-ref-mapping:run_read", "raw_ref_001"))
                .thenReturn("raw-ref:test-uuid");
        when(registry.readContentStrict("raw-ref:test-uuid", "run_read", "user_001"))
                .thenReturn("hello from raw ref");
        String content = store.read("run_read", "user_001", "raw_ref_001");
        assertEquals("hello from raw ref", content);
    }

    @Test
    void read_shouldRespectLargeLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) sb.append("x");
        when(valueOps.increment("agent:raw-ref-counter:run_limit")).thenReturn(1L);
        store.register("run_limit", "user_001", "test", sb.toString(), 3600);
        when(hashOps.get("agent:raw-ref-mapping:run_limit", "raw_ref_001"))
                .thenReturn("raw-ref:test-uuid");
        when(registry.readContentStrict("raw-ref:test-uuid", "run_limit", "user_001"))
                .thenReturn(sb.toString());
        ToolOutputReadResult result = store.read("run_limit", "user_001", "raw_ref_001", 0, 6000, null);
        assertEquals(6000, result.getContent().length());
        assertFalse(result.isHasMore());
    }

    @Test
    void read_shouldRejectWrongOrBlankUser() {
        // 第三轮 MUST-FIX ② 反测：短格式 raw_ref 读取不再因 runId 对上就放行——
        // 内容读取一律经 readContentStrict 四值严格归属校验，userId 错误或空白都
        // 由 store 层 fail-closed 抛出（全量读与窗口读两条入口都覆盖）。
        when(valueOps.increment("agent:raw-ref-counter:run_reject")).thenReturn(1L);
        store.register("run_reject", "user_001", "test", "secret", 3600);
        when(hashOps.get("agent:raw-ref-mapping:run_reject", "raw_ref_001"))
                .thenReturn("raw-ref:test-uuid");
        when(registry.readContentStrict("raw-ref:test-uuid", "run_reject", "user_evil"))
                .thenThrow(new IllegalArgumentException(
                        "Artifact does not belong to current run/user: raw-ref:test-uuid"));
        when(registry.readContentStrict("raw-ref:test-uuid", "run_reject", " "))
                .thenThrow(new IllegalArgumentException(
                        "Artifact does not belong to current run/user: raw-ref:test-uuid"));

        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_reject", "user_evil", "raw_ref_001"));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_reject", " ", "raw_ref_001"));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_reject", "user_evil", "raw_ref_001", 0, 100, null));
    }

    @Test
    void belongsToRun_shouldReturnTrueForOwnedRef() {
        when(valueOps.increment("agent:raw-ref-counter:run_belong")).thenReturn(1L);
        store.register("run_belong", "user_001", "test", "data", 3600);
        when(hashOps.hasKey("agent:raw-ref-mapping:run_belong", "raw_ref_001")).thenReturn(true);
        when(hashOps.hasKey("agent:raw-ref-mapping:run_belong", "raw_ref_999")).thenReturn(false);
        when(hashOps.hasKey("agent:raw-ref-mapping:run_other", "raw_ref_001")).thenReturn(false);
        assertTrue(store.belongsToRun("run_belong", "raw_ref_001"));
        assertFalse(store.belongsToRun("run_belong", "raw_ref_999"));
        assertFalse(store.belongsToRun("run_other", "raw_ref_001"));
    }

    @Test
    void counter_shouldRecoverSequenceAfterSimulatedRestart() {
        when(valueOps.increment("agent:raw-ref-counter:run_restore")).thenReturn(6L);
        assertEquals("raw_ref_006", store.register("run_restore", "user_001", "test", "restored", 3600));
    }

    @Test
    void register_shouldSetTtlOnCounterAndMapping() {
        when(valueOps.increment("agent:raw-ref-counter:run_ttl")).thenReturn(1L);
        store.register("run_ttl", "user_001", "test", "ttl_test", 30);
        verify(redisTemplate).expire("agent:raw-ref-counter:run_ttl", 30, TimeUnit.SECONDS);
        verify(redisTemplate).expire("agent:raw-ref-mapping:run_ttl", 30, TimeUnit.SECONDS);
    }

    @Test
    void register_shouldForwardExplicitContextToRegistry() {
        // D22-5.1.3：userId 参数必须真正传给 registry（此前被丢弃、靠 AgentContext 补）；
        // 且走非幂等 registerExplicit（同 run 第二条 logicalId=runId 的 rawRef 不撞身份）。
        when(valueOps.increment("agent:raw-ref-counter:run_ctx")).thenReturn(1L);
        store.register("run_ctx", "user_ctx", "display", "payload", 3600);
        verify(registry).registerExplicit("run_ctx", "user_ctx", "raw-ref", "run_ctx",
                "display", "payload", 1L);
    }
}
