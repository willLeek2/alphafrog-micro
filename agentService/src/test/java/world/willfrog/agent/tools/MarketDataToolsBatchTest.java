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
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoFullItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByConCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataToolsBatchTest {

    @Mock
    private DatasetWriter datasetWriter;
    @Mock
    private DatasetRegistry datasetRegistry;
    @Mock
    private ManifestWriter manifestWriter;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private MarketDataTools tools;
    private ObjectMapper objectMapper;
    private DomesticIndexService indexService;
    private DomesticStockService stockService;
    private DomesticListedAssetService listedAssetService;

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

        tools = new MarketDataTools(datasetWriter, datasetRegistry, manifestWriter, localConfigLoader, properties, objectMapper);
        lenient().when(datasetWriter.isEnabled()).thenReturn(false);
        lenient().when(datasetRegistry.isEnabled()).thenReturn(false);
        lenient().when(manifestWriter.isEnabled()).thenReturn(false);
        ReflectionTestUtils.setField(tools, "emitManifest", false);

        stockService = mock(DomesticStockService.class);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
        indexService = mock(DomesticIndexService.class);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
        listedAssetService = mock(DomesticListedAssetService.class);
        ReflectionTestUtils.setField(tools, "domesticListedAssetService", listedAssetService);

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
        lenient().when(listedAssetService.searchListedAssets(any())).thenReturn(ListedAssetSearchResponse.newBuilder()
                .addItems(ListedAssetInfoItem.newBuilder()
                        .setAssetType("stock")
                        .setTsCode("000001.SZ")
                        .setName("平安银行")
                        .build())
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

    @Test
    void getStockDaily_batch_shouldNotEmitManifestWhenFlagOff() throws Exception {
        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("batch", data.get("mode"));
        assertTrue(data.containsKey("dataset_ids"));
        assertTrue(!data.containsKey("dataset_id") || "".equals(data.get("dataset_id")));
        verify(manifestWriter, never()).writeManifest(anyString(), anyString(), anyString(), anyList(), anyInt(), anyList());
    }

    @Test
    void getStockDaily_batch_shouldEmitManifestWhenFlagOn() throws Exception {
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.findReusable(eq("stock_daily"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty());
        when(datasetWriter.writeDataset(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn("atomic-ds-1", "atomic-ds-2");
        when(datasetRegistry.findReusableManifest(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Optional.empty());
        when(manifestWriter.writeManifest(
                eq("stock_daily"),
                eq("20240101"),
                eq("20240131"),
                anyList(),
                anyInt(),
                anyList()
        )).thenReturn("manifest-stock_daily-20240101-20240131-deadbeef");

        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("manifest-stock_daily-20240101-20240131-deadbeef", data.get("dataset_id"));
        assertEquals("manifest-stock_daily-20240101-20240131-deadbeef", data.get("manifest_id"));
        assertNotNull(data.get("manifest"));
        verify(datasetRegistry).registerManifest(
                eq("stock_daily"),
                eq("20240101"),
                eq("20240131"),
                anyList(),
                anyList(),
                eq("manifest-stock_daily-20240101-20240131-deadbeef"),
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void resolveEmitManifest_shouldUseNacosFlagOnOverSpringFlagOff() {
        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfig(null, true)));
        ReflectionTestUtils.setField(tools, "emitManifest", false);

        Boolean effective = ReflectionTestUtils.invokeMethod(tools, "resolveEmitManifest");

        assertEquals(Boolean.TRUE, effective);
    }

    @Test
    void resolveEmitManifest_shouldUseNacosFlagOffOverSpringFlagOn() {
        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfig(null, false)));
        ReflectionTestUtils.setField(tools, "emitManifest", true);

        Boolean effective = ReflectionTestUtils.invokeMethod(tools, "resolveEmitManifest");

        assertEquals(Boolean.FALSE, effective);
    }

    @Test
    void getStockDaily_batch_shouldFallbackWhenManifestWriteFails() throws Exception {
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.findReusable(eq("stock_daily"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty());
        when(datasetWriter.writeDataset(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn("atomic-ds-1", "atomic-ds-2");
        when(datasetRegistry.findReusableManifest(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Optional.empty());
        when(manifestWriter.writeManifest(anyString(), anyString(), anyString(), anyList(), anyInt(), anyList()))
                .thenThrow(new RuntimeException("disk full"));

        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("batch", data.get("mode"));
        assertTrue(!data.containsKey("dataset_id") || data.get("dataset_id") == null);
    }

    @Test
    void getStockDaily_batch_shouldReuseExistingManifestWithoutRewrite() throws Exception {
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.findReusable(eq("stock_daily"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty());
        when(datasetWriter.writeDataset(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn("atomic-ds-1", "atomic-ds-2");
        DatasetRegistry.ManifestMeta reused = DatasetRegistry.ManifestMeta.builder()
                .manifestId("manifest-stock_daily-20240101-20240131-reused")
                .build();
        when(datasetRegistry.findReusableManifest(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Optional.of(reused));

        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("manifest-stock_daily-20240101-20240131-reused", data.get("dataset_id"));
        assertEquals("manifest-stock_daily-20240101-20240131-reused", data.get("manifest_id"));
        verify(manifestWriter, never()).writeManifest(anyString(), anyString(), anyString(), anyList(), anyInt(), anyList());
        verify(datasetRegistry, never()).registerManifest(anyString(), anyString(), anyString(), anyList(), anyList(),
                anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void getStockDaily_batch_partialFailure_shouldEmitManifestWhenReadyMembersExist() throws Exception {
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.findReusable(eq("stock_daily"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty());
        when(datasetWriter.writeDataset(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn("atomic-ds-1");
        when(datasetRegistry.findReusableManifest(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Optional.empty());
        when(manifestWriter.writeManifest(anyString(), anyString(), anyString(), anyList(), anyInt(), anyList()))
                .thenReturn("manifest-stock_daily-20240101-20240131-partial");

        DomesticStockDailyByTsCodeAndDateRangeResponse successResponse = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder()
                .addItems(DomesticStockDailyItem.newBuilder()
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
                        .build())
                .build();
        when(stockService.getStockDailyByTsCodeAndDateRange(any())).thenAnswer(invocation -> {
            DomesticStockDailyByTsCodeAndDateRangeRequest request = invocation.getArgument(0);
            if ("000002.SZ".equals(request.getTsCode())) {
                return DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build();
            }
            return successResponse;
        });

        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("batch", data.get("mode"));
        assertEquals(1, data.get("success_count"));
        assertEquals(1, data.get("failure_count"));
        assertEquals("manifest-stock_daily-20240101-20240131-partial", data.get("dataset_id"));
        List<?> datasetIds = (List<?>) data.get("dataset_ids");
        assertEquals(1, datasetIds.size());
        assertEquals("atomic-ds-1", datasetIds.get(0));
    }

    @Test
    void getStockDaily_batch_allFailed_shouldNotEmitTopLevelManifest() throws Exception {
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build());

        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals(0, data.get("success_count"));
        assertEquals(2, data.get("failure_count"));
        assertFalse(data.containsKey("dataset_id"));
        assertTrue(((List<?>) data.get("dataset_ids")).isEmpty());
        verify(manifestWriter, never()).writeManifest(anyString(), anyString(), anyString(), anyList(), anyInt(), anyList());
    }

    @Test
    void checkParallelLimits_shouldExposeAdvancedHotConfigAndClamp() throws Exception {
        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfigAdvanced(100, 7)));

        String response = tools.checkParallelLimits();
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> advanced = (Map<?, ?>) ((Map<?, ?>) root.get("data")).get("advanced");

        assertEquals(20, advanced.get("maxItems"));
        assertEquals(7, advanced.get("previewRows"));

        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfigAdvanced(3, 0)));
        response = tools.checkParallelLimits();
        root = objectMapper.readValue(response, Map.class);
        advanced = (Map<?, ?>) ((Map<?, ?>) root.get("data")).get("advanced");
        assertEquals(0, advanced.get("previewRows"));

        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfigAdvanced(0, null)));
        response = tools.checkParallelLimits();
        root = objectMapper.readValue(response, Map.class);
        advanced = (Map<?, ?>) ((Map<?, ?>) root.get("data")).get("advanced");
        assertEquals(1, advanced.get("maxItems"));

        when(localConfigLoader.current()).thenReturn(Optional.of(hotConfigAdvanced(-1, null)));
        response = tools.checkParallelLimits();
        root = objectMapper.readValue(response, Map.class);
        advanced = (Map<?, ?>) ((Map<?, ?>) root.get("data")).get("advanced");
        assertEquals(1, advanced.get("maxItems"));
    }

    @Test
    void searchAssetInfoAdvanced_nameOnly_shouldReturnAdvancedDatasetAndPreview() throws Exception {
        String response = tools.searchAssetInfoAdvanced(Map.of(
                "mode", "advanced",
                "asset_type", "stock",
                "query", Map.of(
                        "name", "平安",
                        "conditions", List.of()
                )
        ));
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals("advanced", data.get("mode"));
        assertEquals("stock", data.get("asset_type"));
        assertEquals(1, data.get("row_count"));
        assertEquals("inline", data.get("dataset_status"));
        assertEquals(1, ((List<?>) data.get("preview_rows")).size());
    }

    @Test
    void searchIndexAdvanced_hasStock_shouldUseLatestTradeDateReason() throws Exception {
        when(indexService.getDomesticIndexWeightByConCodeAndDateRange(any())).thenReturn(
                DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticIndexWeightItem.newBuilder()
                                .setIndexCode("000300.SH")
                                .setConCode("000001.SZ")
                                .setTradeDate(20240115L)
                                .setWeight(4.0)
                                .build())
                        .addItems(DomesticIndexWeightItem.newBuilder()
                                .setIndexCode("000300.SH")
                                .setConCode("000001.SZ")
                                .setTradeDate(20240201L)
                                .setWeight(5.0)
                                .build())
                        .build());
        when(indexService.getDomesticIndexInfoByTsCode(any())).thenReturn(
                DomesticIndexInfoByTsCodeResponse.newBuilder()
                        .setItem(DomesticIndexInfoFullItem.newBuilder()
                                .setTsCode("000300.SH")
                                .setName("沪深300")
                                .setFullname("沪深300指数")
                                .setMarket("SSE")
                                .build())
                        .build());

        String response = tools.searchIndexAdvanced(Map.of(
                "mode", "advanced",
                "query", Map.of(
                        "conditions", List.of(Map.of(
                                "type", "has_stock",
                                "stock_code", "000001.SZ",
                                "start_date", "20240101",
                                "end_date", "20241231"
                        ))
                )
        ));
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");
        Map<?, ?> preview = (Map<?, ?>) ((List<?>) data.get("preview_rows")).get(0);
        List<?> reason = (List<?>) ((List<?>) preview.get("match_conditions")).get(0);

        assertEquals(true, root.get("ok"));
        assertEquals("沪深300", preview.get("name"));
        assertEquals(20240201L, ((Number) reason.get(0)).longValue());
        assertEquals(5.0, ((Number) reason.get(1)).doubleValue());
    }

    @Test
    void searchIndexAdvanced_shouldRejectInvalidDatesAndLowercaseNone() throws Exception {
        String invalidDate = tools.searchIndexAdvanced(Map.of(
                "mode", "advanced",
                "query", Map.of("conditions", List.of(Map.of(
                        "type", "has_stock",
                        "stock_code", "000001.SZ",
                        "start_date", "20240230",
                        "end_date", "20241231"
                )))
        ));
        Map<?, ?> root = objectMapper.readValue(invalidDate, Map.class);
        assertEquals(false, root.get("ok"));
        assertEquals("INVALID_ARGUMENT", ((Map<?, ?>) root.get("error")).get("code"));

        String lowercaseNone = tools.searchIndexAdvanced(Map.of(
                "mode", "advanced",
                "query", Map.of("conditions", List.of(Map.of(
                        "type", "has_stock",
                        "stock_code", "000001.SZ",
                        "start_date", "none",
                        "end_date", "NONE"
                )))
        ));
        root = objectMapper.readValue(lowercaseNone, Map.class);
        assertEquals(false, root.get("ok"));
        assertEquals("INVALID_ARGUMENT", ((Map<?, ?>) root.get("error")).get("code"));
    }

    @Test
    void searchIndexAdvanced_shouldAllowSinglePointAndNegativeWeight() throws Exception {
        when(indexService.getDomesticIndexWeightByConCodeAndDateRange(any())).thenReturn(
                DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticIndexWeightItem.newBuilder()
                                .setIndexCode("000300.SH")
                                .setConCode("000001.SZ")
                                .setTradeDate(20240115L)
                                .setWeight(-1.0)
                                .build())
                        .build());

        String response = tools.searchIndexAdvanced(Map.of(
                "mode", "advanced",
                "query", Map.of("conditions", List.of(Map.of(
                        "type", "has_stock",
                        "stock_code", "000001.SZ",
                        "start_date", "NONE",
                        "end_date", "NONE",
                        "min_weight", -1.0,
                        "max_weight", -1.0
                )))
        ));
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals(true, root.get("ok"));
        assertEquals(1, data.get("row_count"));

        String invalidWeightRange = tools.searchIndexAdvanced(Map.of(
                "mode", "advanced",
                "query", Map.of("conditions", List.of(Map.of(
                        "type", "has_stock",
                        "stock_code", "000001.SZ",
                        "start_date", "NONE",
                        "end_date", "NONE",
                        "min_weight", 2.0,
                        "max_weight", 1.0
                )))
        ));
        root = objectMapper.readValue(invalidWeightRange, Map.class);
        assertEquals(false, root.get("ok"));
        assertEquals("INVALID_ARGUMENT", ((Map<?, ?>) root.get("error")).get("code"));
    }

    private long toTimestamp(String date) {
        return DateConvertUtils.convertDateStrToLong(date, "yyyyMMdd");
    }

    private AgentLlmProperties hotConfig(Boolean datasetEnabled, Boolean emitManifest) {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.Tools toolsCfg = new AgentLlmProperties.Tools();
        AgentLlmProperties.MarketData marketData = new AgentLlmProperties.MarketData();
        AgentLlmProperties.MarketDataDataset dataset = new AgentLlmProperties.MarketDataDataset();
        dataset.setEnabled(datasetEnabled);
        AgentLlmProperties.MarketDataBatch batch = new AgentLlmProperties.MarketDataBatch();
        batch.setEmitManifest(emitManifest);
        marketData.setDataset(dataset);
        marketData.setBatch(batch);
        toolsCfg.setMarketData(marketData);
        cfg.setTools(toolsCfg);
        return cfg;
    }

    private AgentLlmProperties hotConfigAdvanced(Integer maxParallelAdvanced, Integer previewRows) {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxParallelQueriesInAdvancedMode(maxParallelAdvanced);
        runtime.setParallel(parallel);
        cfg.setRuntime(runtime);
        AgentLlmProperties.Tools toolsCfg = new AgentLlmProperties.Tools();
        AgentLlmProperties.MarketData marketData = new AgentLlmProperties.MarketData();
        AgentLlmProperties.MarketDataAdvanced advanced = new AgentLlmProperties.MarketDataAdvanced();
        advanced.setPreviewRows(previewRows);
        marketData.setAdvanced(advanced);
        toolsCfg.setMarketData(marketData);
        cfg.setTools(toolsCfg);
        return cfg;
    }
}
