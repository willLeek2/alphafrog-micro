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
import org.springframework.data.redis.core.script.RedisScript;
import world.willfrog.agent.platform.entity.AgentRunEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补投写入与普通写入的差别：已经在里面的成员一个字节都不写、不续期；缺的才补，
 * 而且只补这条事实自己还剩的寿命。
 *
 * <p>两条硬要求在这里钉住。一是同一条事实、普通投射与补投写出的成员必须逐字一样：事件流按成员
 * 去重，两边差一个字符就是两个成员。二是补投的三件事（查有没有、补缺的、设剩余寿命）必须在
 * 同一次服务端执行里做完：分三次做，中间退出会留下一条永远不会过期的事件流，并发写入时还可能
 * 把别人刚设好的保留期改短。</p>
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
        Mockito.lenient().when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), anyList(),
                any(Object.class), any(Object.class), any(Object.class))).thenReturn(0L);
        store = new AgentRunEventRedisStore(redisTemplate, new ObjectMapper(), llmConfigLoader, 7L);
    }

    @Test
    void anExistingMemberIsNeitherWrittenNorRenewed() {
        assertThat(store.repairMissing(received("run-1", 1, OffsetDateTime.now().minusMinutes(5))))
                .as("脚本说成员已经在流里：这一条不需要补")
                .isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(redisTemplate, never()).getExpire(anyString());
    }

    @Test
    void aMissingMemberKeepsTheRemainingLifetimeOfItsOwnFact() {
        OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(10);
        scriptReturns(1L);

        assertThat(store.repairMissing(received("run-2", 1, createdAt))).isTrue();

        List<String> args = repairScriptArgs();
        assertThat(args.get(0)).as("补进去的成员").isNotBlank();
        assertThat(Long.parseLong(args.get(2)))
                .as("补的是这条事实自己还剩的寿命，不是从头再算一份完整保留期")
                .isLessThan(store.retention().toMillis())
                .isGreaterThan(Duration.ofMinutes(6 * 24 * 60).toMillis());
    }

    @Test
    void anOldFactWhoseLifetimeIsGoneStillGetsAFloorOfOneSecond() {
        scriptReturns(1L);
        store.repairMissing(received("run-4", 1, OffsetDateTime.now().minusDays(30)));
        assertThat(Long.parseLong(repairScriptArgs().get(2)))
                .as("寿命早就过去了：给一个最小的存活时间，别写出一个负数或零")
                .isEqualTo(1_000L);
    }

    /**
     * 三件事在同一次服务端执行里做完：这条用例直接量「只发出了一次脚本调用」，
     * 并检查脚本正文里确实带着那三条语义。
     */
    @Test
    void theRepairIsOneServerSideExecutionThatCoversAllThreeSteps() {
        scriptReturns(1L);
        store.repairMissing(received("run-6", 1, OffsetDateTime.now()));

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(1)).execute(script.capture(), anyList(),
                any(Object.class), any(Object.class), any(Object.class));
        String body = script.getValue().getScriptAsString();
        assertThat(body)
                .as("有就不动：先查成员在不在")
                .contains("ZSCORE")
                .as("缺了才补")
                .contains("ZADD")
                .as("只在 key 是刚建起来的、或者本来就没有到期时间时才设剩余寿命")
                .contains("EXISTS")
                .contains("PTTL")
                .contains("PEXPIRE");
        // 分三次做才会用到的那些单条命令，这里一个都不该出现。
        verify(redisTemplate, never()).getExpire(anyString());
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(zsetOperations, never()).addIfAbsent(anyString(), anyString(), anyDouble());
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

        scriptReturns(1L);
        store.repairMissing(event);
        assertThat(repairScriptArgs().get(0))
                .as("两条路写出的成员必须逐字一致，否则同一条事实会变成两个成员")
                .isEqualTo(ordinaryMember);
    }

    /** 保留期只有一份出处：写入用的和修补回扫用的是同一个值。 */
    @Test
    void theRetentionComesFromOnePlace() {
        assertThat(store.retention()).isEqualTo(Duration.ofDays(7));
    }

    private void scriptReturns(long value) {
        Mockito.lenient().when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), anyList(),
                any(Object.class), any(Object.class), any(Object.class))).thenReturn(value);
    }

    /** 补投脚本收到的三个参数：成员、分数、剩余寿命（毫秒）。 */
    private List<String> repairScriptArgs() {
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, Mockito.atLeastOnce()).execute(Mockito.<RedisScript<Long>>any(), anyList(),
                args.capture(), args.capture(), args.capture());
        return args.getAllValues();
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
