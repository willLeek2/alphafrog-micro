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
        when(registry.register(eq("raw-ref"), anyString(), anyString(), anyString(), anyLong()))
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
        when(registry.readContent("raw-ref:test-uuid")).thenReturn("hello from raw ref");
        String content = store.read("run_read", "raw_ref_001");
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
        when(registry.readContent("raw-ref:test-uuid")).thenReturn(sb.toString());
        ToolOutputReadResult result = store.read("run_limit", "raw_ref_001", 0, 6000, null);
        assertEquals(6000, result.getContent().length());
        assertFalse(result.isHasMore());
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
}
