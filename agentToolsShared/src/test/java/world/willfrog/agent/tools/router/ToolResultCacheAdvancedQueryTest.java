package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchDatasetWriter;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.DatasetPersistedEvent;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchException;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolResultCacheAdvancedQueryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> redisValues = new HashMap<>();
    private StringRedisTemplate redis;
    private ToolResultCacheService service;
    private SimpleMeterRegistry meterRegistry;

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> redisValues.get(call.getArgument(0)));
        doAnswer(call -> {
            redisValues.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        AgentLlmLocalConfigLoader config = mock(AgentLlmLocalConfigLoader.class);
        when(config.current()).thenReturn(Optional.empty());
        ToolOutputCompactionService compaction = mock(ToolOutputCompactionService.class);
        when(compaction.compact(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String output = call.getArgument(1);
            return ToolOutputCompactionService.CompactionResult.builder()
                    .modelOutput(output).cacheTemplate(output).observabilityOutput(output)
                    .compactionApplied(false).build();
        });
        meterRegistry = new SimpleMeterRegistry();
        service = new ToolResultCacheService(redis, mapper, config, compaction, meterRegistry);
        service.init();
        ReflectionTestUtils.setField(service, "defaultVersion", "v1");
        ReflectionTestUtils.setField(service, "defaultSearchTtlSeconds", 3600);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        meterRegistry.close();
    }










    private ToolResultCacheService.CachedToolCallResult execute(String tool, Map<String, Object> params,
                                                               String label, boolean success, AtomicInteger calls) {
        return service.executeWithCache(tool, params, "run:business", () -> {
            calls.incrementAndGet();
            String output = "{\"ok\":" + success + ",\"tool\":\"" + tool + "\",\"data\":{\"label\":\"" + label + "\"}}";
            return ToolResultCacheService.ToolExecutionOutcome.builder().result(output).success(success).durationMs(3).build();
        });
    }

    private String key(String tool, Map<String, Object> params) {
        return ReflectionTestUtils.invokeMethod(service, "buildCacheKey", tool, params, "run:business");
    }

    private Map<String, Object> indexQuery(String index, String start, String end) {
        return new LinkedHashMap<>(Map.of("asset_type", "stock", "conditions", List.of(
                Map.of("type", "index_component", "index_code", index, "start_date", start, "end_date", end))));
    }

    private Map<String, Object> withWeight(String field, double weight) {
        Map<String, Object> query = indexQuery("000300.SH", "20230101", "20231231");
        Map<String, Object> condition = new LinkedHashMap<>(firstCondition(query));
        condition.put(field, weight);
        query.put("conditions", List.of(condition));
        return query;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCondition(Map<String, Object> query) {
        return ((List<Map<String, Object>>) query.get("conditions")).get(0);
    }

    private Map<String, Object> advanced(Object query) {
        return Map.of("mode", "advanced", "advancedQuery", query);
    }

    private Map<String, Object> daily(Object query) {
        Map<String, Object> params = new LinkedHashMap<>(advanced(query));
        params.put("assetType", "stock");
        params.put("startDate", "20230101");
        params.put("endDate", "20231231");
        return params;
    }
}
