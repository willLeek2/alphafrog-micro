package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D07 工具结果缓存 scope 契约测试。
 *
 * <p>验证 {@link ToolResultCacheService} 的 scope 解析与 fail-closed 行为：</p>
 * <ul>
 *   <li>REDIS 模式工具在 user: 或 run: scope 下正常走 Redis 读写；</li>
 *   <li>blank scope 时降级为 NONE，不触碰 Redis，直接回源；</li>
 *   <li>缓存键中绝不含 {@code global} 字样；</li>
 *   <li>不同 run scope 产生不同缓存键，避免跨 run 串线。</li>
 * </ul>
 */
class ToolResultCacheScopeContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ToolOutputCompactionService compactionService;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private ToolResultCacheService service;
    private SimpleMeterRegistry meterRegistry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        AgentContext.clear();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        compactionService = mock(ToolOutputCompactionService.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());

        meterRegistry = new SimpleMeterRegistry();
        service = new ToolResultCacheService(
                redisTemplate,
                objectMapper,
                localConfigLoader,
                compactionService,
                meterRegistry
        );
        service.init();
        ReflectionTestUtils.setField(service, "defaultVersion", "v1");
        ReflectionTestUtils.setField(service, "defaultSearchTtlSeconds", 3600);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void redisMode_withUserScope_attemptsRedisReadAndWrite() {
        String scope = "user:u1";
        String result = executeWithScope(scope);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        verify(valueOperations).set(keyCaptor.capture(), anyString(), eq(3600L), eq(TimeUnit.SECONDS));

        Set<String> capturedKeys = new HashSet<>(keyCaptor.getAllValues());
        assertEquals(1, capturedKeys.size(), "读/写应使用同一缓存键");
        String key = capturedKeys.iterator().next();
        assertTrue(key.contains(":user:u1"), "键中应包含 user scope");
        assertFalse(key.contains("global"), "D07 口径：键中不应出现 global");
        assertTrue(result.contains("\"ok\":true"), "应返回 loader 结果");
    }

    @Test
    void redisMode_withRunScope_attemptsRedisReadAndWrite() {
        String scope = "run:r1";
        executeWithScope(scope);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        String key = keyCaptor.getValue();
        assertTrue(key.contains(":run:r1"), "键中应包含 run scope");
        assertFalse(key.contains("global"), "D07 口径：键中不应出现 global");
    }

    @Test
    void redisMode_withBlankScope_neverTouchesRedisAndExecutesLoaderDirectly() {
        String scope = "";
        java.util.concurrent.atomic.AtomicBoolean loaderCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        Supplier<ToolResultCacheService.ToolExecutionOutcome> loader = () -> {
            loaderCalled.set(true);
            return ToolResultCacheService.ToolExecutionOutcome.builder()
                    .result("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{}}")
                    .success(true)
                    .durationMs(5L)
                    .build();
        };

        stubCompact("{\"ok\":true,\"tool\":\"searchStock\",\"data\":{}}");

        ToolResultCacheService.CachedToolCallResult result = service.executeWithCache(
                "searchStock", Map.of("keyword", "abc"), scope, loader);

        assertTrue(loaderCalled.get(), "blank scope 时应直接执行 loader");
        assertTrue(result.isSuccess());
        assertNotNull(result.getCacheMeta());
        assertFalse(result.getCacheMeta().isEligible());
        assertEquals("none", result.getCacheMeta().getSource());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void differentRunScopes_sameParams_produceDifferentCacheKeys() {
        String key1 = captureKeyForScope("run:r1");
        String key2 = captureKeyForScope("run:r2");
        assertNotEquals(key1, key2, "不同 run scope 不应共享缓存键");
        assertFalse(key1.contains("global"));
        assertFalse(key2.contains("global"));
    }

    // ── helpers ──

    private String captureKeyForScope(String scope) {
        reset(valueOperations, redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        executeWithScope(scope);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        return keyCaptor.getValue();
    }

    private String executeWithScope(String scope) {
        String resultJson = "{\"ok\":true,\"tool\":\"searchStock\",\"data\":{}}";
        stubCompact(resultJson);
        Supplier<ToolResultCacheService.ToolExecutionOutcome> loader = () ->
                ToolResultCacheService.ToolExecutionOutcome.builder()
                        .result(resultJson)
                        .success(true)
                        .durationMs(3L)
                        .build();

        ToolResultCacheService.CachedToolCallResult result = service.executeWithCache(
                "searchStock", Map.of("keyword", "abc"), scope, loader);
        return result.getResult();
    }

    private void stubCompact(String resultJson) {
        when(compactionService.compact(eq("searchStock"), eq(resultJson), anyString()))
                .thenReturn(ToolOutputCompactionService.CompactionResult.builder()
                        .modelOutput(resultJson)
                        .cacheTemplate(resultJson)
                        .observabilityOutput(resultJson)
                        .compactionApplied(false)
                        .build());
    }
}
