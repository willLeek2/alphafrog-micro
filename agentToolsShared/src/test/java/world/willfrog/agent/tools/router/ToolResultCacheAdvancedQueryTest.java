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

    @Test
    void historicalConstituentQueries_doNotReuseAnotherPeriodIndexOrFilter() {
        List<Map<String, Object>> queries = new ArrayList<>();
        queries.add(indexQuery("000300.SH", "20230101", "20231231"));
        queries.add(indexQuery("000300.SH", "20240101", "20241231"));
        queries.add(indexQuery("000300.SH", "NONE", "NONE"));
        queries.add(indexQuery("000300.SH", "20231201", "20231231"));
        queries.add(indexQuery("000905.SH", "20230101", "20231231"));
        Map<String, Object> named = indexQuery("000300.SH", "20230101", "20231231");
        named.put("name", "平安银行");
        queries.add(named);
        queries.add(withWeight("min_weight", 3.0));
        queries.add(withWeight("max_weight", 8.0));
        Map<String, Object> intersection = indexQuery("000300.SH", "20230101", "20231231");
        intersection.put("conditions", List.of(firstCondition(intersection),
                Map.of("type", "sw_industry_l3_component", "industry_code", "430101")));
        queries.add(intersection);

        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < queries.size(); i++) {
            var result = execute("searchAssetInfo", advanced(queries.get(i)), "variant-" + i, true, calls);
            assertFalse(result.getCacheMeta().isHit(), "不同查询必须实际取数");
            assertTrue(result.getResult().contains("variant-" + i));
        }
        var repeated = execute("searchAssetInfo", advanced(queries.get(0)), "current", true, calls);
        assertEquals("dataset_registry", repeated.getCacheMeta().getSource());
        assertFalse(repeated.getCacheMeta().isHit());
        assertTrue(repeated.getResult().contains("current"));
        assertEquals(queries.size() + 1, calls.get());
    }

    @Test
    void hasStockQueries_doNotReuseAnotherStockOrPeriod() {
        AtomicInteger calls = new AtomicInteger();
        for (Map<String, Object> condition : List.of(
                Map.<String, Object>of("type", "has_stock", "stock_code", "000001.SZ", "start_date", "20230101", "end_date", "20231231"),
                Map.<String, Object>of("type", "has_stock", "stock_code", "000001.SZ", "start_date", "20240101", "end_date", "20241231"),
                Map.<String, Object>of("type", "has_stock", "stock_code", "600519.SH", "start_date", "20230101", "end_date", "20231231"))) {
            var result = execute("searchIndex", advanced(Map.of("conditions", List.of(condition))), "index", true, calls);
            assertFalse(result.getCacheMeta().isHit());
        }
        assertEquals(3, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchAssetInfo", "searchIndex"})
    void simpleSearchStillReusesRedisResult(String tool) {
        AtomicInteger calls = new AtomicInteger();
        var first = execute(tool, Map.of("keyword", "沪深300"), "first", true, calls);
        var second = execute(tool, Map.of("keyword", "沪深300", "mode", "simple"), "unused", true, calls);
        assertEquals(1, calls.get());
        assertTrue(second.getCacheMeta().isHit());
        assertEquals("redis_tool_cache", second.getCacheMeta().getSource());
        assertEquals(first.getResult(), second.getResult());
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchAssetInfo", "searchIndex"})
    void objectAndJsonString_withDifferentFieldOrder_shareIdentityButExecuteLoader(String tool) throws Exception {
        Map<String, Object> query = tool.equals("searchIndex")
                ? Map.of("conditions", List.of(Map.of("type", "has_stock", "stock_code", "000001.SZ")))
                : indexQuery("000300.SH", "20230101", "20231231");
        Map<String, Object> reordered = new LinkedHashMap<>();
        Map<String, Object> condition = new LinkedHashMap<>();
        firstCondition(query).entrySet().stream().sorted(Map.Entry.<String, Object>comparingByKey().reversed())
                .forEach(entry -> condition.put(entry.getKey(), entry.getValue()));
        reordered.put("conditions", List.of(condition));
        if (query.containsKey("asset_type")) reordered.put("asset_type", query.get("asset_type"));
        AtomicInteger calls = new AtomicInteger();
        var first = execute(tool, advanced(query), "correct", true, calls);
        var second = execute(tool, Map.of("mode", " ADVANCED ", "advancedQuery", mapper.writeValueAsString(reordered)), "current", true, calls);
        assertFalse(second.getCacheMeta().isHit());
        assertEquals(first.getCacheMeta().getKey(), second.getCacheMeta().getKey());
        assertTrue(second.getResult().contains("current"));
        assertEquals(2, calls.get());
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchAssetInfo", "searchIndex", "getExchangeAssetDaily"})
    void advancedAndSimple_doNotShareCacheIdentity_andSimpleKeysRemainUnchanged(String tool) {
        Map<String, Object> simple = tool.equals("getExchangeAssetDaily")
                ? Map.of("tsCode", "000001.SZ", "assetType", "stock", "startDate", "20230101", "endDate", "20231231")
                : Map.of("keyword", "沪深300");
        Map<String, Object> explicitSimple = new LinkedHashMap<>(simple);
        explicitSimple.put("mode", "simple");
        String oldKey = key(tool, simple);
        assertEquals(oldKey, key(tool, explicitSimple));
        Map<String, String> expected = tool.equals("getExchangeAssetDaily")
                ? Map.of("tsCode", "000001.SZ", "assetType", "stock", "startDateStr", "20230101", "endDateStr", "20231231", "priceMode", "raw_ohlc")
                : tool.equals("searchAssetInfo") ? Map.of("keyword", "沪深300", "marketScope", "domestic") : Map.of("keyword", "沪深300");
        assertEquals(expected, ReflectionTestUtils.invokeMethod(service, "normalizeArgs", tool, simple));
        Map<String, Object> advanced = new LinkedHashMap<>(simple);
        advanced.putAll(advanced(indexQuery("000300.SH", "20230101", "20231231")));
        assertNotEquals(oldKey, key(tool, advanced));
    }

    @Test
    void topLevelNameFallback_andConditionOrder_remainPartOfAdvancedIdentity() {
        Map<String, Object> query = indexQuery("000300.SH", "20230101", "20231231");
        Map<String, Object> first = new LinkedHashMap<>(advanced(query));
        first.put("name", "银行");
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("name", "保险");
        assertNotEquals(key("searchAssetInfo", first), key("searchAssetInfo", second));
        Map<String, Object> extra = Map.of("type", "sw_industry_l3_component", "industry_code", "430101");
        assertNotEquals(key("searchAssetInfo", advanced(Map.of("conditions", List.of(firstCondition(query), extra)))),
                key("searchAssetInfo", advanced(Map.of("conditions", List.of(extra, firstCondition(query))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchIndex", "searchAssetInfo", "getExchangeAssetDaily"})
    void advancedTools_registryPathStillExecutesLoader_withoutRedisReuse(String tool) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> firstParams = daily(indexQuery("000300.SH", "20230101", "20231231"));
        Map<String, Object> secondParams = daily(indexQuery("000905.SH", "20230101", "20231231"));
        var first = execute(tool, firstParams, "csi300", true, calls);
        var second = execute(tool, secondParams, "csi500", true, calls);
        assertEquals("dataset_registry", second.getCacheMeta().getSource());
        assertNotEquals(first.getCacheMeta().getKey(), second.getCacheMeta().getKey());
        Map<String, Object> encoded = new LinkedHashMap<>(firstParams);
        encoded.put("advancedQuery", mapper.writeValueAsString(firstParams.get("advancedQuery")));
        assertEquals(first.getCacheMeta().getKey(), key(tool, encoded));
        assertEquals(2, calls.get());
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "\"stock\"", "null"})
    void invalidAdvancedQuery_isLeftToToolValidation_andFailureIsNotCached(String query) {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> params = advanced(query);
        Supplier<ToolResultCacheService.ToolExecutionOutcome> loader = () -> {
            calls.incrementAndGet();
            AdvancedSearchException error = assertThrows(AdvancedSearchException.class,
                    () -> AdvancedSearchRequest.from("searchAssetInfo", params, mapper));
            assertEquals("INVALID_ARGUMENT", error.getCode());
            return ToolResultCacheService.ToolExecutionOutcome.builder().success(false)
                    .result("{\"ok\":false,\"tool\":\"searchAssetInfo\",\"data\":{},\"error\":{\"code\":\"INVALID_ARGUMENT\"}}")
                    .durationMs(3).build();
        };
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = service.executeWithCache("searchAssetInfo", params, "run:business", loader);
            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("INVALID_ARGUMENT"));
        }
        assertEquals(2, calls.get());
        assertTrue(redisValues.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchAssetInfo", "searchIndex"})
    @SuppressWarnings("unchecked")
    void sameUserAcrossRuns_reusesActualFileAndRegistersItInEachRun(String tool) throws Exception {
        // Redis 仅替代存储连接，写盘、复用校验和运行目录事件均执行真实实现。
        ValueOperations<String, String> values = redis.opsForValue();
        doAnswer(call -> {
            redisValues.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString());
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        AgentRunDatasetRegistry runRegistry = new AgentRunDatasetRegistry();
        DatasetRegistry registry = new DatasetRegistry(redis, event -> {
            if (event instanceof DatasetPersistedEvent persisted) runRegistry.onDatasetPersisted(persisted);
        });
        ReflectionTestUtils.setField(registry, "enabled", true);
        ReflectionTestUtils.setField(registry, "databaseFetchedPath", tempDir.toString());
        ReflectionTestUtils.setField(registry, "ttlSeconds", 3600L);
        DatasetWriter datasetWriter = new DatasetWriter(new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(), tempDir.resolve("artifacts").toString(),
                tempDir.resolve("datasets").toString(), tempDir.resolve("obs.log").toString()));
        ReflectionTestUtils.setField(datasetWriter, "enabled", true);
        ReflectionTestUtils.setField(datasetWriter, "databaseFetchedPath", tempDir.toString());
        AdvancedSearchDatasetWriter advancedWriter = new AdvancedSearchDatasetWriter(datasetWriter, registry, mapper);
        Map<String, Object> query = indexQuery("000300.SH", "20230101", "20231231");
        Map<String, Object> dataset = Map.of("row_count", 1,
                "results", List.of(Map.of("ts_code", "000001.SZ", "weight", 1.25)));
        AtomicInteger calls = new AtomicInteger();
        Supplier<ToolResultCacheService.ToolExecutionOutcome> loader = () -> {
            calls.incrementAndGet();
            var written = advancedWriter.writeOrReuse(tool, "stock", query, dataset, 10);
            String response = mapper.valueToTree(Map.of("ok", true, "tool", tool, "data", Map.of(
                    "dataset_id", written.getDatasetId(), "cache_hit", written.isReused(), "row_count", 1)))
                    .toString();
            return ToolResultCacheService.ToolExecutionOutcome.builder().success(true).result(response).build();
        };
        AgentContext.setUserId("same-user");
        AgentContext.setRunId("run-first");
        var first = service.executeWithCache(tool, advanced(query), "user:same-user", loader);
        AgentContext.setRunId("run-second");
        var second = service.executeWithCache(tool, advanced(query), "user:same-user", loader);

        assertEquals(2, calls.get(), "第二个Run必须执行工具，不能只返回另一个Run的短缓存响应");
        assertFalse(first.getCacheMeta().isHit());
        assertTrue(second.getCacheMeta().isHit());
        assertEquals("dataset_registry", second.getCacheMeta().getSource());
        var firstEntries = runRegistry.snapshot("run-first").datasets();
        var secondEntries = runRegistry.snapshot("run-second").datasets();
        assertEquals(1, firstEntries.size());
        assertEquals(1, secondEntries.size());
        assertEquals(firstEntries.get(0).originalId(), secondEntries.get(0).originalId());
        assertEquals(firstEntries.get(0).persistedPath(), secondEntries.get(0).persistedPath());
        Path persisted = Path.of(secondEntries.get(0).persistedPath());
        assertTrue(Files.isRegularFile(persisted));
        assertEquals(mapper.valueToTree(dataset), mapper.readTree(persisted.toFile()));
        assertEquals(1, mapper.readTree(second.getResult()).path("data").path("row_count").asInt());
        assertTrue(redisValues.keySet().stream().noneMatch(key -> key.startsWith("agent:tool-cache:")));
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
