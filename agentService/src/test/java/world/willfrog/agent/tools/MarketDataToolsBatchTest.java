package world.willfrog.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataToolsBatchTest {

    @Mock
    private DatasetWriter datasetWriter;
    @Mock
    private DatasetRegistry datasetRegistry;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private MarketDataTools tools;
    private ObjectMapper objectMapper;
    private DomesticIndexService indexService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxParallelSearchQueries(5);
        parallel.setMaxParallelDailyQueries(5);
        runtime.setParallel(parallel);
        properties.setRuntime(runtime);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.of(properties));

        tools = new MarketDataTools(datasetWriter, datasetRegistry, localConfigLoader, properties, objectMapper);
        lenient().when(datasetWriter.isEnabled()).thenReturn(false);
        lenient().when(datasetRegistry.isEnabled()).thenReturn(false);

        DomesticStockService stockService = mock(DomesticStockService.class);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
        indexService = mock(DomesticIndexService.class);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);

        DomesticStockInfoSimpleItem searchItem = DomesticStockInfoSimpleItem.newBuilder()
                .setTsCode("000001.SZ")
                .setName("平安银行")
                .setIndustry("银行")
                .build();
        DomesticStockSearchResponse searchResponse = DomesticStockSearchResponse.newBuilder()
                .addItems(searchItem)
                .build();

        DomesticStockDailyItem dailyItem = DomesticStockDailyItem.newBuilder()
                .setTsCode("000001.SZ")
                .setTradeDate(20240102L)
                .setOpen(10.0)
                .setHigh(10.5)
                .setLow(9.8)
                .setClose(10.2)
                .setPreClose(10.0)
                .setChange(0.2)
                .setPctChg(2.0)
                .setVol(1000.0)
                .setAmount(10000.0)
                .build();
        DomesticStockDailyByTsCodeAndDateRangeResponse dailyResponse = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder()
                .addItems(dailyItem)
                .build();

        lenient().when(stockService.searchStock(any())).thenReturn(searchResponse);
        lenient().when(stockService.getStockDailyByTsCodeAndDateRange(any())).thenReturn(dailyResponse);
        lenient().when(indexService.getTradingDaysCountByDateRange(any())).thenReturn(DomesticTradingDaysCountResponse.newBuilder()
                .setExchange("SSE")
                .setStartDate(toTimestamp("20240101"))
                .setEndDate(toTimestamp("20240105"))
                .setTradingDaysCount(3)
                .setFirstTradingDate(toTimestamp("20240102"))
                .setLastTradingDate(toTimestamp("20240104"))
                .build());
        lenient().when(indexService.isTradingDay(any())).thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                .setExchange("SSE")
                .setDate(toTimestamp("20240102"))
                .setCalendarRecordFound(true)
                .setTradingDay(true)
                .build());
    }

    @Test
    void searchStock_shouldSupportBatchKeyword() throws Exception {
        String response = tools.searchStock("平安|万科");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((List<?>) data.get("results")).size());
    }

    @Test
    void getStockDaily_shouldSupportBatchTsCode() throws Exception {
        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertNotNull(data);
        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((List<?>) data.get("results")).size());
    }

    @Test
    void checkParallelLimits_shouldExposeHotLoadedLimits() throws Exception {
        String response = tools.checkParallelLimits();
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");
        Map<?, ?> search = (Map<?, ?>) data.get("search");
        Map<?, ?> daily = (Map<?, ?>) data.get("daily");
        Map<?, ?> calendar = (Map<?, ?>) data.get("calendar");

        assertEquals(5, search.get("maxItems"));
        assertEquals(5, daily.get("maxItems"));
        assertEquals(50, calendar.get("maxItems"));
        assertTrue(((List<?>) search.get("tools")).contains("searchAssetInfo"));
        assertTrue(((List<?>) daily.get("tools")).contains("getExchangeAssetDaily"));
        assertTrue(((List<?>) calendar.get("tools")).contains("isTradingDay"));
    }

    @Test
    void getTradingDaysSummary_shouldReturnCountAndBoundaryDates() throws Exception {
        String response = tools.getTradingDaysSummary("20240101", "20240105", "");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("SSE", data.get("exchange"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240105", data.get("end_date"));
        assertEquals(3, data.get("trading_days_count"));
        assertEquals("20240102", data.get("first_trading_date"));
        assertEquals("20240104", data.get("last_trading_date"));
    }

    @Test
    void getTradingDaysSummary_shouldRejectInvalidDateRange() throws Exception {
        String response = tools.getTradingDaysSummary("20240105", "20240101", "SSE");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> error = (Map<?, ?>) root.get("error");

        assertEquals(false, root.get("ok"));
        assertEquals("INVALID_ARGUMENT", error.get("code"));
    }

    @Test
    void isTradingDay_shouldReturnStatusAndCalendarRecordFlag() throws Exception {
        String response = tools.isTradingDay("20240102", null);
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("SSE", data.get("exchange"));
        assertEquals("20240102", data.get("date"));
        assertEquals(true, data.get("is_trading_day"));
        assertEquals(true, data.get("calendar_record_found"));
    }

    @Test
    void isTradingDay_shouldExposeCalendarDataGap() throws Exception {
        when(indexService.isTradingDay(any())).thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                .setExchange("SSE")
                .setDate(toTimestamp("20260102"))
                .setCalendarRecordFound(false)
                .setTradingDay(false)
                .build());

        String response = tools.isTradingDay("20260102", "SSE");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals(false, data.get("is_trading_day"));
        assertEquals(false, data.get("calendar_record_found"));
    }

    @Test
    void isTradingDay_shouldSupportBatchDates() throws Exception {
        String response = tools.isTradingDay("20240102|20240103", "SSE");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("batch", data.get("mode"));
        assertEquals(List.of("20240102", "20240103"), data.get("dates"));
        assertEquals(2, ((List<?>) data.get("results")).size());
        assertEquals(2, data.get("success_count"));
        assertEquals(0, data.get("failure_count"));
    }

    @Test
    void isTradingDay_shouldRejectBatchAboveHotLoadedLimit() throws Exception {
        AgentLlmProperties limited = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxParallelCalendarQueries(1);
        runtime.setParallel(parallel);
        limited.setRuntime(runtime);
        when(localConfigLoader.current()).thenReturn(Optional.of(limited));

        String response = tools.isTradingDay("20240102|20240103", "SSE");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> error = (Map<?, ?>) root.get("error");

        assertEquals(false, root.get("ok"));
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
    }

    @Test
    void searchStock_shouldRejectBatchAboveHotLoadedLimit() throws Exception {
        AgentLlmProperties limited = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxParallelSearchQueries(1);
        runtime.setParallel(parallel);
        limited.setRuntime(runtime);
        when(localConfigLoader.current()).thenReturn(Optional.of(limited));

        String response = tools.searchStock("平安|万科");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> error = (Map<?, ?>) root.get("error");

        assertEquals(false, root.get("ok"));
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
    }

    private long toTimestamp(String date) {
        return DateConvertUtils.convertDateStrToLong(date, "yyyyMMdd");
    }
}
