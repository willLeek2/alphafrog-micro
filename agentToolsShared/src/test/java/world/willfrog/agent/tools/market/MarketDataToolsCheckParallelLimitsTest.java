package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：checkParallelLimits 的四例钉住测试（task #109）。
 * 锁默认值、热加载优先、clamp 边界与 Spring 兜底；组内 tools 清单与文案逐字钉住。
 */
class MarketDataToolsCheckParallelLimitsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentLlmLocalConfigLoader localConfigLoader;
    private AgentLlmProperties llmProperties;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        llmProperties = new AgentLlmProperties();
        tools = new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                llmProperties, objectMapper);
    }

    private Map<String, Object> invoke() throws Exception {
        return objectMapper.readValue(tools.checkParallelLimits(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupOf(Map<String, Object> data, String group) {
        return (Map<String, Object>) data.get(group);
    }

    private static int maxItems(Map<String, Object> group) {
        return ((Number) group.get("maxItems")).intValue();
    }

    @Test
    void defaultsPinAllGroupsListsAndLiterals() throws Exception {
        Map<String, Object> response = invoke();

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("checkParallelLimits", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);

        Map<String, Object> search = groupOf(data, "search");
        assertEquals(3, maxItems(search));
        assertEquals(List.of("searchAssetInfo", "searchStock", "searchIndex", "searchFund",
                "getStockInfo", "getIndexInfo", "getStockSwIndustryInfo"), search.get("tools"));
        assertEquals("Use | separated values or JSON arrays. Do not use comma-separated values.",
                search.get("argumentFormat"));

        Map<String, Object> daily = groupOf(data, "daily");
        assertEquals(2, maxItems(daily));
        assertEquals(List.of("getExchangeAssetDaily", "getStockDaily", "getIndexDaily"), daily.get("tools"));
        assertEquals("Use | separated tsCode values or JSON arrays. Do not use comma-separated values.",
                daily.get("argumentFormat"));

        Map<String, Object> calendar = groupOf(data, "calendar");
        assertEquals(50, maxItems(calendar));
        assertEquals(List.of("isTradingDay"), calendar.get("tools"));
        assertEquals("Use | separated YYYYMMDD values or JSON arrays. Do not use comma-separated values.",
                calendar.get("argumentFormat"));

        Map<String, Object> advanced = groupOf(data, "advanced");
        assertEquals(3, maxItems(advanced));
        assertEquals(10, ((Number) advanced.get("previewRows")).intValue());
        assertEquals(List.of("searchIndex(mode=advanced)", "searchAssetInfo(mode=advanced)",
                "getExchangeAssetDaily(mode=advanced)"), advanced.get("tools"));
        assertEquals("conditions use | separated index_code/stock_code/industry_code values. "
                        + "Dates must be YYYYMMDD or NONE. "
                        + "getExchangeAssetDaily advanced only supports stock asset_type.",
                advanced.get("argumentFormat"));

        assertEquals("If checkParallelLimits is unavailable, assume batch/parallel querying is disabled "
                + "and call tools with one item at a time.", data.get("fallbackRule"));
        assertEquals("agent.llm.runtime.parallel from hot-loaded local config first, then application properties",
                data.get("source"));
    }

    @Test
    void hotLoadedConfigTakesPrecedenceOverSpring() throws Exception {
        // Spring 侧放不同值，断言热加载值胜出
        llmProperties.getRuntime().getParallel().setMaxParallelSearchQueries(9);
        llmProperties.getRuntime().getParallel().setMaxParallelDailyQueries(8);
        llmProperties.getRuntime().getParallel().setMaxParallelCalendarQueries(77);
        llmProperties.getRuntime().getParallel().setMaxParallelQueriesInAdvancedMode(6);
        llmProperties.getTools().getMarketData().getAdvanced().setPreviewRows(33);

        AgentLlmProperties hot = new AgentLlmProperties();
        hot.getRuntime().getParallel().setMaxParallelSearchQueries(7);
        hot.getRuntime().getParallel().setMaxParallelDailyQueries(5);
        hot.getRuntime().getParallel().setMaxParallelCalendarQueries(60);
        hot.getRuntime().getParallel().setMaxParallelQueriesInAdvancedMode(4);
        hot.getTools().getMarketData().getAdvanced().setPreviewRows(15);
        when(localConfigLoader.current()).thenReturn(Optional.of(hot));

        Map<String, Object> data = dataOf(invoke());

        assertEquals(7, maxItems(groupOf(data, "search")));
        assertEquals(5, maxItems(groupOf(data, "daily")));
        assertEquals(60, maxItems(groupOf(data, "calendar")));
        Map<String, Object> advanced = groupOf(data, "advanced");
        assertEquals(4, maxItems(advanced));
        assertEquals(15, ((Number) advanced.get("previewRows")).intValue());
    }

    @Test
    void clampBoundariesAreEnforced() throws Exception {
        // 超上限 → 钳到组内最大值
        AgentLlmProperties over = new AgentLlmProperties();
        over.getRuntime().getParallel().setMaxParallelSearchQueries(21);
        over.getRuntime().getParallel().setMaxParallelCalendarQueries(101);
        over.getTools().getMarketData().getAdvanced().setPreviewRows(101);
        when(localConfigLoader.current()).thenReturn(Optional.of(over));

        Map<String, Object> data = dataOf(invoke());
        assertEquals(20, maxItems(groupOf(data, "search")), "search 钳到 20");
        assertEquals(100, maxItems(groupOf(data, "calendar")), "calendar 钳到 100");
        assertEquals(100, ((Number) groupOf(data, "advanced").get("previewRows")).intValue(),
                "previewRows 钳到 100");

        // 0 / 负数在 search/daily/calendar 解析器里视为未配置 → 落到默认值（Spring 也未配置时）
        AgentLlmProperties zero = new AgentLlmProperties();
        zero.getRuntime().getParallel().setMaxParallelSearchQueries(0);
        zero.getRuntime().getParallel().setMaxParallelDailyQueries(-5);
        zero.getRuntime().getParallel().setMaxParallelCalendarQueries(0);
        when(localConfigLoader.current()).thenReturn(Optional.of(zero));

        Map<String, Object> data2 = dataOf(invoke());
        assertEquals(3, maxItems(groupOf(data2, "search")), "search 配置 0 回退默认 3");
        assertEquals(2, maxItems(groupOf(data2, "daily")), "daily 配置负数回退默认 2");
        assertEquals(50, maxItems(groupOf(data2, "calendar")), "calendar 配置 0 回退默认 50");
    }

    @Test
    void springConfigUsedWhenHotLoadAbsent() throws Exception {
        llmProperties.getRuntime().getParallel().setMaxParallelSearchQueries(8);
        llmProperties.getRuntime().getParallel().setMaxParallelDailyQueries(6);
        llmProperties.getRuntime().getParallel().setMaxParallelCalendarQueries(70);
        llmProperties.getRuntime().getParallel().setMaxParallelQueriesInAdvancedMode(5);
        llmProperties.getTools().getMarketData().getAdvanced().setPreviewRows(25);

        Map<String, Object> data = dataOf(invoke());

        assertEquals(8, maxItems(groupOf(data, "search")));
        assertEquals(6, maxItems(groupOf(data, "daily")));
        assertEquals(70, maxItems(groupOf(data, "calendar")));
        Map<String, Object> advanced = groupOf(data, "advanced");
        assertEquals(5, maxItems(advanced));
        assertEquals(25, ((Number) advanced.get("previewRows")).intValue());
    }
}
