package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentRunStateStoreTest {

    private AgentRunStateStore store;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        store = new AgentRunStateStore(redisTemplate, new ObjectMapper());
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        // @Value 字段在直接 new 时不会注入，需手动设 TTL，否则 touch() 会因 ttl<=0 直接返回
        org.springframework.test.util.ReflectionTestUtils.setField(store, "ttlSeconds", 7200L);
        org.springframework.test.util.ReflectionTestUtils.setField(store, "callDetailTtlSeconds", 21600L);
        org.springframework.test.util.ReflectionTestUtils.setField(store, "callRawContentTtlSeconds", 7200L);
    }

    @Test
    void saveObservability_whenSetThrows_shouldRethrowAndNotTouch() {
        String runId = "run-test-1";
        String json = "{}";

        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).set(anyString(), eq(json));

        RedisConnectionFailureException thrown = assertThrows(
                RedisConnectionFailureException.class,
                () -> store.saveObservability(runId, json)
        );

        assertTrue(thrown.getMessage().contains("connection refused"));
        // SET 失败时不能调用 expire
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void saveObservability_whenSetSucceeds_shouldTouchKey() {
        String runId = "run-test-2";
        String json = "{\"test\":true}";

        store.saveObservability(runId, json);

        verify(valueOperations).set(anyString(), eq(json));
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void saveLlmCallRawContent_shouldUseRawTtlAndLongerMarkerTtl() {
        store.saveLlmCallRawContent("run-1", "llm-1", "{\"httpRequest\":{}}");

        verify(valueOperations).set(eq("agent:run:run-1:detail:llm:llm-1:raw"),
                eq("{\"httpRequest\":{}}"), eq(Duration.ofSeconds(7200)));
        verify(valueOperations).set(eq("agent:run:run-1:detail:llm:llm-1:raw:meta"),
                anyString(), eq(Duration.ofSeconds(21600)));
    }

    @Test
    void loadObservability_whenGetThrows_shouldReturnEmpty() {
        String runId = "run-test-3";

        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).get(anyString());

        Optional<String> result = store.loadObservability(runId);

        assertTrue(result.isEmpty());
    }
}
