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
