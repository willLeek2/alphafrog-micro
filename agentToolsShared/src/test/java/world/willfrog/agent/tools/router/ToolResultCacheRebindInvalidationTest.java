package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 260814 scheduler-03 review fix：压缩缓存命中后的 rawRef 重绑定必须保持 Run
 * 隔离。rebind 只允许读当前 AgentContext 拥有的 ref；当缓存里的 locator 不属于
 * 当前 Run（跨 Run 命中）或来源 Run 已终态清理时，缓存视为失效——删除缓存键并
 * 回源真实工具调用，绝不跨 Run 复制 raw 内容，也不让异常中断工具调用链。
 */
class ToolResultCacheRebindInvalidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ToolOutputCompactionService compactionService;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private ToolResultCacheService service;

    @BeforeEach
    void setUp() {
        AgentContext.clear();
        AgentContext.setRunId("run-r1");
        AgentContext.setUserId("user-1");

        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        compactionService = mock(ToolOutputCompactionService.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());

        service = new ToolResultCacheService(
                redisTemplate, objectMapper, localConfigLoader, compactionService,
                new SimpleMeterRegistry());
        service.init();
        ReflectionTestUtils.setField(service, "defaultVersion", "v1");
        ReflectionTestUtils.setField(service, "defaultSearchTtlSeconds", 3600);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private String cachePayloadJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "result", "{\"ok\":true,\"tool\":\"searchStock\",\"data\":{\"rawRef\":\"raw_old\"}}",
                "originalDurationMs", 100,
                "cachedAtMillis", System.currentTimeMillis(),
                "compactionApplied", true,
                "rawLocator", Map.of("path", "raw_old_ref")));
    }

    private Supplier<ToolResultCacheService.ToolExecutionOutcome> countingLoader(AtomicBoolean called) {
        return () -> {
            called.set(true);
            return ToolResultCacheService.ToolExecutionOutcome.builder()
                    .result("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{\"rawRef\":\"raw_fresh\"}}")
                    .success(true)
                    .durationMs(5L)
                    .build();
        };
    }

    @Test
    void rebindFailure_shouldInvalidateCacheAndInvokeLoader() throws Exception {
        String cached = cachePayloadJson();
        when(valueOperations.get(anyString())).thenReturn(cached);
        when(compactionService.rebindForCacheHit(anyString(), any(RawPayloadLocator.class)))
                .thenThrow(new IllegalArgumentException("rawRef not found"));
        // 回源路径的 compact 为 mock，需要返回合法的 CompactionResult
        when(compactionService.compact(anyString(), anyString(), anyString()))
                .thenReturn(ToolOutputCompactionService.CompactionResult.builder()
                        .modelOutput("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{\"rawRef\":\"raw_fresh\"}}")
                        .cacheTemplate("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{\"rawRef\":\"raw_fresh\"}}")
                        .rawLocator(null)
                        .compactionApplied(false)
                        .observabilityOutput("fresh")
                        .build());
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        ToolResultCacheService.CachedToolCallResult outcome = service.executeWithCache(
                "searchStock", Map.of("keyword", "x"), "user:u1", countingLoader(loaderCalled));

        // 缓存键被删除（视为失效），loader 被调用，结果来自回源
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).delete(keyCaptor.capture());
        verify(valueOperations).get(keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1),
                "删除的键必须就是读取的键");
        assertTrue(loaderCalled.get(), "缓存失效后必须回源真实工具调用");
        assertTrue(outcome.getResult().contains("raw_fresh"), "返回内容必须来自回源");
        assertFalse(outcome.getCacheMeta().isHit(), "失效回源必须按 miss 记账");
        assertEquals("redis_tool_cache", outcome.getCacheMeta().getSource());
    }

    @Test
    void rebindSuccess_shouldReturnReboundResultWithoutLoader() throws Exception {
        String cached = cachePayloadJson();
        when(valueOperations.get(anyString())).thenReturn(cached);
        when(compactionService.rebindForCacheHit(anyString(), any(RawPayloadLocator.class)))
                .thenReturn("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{\"rawRef\":\"raw_new\"}}");
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        ToolResultCacheService.CachedToolCallResult outcome = service.executeWithCache(
                "searchStock", Map.of("keyword", "x"), "user:u1", countingLoader(loaderCalled));

        assertTrue(outcome.getResult().contains("raw_new"), "必须返回 rebind 后的结果");
        assertTrue(outcome.getCacheMeta().isHit(), "成功 rebind 仍是 cache hit");
        assertFalse(loaderCalled.get(), "rebind 成功时绝不回源");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void hitWithoutLocator_shouldNotTouchRebind() throws Exception {
        // 无压缩/无 locator 的普通缓存命中不经过 rebind，行为保持不变
        String plain = objectMapper.writeValueAsString(Map.of(
                "result", "{\"ok\":true,\"tool\":\"searchStock\",\"data\":{}}",
                "originalDurationMs", 100,
                "cachedAtMillis", System.currentTimeMillis(),
                "compactionApplied", false,
                "rawLocator", Map.of()));
        when(valueOperations.get(anyString())).thenReturn(plain);
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        ToolResultCacheService.CachedToolCallResult outcome = service.executeWithCache(
                "searchStock", Map.of("keyword", "x"), "user:u1", countingLoader(loaderCalled));

        assertTrue(outcome.getCacheMeta().isHit());
        assertFalse(loaderCalled.get());
        verify(compactionService, never()).rebindForCacheHit(anyString(), any(RawPayloadLocator.class));
        verify(redisTemplate, never()).delete(anyString());
    }
}
