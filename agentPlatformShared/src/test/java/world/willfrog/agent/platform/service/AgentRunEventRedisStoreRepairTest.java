package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import world.willfrog.agent.platform.entity.AgentRunEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补投写入与普通写入的差别：已经在里面的成员一个字节都不写、不续期；缺的才补，
 * 而且只补这条事实自己还剩的寿命。
 *
 * <p>还钉住一件事：同一条事实，普通投射与补投写出的成员必须逐字一样。事件流按成员去重，
 * 两边只要有一处不一样（比如一边用 Java 对象里的时间、一边用库里那一行的时间），
 * 同一个事件就会在流里变成两个成员。</p>
 */
class AgentRunEventRedisStoreRepairTest {

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zsetOperations;
    private AgentRunEventRedisStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        zsetOperations = Mockito.mock(ZSetOperations.class);
        AgentLlmLocalConfigLoader llmConfigLoader = Mockito.mock(AgentLlmLocalConfigLoader.class);
        Mockito.lenient().when(llmConfigLoader.current()).thenReturn(Optional.empty());
        Mockito.lenient().when(redisTemplate.opsForZSet()).thenReturn(zsetOperations);
        store = new AgentRunEventRedisStore(redisTemplate, new ObjectMapper(), llmConfigLoader, 7L);
    }

    @Test
    void anExistingMemberIsNeitherWrittenNorRenewed() {
        when(redisTemplate.getExpire(store.eventsKey("run-1"))).thenReturn(3_600L);
        when(zsetOperations.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(false);

        assertThat(store.repairMissing(received("run-1", 1, OffsetDateTime.now().minusMinutes(5))))
                .as("成员已经在流里：这一条不需要补")
                .isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void aMissingMemberIsAddedOnceAndKeepsTheRemainingLifetime() {
        OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(10);
        when(redisTemplate.getExpire(store.eventsKey("run-2"))).thenReturn(-2L);
        when(zsetOperations.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(true);

        assertThat(store.repairMissing(received("run-2", 1, createdAt))).isTrue();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate).expire(eq(store.eventsKey("run-2")), ttl.capture());
        assertThat(ttl.getValue())
                .as("补的是这条事实自己还剩的寿命，不是从头再算一份完整保留期")
                .isLessThan(store.retention())
                .isGreaterThan(Duration.ofMinutes(6 * 24 * 60));
    }

    @Test
    void anExistingKeyKeepsItsOwnExpiry() {
        // key 还在（有到期时间）：补一个缺的成员进去，不能顺手动它的到期时间。
        when(redisTemplate.getExpire(store.eventsKey("run-3"))).thenReturn(3_600L);
        when(zsetOperations.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(true);

        assertThat(store.repairMissing(received("run-3", 2, OffsetDateTime.now()))).isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void anOldFactWhoseLifetimeIsGoneStillGetsAFloorOfOneSecond() {
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(30);
        when(redisTemplate.getExpire(store.eventsKey("run-4"))).thenReturn(-2L);
        when(zsetOperations.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(true);

        store.repairMissing(received("run-4", 1, createdAt));
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate).expire(eq(store.eventsKey("run-4")), ttl.capture());
        assertThat(ttl.getValue())
                .as("寿命早就过去了：给一个最小的存活时间，别写出一个负数或零")
                .isEqualTo(Duration.ofSeconds(1));
    }

    /**
     * 同一条事实，普通投射与补投写出的成员逐字一样。
     *
     * <p>这条断言是「同一个事件在流里只有一个成员」的直接依据：只要两边有一处不同
     * （时间、负载文本、字段顺序），事件流就会当成两个成员收下。</p>
     */
    @Test
    void theOrdinaryWriteAndTheRepairWriteProduceTheSameMember() {
        AgentRunEvent event = received("run-5", 1, OffsetDateTime.now().minusMinutes(1));

        // 普通投射：批大小默认 1，append 会立刻落一次批写。
        RedisOperations<String, String> operations = Mockito.mock(RedisOperations.class);
        Mockito.lenient().when(operations.opsForZSet()).thenReturn(zsetOperations);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        });
        store.append(event);

        ArgumentCaptor<java.util.Set<ZSetOperations.TypedTuple<String>>> written =
                ArgumentCaptor.forClass(java.util.Set.class);
        verify(zsetOperations).add(eq(store.eventsKey("run-5")), written.capture());
        String ordinaryMember = written.getValue().iterator().next().getValue();

        ArgumentCaptor<String> repaired = ArgumentCaptor.forClass(String.class);
        when(redisTemplate.getExpire(store.eventsKey("run-5"))).thenReturn(600L);
        when(zsetOperations.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(true);
        store.repairMissing(event);
        verify(zsetOperations).addIfAbsent(eq(store.eventsKey("run-5")), repaired.capture(), anyDouble());

        assertThat(repaired.getValue())
                .as("两条路写出的成员必须逐字一致，否则同一条事实会变成两个成员")
                .isEqualTo(ordinaryMember);
    }

    /** 保留期只有一份出处：写入用的和修补回扫用的是同一个值。 */
    @Test
    void theRetentionComesFromOnePlace() {
        assertThat(store.retention()).isEqualTo(Duration.ofDays(7));
    }

    private static AgentRunEvent received(String runId, int seq, OffsetDateTime createdAt) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId(1L);
        event.setRunId(runId);
        event.setSeq(seq);
        event.setEventType("RUN_RECEIVED");
        event.setPayloadJson("{\"hello\":\"world\"}");
        event.setCreatedAt(createdAt);
        return event;
    }
}
