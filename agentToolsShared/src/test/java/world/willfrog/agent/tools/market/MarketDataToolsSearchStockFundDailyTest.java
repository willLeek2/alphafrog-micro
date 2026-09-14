package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchException;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：searchStock / searchFund / getStockDaily simple / searchAssetInfo 公开入口（task #111）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序；与 task #109 四个测试文件不交叉。
 */
class MarketDataToolsSearchStockFundDailyTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final List<String> DAILY_HEADERS = List.of(
            "ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticStockService stockService;
    private DomesticFundService fundService;
    private DomesticIndexService indexService;
    private DomesticListedAssetService listedAssetService;
    private IndexWeightDao indexWeightDao;
    private DatasetWriter datasetWriter;
    private DatasetRegistry datasetRegistry;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        stockService = mock(DomesticStockService.class);
        fundService = mock(DomesticFundService.class);
        indexService = mock(DomesticIndexService.class);
        listedAssetService = mock(DomesticListedAssetService.class);
        indexWeightDao = mock(IndexWeightDao.class);
        datasetWriter = mock(DatasetWriter.class);
        datasetRegistry = mock(DatasetRegistry.class);
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
        ReflectionTestUtils.setField(tools, "domesticFundService", fundService);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
        ReflectionTestUtils.setField(tools, "domesticListedAssetService", listedAssetService);
        ReflectionTestUtils.setField(tools, "indexWeightDao", indexWeightDao);
        ReflectionTestUtils.setField(tools, "swIndustryMemberDao", mock(SwIndustryMemberDao.class));
    }

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("error");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Map<String, Object> error) {
        return (Map<String, Object>) error.get("details");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void enableDataset() {
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
    }

    // ---------- searchStock ----------

    @Test
    void searchStockHappyPinsQueryCountItemsAndPreviewCap() throws Exception {
        DomesticStockSearchResponse.Builder builder = DomesticStockSearchResponse.newBuilder();
        for (int i = 0; i < 21; i++) {
            builder.addItems(DomesticStockInfoSimpleItem.newBuilder()
                    .setTsCode("000001.SZ").setName("平安银行").setIndustry("银行").build());
        }
        when(stockService.searchStock(any())).thenReturn(builder.build());

        Map<String, Object> response = parse(tools.searchStock("平安银行"));

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("searchStock", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("平安银行", data.get("query"));
        assertEquals(21, ((Number) data.get("count")).intValue(), "count 保留服务端总数");
        List<Map<String, Object>> items = listOfMaps(data.get("items"));
        assertEquals(20, items.size(), "预览上限 20 条");
        Map<String, Object> row = items.get(0);
        assertEquals("000001.SZ", row.get("ts_code"));
        assertEquals("平安银行", row.get("name"));
        assertEquals("银行", row.get("industry"));
        ArgumentCaptor<DomesticStockSearchRequest> captor = ArgumentCaptor.forClass(DomesticStockSearchRequest.class);
        verify(stockService).searchStock(captor.capture());
        assertEquals("平安银行", captor.getValue().getQuery());
    }

    @Test
    void searchStockNoData() throws Exception {
        when(stockService.searchStock(any())).thenReturn(DomesticStockSearchResponse.newBuilder().build());

        Map<String, Object> response = parse(tools.searchStock("不存在的股票"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No stocks found for keyword", error.get("message"));
        assertEquals("不存在的股票", detailsOf(error).get("keyword"));
    }

    @Test
    void searchStockToolError() throws Exception {
        when(stockService.searchStock(any())).thenThrow(new RuntimeException("stock search dubbo timeout"));

        Map<String, Object> response = parse(tools.searchStock("平安银行"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error searching stock", error.get("message"));
        assertEquals("stock search dubbo timeout", detailsOf(error).get("message"));
    }

    @Test
    void searchStockBatchPartialFailureKeepsOverallOk() throws Exception {
        when(stockService.searchStock(any())).thenAnswer(invocation -> {
            String query = ((DomesticStockSearchRequest) invocation.getArgument(0)).getQuery();
            if ("查不到".equals(query)) {
                return DomesticStockSearchResponse.newBuilder().build();
            }
            return DomesticStockSearchResponse.newBuilder()
                    .addItems(DomesticStockInfoSimpleItem.newBuilder()
                            .setTsCode("000001.SZ").setName("平安银行").setIndustry("银行").build())
                    .build();
        });

        Map<String, Object> response = parse(tools.searchStock("平安银行|查不到|贵州茅台"));

        assertEquals(Boolean.TRUE, response.get("ok"), "局部失败不拖垮整批");
        Map<String, Object> data = dataOf(response);
        assertEquals("batch", data.get("mode"));
        assertEquals(List.of("平安银行", "查不到", "贵州茅台"), data.get("queries"));
        assertEquals(2, ((Number) data.get("success_count")).intValue());
        assertEquals(1, ((Number) data.get("failure_count")).intValue());
        List<Map<String, Object>> results = listOfMaps(data.get("results"));
        assertEquals(3, results.size());
        Map<String, Object> failedRow = results.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("ok"))).findFirst().orElseThrow();
        assertEquals("查不到", failedRow.get("query"));
        assertTrue(((Map<?, ?>) failedRow.get("data")).isEmpty(), "失败行 data 为空对象");
        assertEquals("NO_DATA", ((Map<?, ?>) failedRow.get("error")).get("code"));
        Map<String, Object> okRow = results.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("ok"))).findFirst().orElseThrow();
        assertTrue(((Map<?, ?>) okRow.get("error")).isEmpty(), "成功行 error 为空对象");
        assertEquals("平安银行", ((Map<?, ?>) okRow.get("data")).get("query"));
    }

    @Test
    void searchStockBatchLimitExceededBeforeDubbo() throws Exception {
        // 默认 search.maxItems=3，4 个关键词超限
        Map<String, Object> response = parse(tools.searchStock("平安银行|贵州茅台|万科A|招商银行"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
        assertEquals("Batch size exceeds the current parallel limit.", error.get("message"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("keyword", details.get("argument"));
        assertEquals(4, ((Number) details.get("actual_items")).intValue());
        assertEquals(3, ((Number) details.get("max_items")).intValue());
        assertEquals(List.of("平安银行", "贵州茅台", "万科A", "招商银行"), details.get("requested_values"));
        assertEquals("Call checkParallelLimits before batching, then split the request into batches no larger than max_items.",
                details.get("hint"));
        verify(stockService, never()).searchStock(any());
    }

    // ---------- searchFund ----------

    @Test
    void searchFundHappyUsesDomesticFundServiceNotEtf() throws Exception {
        DomesticFundSearchResponse.Builder builder = DomesticFundSearchResponse.newBuilder();
        for (int i = 0; i < 21; i++) {
            builder.addItems(DomesticFundInfoSimpleItem.newBuilder()
                    .setTsCode("005827.OF").setName("易方达蓝筹精选").build());
        }
        when(fundService.searchDomesticFundInfo(any())).thenReturn(builder.build());

        Map<String, Object> response = parse(tools.searchFund("易方达蓝筹精选"));

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("searchFund", response.get("tool"));
        Map<String, Object> data = dataOf(response);
        assertEquals("易方达蓝筹精选", data.get("query"));
        assertEquals(21, ((Number) data.get("count")).intValue());
        List<Map<String, Object>> items = listOfMaps(data.get("items"));
        assertEquals(20, items.size(), "预览上限 20 条");
        Map<String, Object> row = items.get(0);
        assertEquals("005827.OF", row.get("ts_code"));
        assertEquals("易方达蓝筹精选", row.get("name"));
        assertFalse(row.containsKey("industry"), "基金行没有 industry 字段（与股票行区分）");
        ArgumentCaptor<DomesticFundSearchRequest> captor = ArgumentCaptor.forClass(DomesticFundSearchRequest.class);
        verify(fundService).searchDomesticFundInfo(captor.capture());
        assertEquals("易方达蓝筹精选", captor.getValue().getQuery());
        verify(stockService, never()).searchStock(any());
        verify(listedAssetService, never()).searchListedAssets(any());
    }

    @Test
    void searchFundNoData() throws Exception {
        when(fundService.searchDomesticFundInfo(any())).thenReturn(DomesticFundSearchResponse.newBuilder().build());

        Map<String, Object> response = parse(tools.searchFund("不存在的基金"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No funds found for keyword", error.get("message"));
        assertEquals("不存在的基金", detailsOf(error).get("keyword"));
    }

    @Test
    void searchFundToolError() throws Exception {
        when(fundService.searchDomesticFundInfo(any())).thenThrow(new RuntimeException("fund search dubbo timeout"));

        Map<String, Object> response = parse(tools.searchFund("易方达蓝筹精选"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error searching fund", error.get("message"));
        assertEquals("fund search dubbo timeout", detailsOf(error).get("message"));
    }

    @Test
    void searchFundBatchLimitFirstThenPartialFailure() throws Exception {
        // 超限先行：4 个关键词在调用下游前被拒
        Map<String, Object> limited = parse(tools.searchFund("基金A|基金B|基金C|基金D"));
        assertEquals(Boolean.FALSE, limited.get("ok"));
        assertEquals("BATCH_LIMIT_EXCEEDED", errorOf(limited).get("code"));
        assertEquals("keyword", detailsOf(errorOf(limited)).get("argument"));
        verify(fundService, never()).searchDomesticFundInfo(any());

        // 合规批量：局部失败不拖垮整批
        when(fundService.searchDomesticFundInfo(any())).thenAnswer(invocation -> {
            String query = ((DomesticFundSearchRequest) invocation.getArgument(0)).getQuery();
            if ("查不到".equals(query)) {
                return DomesticFundSearchResponse.newBuilder().build();
            }
            return DomesticFundSearchResponse.newBuilder()
                    .addItems(DomesticFundInfoSimpleItem.newBuilder()
                            .setTsCode("005827.OF").setName("易方达蓝筹精选").build())
                    .build();
        });
        Map<String, Object> batch = parse(tools.searchFund("易方达蓝筹精选|查不到"));
        assertEquals(Boolean.TRUE, batch.get("ok"));
        Map<String, Object> data = dataOf(batch);
        assertEquals("batch", data.get("mode"));
        assertEquals(1, ((Number) data.get("success_count")).intValue());
        assertEquals(1, ((Number) data.get("failure_count")).intValue());
        List<Map<String, Object>> results = listOfMaps(data.get("results"));
        Map<String, Object> failedRow = results.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("ok"))).findFirst().orElseThrow();
        assertEquals("查不到", failedRow.get("query"));
        assertEquals("NO_DATA", ((Map<?, ?>) failedRow.get("error")).get("code"));
    }

    // ---------- getStockDaily simple ----------

    @Test
    void stockDailyInvalidDateFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "not-a-date", "20240331"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals("getStockDaily", response.get("tool"));
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("Invalid date range, please use YYYYMMDD format (Asia/Shanghai).", error.get("message"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("000001.SZ", details.get("ts_code"));
        assertEquals("not-a-date", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));
        verify(stockService, never()).getStockDailyByTsCodeAndDateRange(any());
    }

    @Test
    void stockDailyStartAfterEndHasNoClientCheckAndFallsThrough() throws Exception {
        // 既有合同：simple 模式没有 start<=end 的客户端校验，倒置区间照样下发 Dubbo，
        // 空响应 + 股票存在时返回 TIME_SERIES_EMPTY
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build());
        when(stockService.getStockInfoByTsCode(any()))
                .thenReturn(DomesticStockInfoByTsCodeResponse.newBuilder()
                        .setItem(world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoFullItem.newBuilder()
                                .setTsCode("000001.SZ").build())
                        .build());

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "20240331", "20240101"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TIME_SERIES_EMPTY", error.get("code"));
        assertEquals("该资产在指定日期范围内无日线记录，请考虑调整起止日期或更换资产。", error.get("message"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("000001.SZ", details.get("ts_code"));
        assertEquals("20240331", details.get("start_date"));
        assertEquals("20240101", details.get("end_date"));
        ArgumentCaptor<DomesticStockDailyByTsCodeAndDateRangeRequest> captor =
                ArgumentCaptor.forClass(DomesticStockDailyByTsCodeAndDateRangeRequest.class);
        verify(stockService).getStockDailyByTsCodeAndDateRange(captor.capture());
        assertEquals(MS_20240331, captor.getValue().getStartDate());
        assertEquals(MS_20240101, captor.getValue().getEndDate());
    }

    @Test
    void stockDailyAssetNotFoundWhenStockMissing() throws Exception {
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build());
        when(stockService.getStockInfoByTsCode(any()))
                .thenReturn(DomesticStockInfoByTsCodeResponse.newBuilder().build());

        Map<String, Object> response = parse(tools.getStockDaily("999999.SH", "20240101", "20240331"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("ASSET_NOT_FOUND", error.get("code"));
        assertEquals("资产 999999.SH 不存在，请检查代码是否正确或更换查询标的。", error.get("message"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("999999.SH", details.get("ts_code"));
        assertEquals("20240101", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));
    }

    @Test
    void stockDailyDatasetReuseSkipsServiceAndWrite() throws Exception {
        enableDataset();
        DatasetRegistry.DatasetMeta meta = mock(DatasetRegistry.DatasetMeta.class);
        when(meta.getDatasetId()).thenReturn("ds-reuse-1");
        when(meta.getRowCount()).thenReturn(42);
        when(meta.getStartDate()).thenReturn("20240101");
        when(meta.getEndDate()).thenReturn("20240331");
        when(datasetRegistry.findReusable(any(), any(), any(), any(), any())).thenReturn(Optional.of(meta));

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "20240101", "20240331"));

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertEquals("000001.SZ", data.get("ts_code"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240331", data.get("end_date"));
        assertEquals(42, ((Number) data.get("rows")).intValue());
        assertEquals(DAILY_HEADERS, data.get("fields"));
        assertEquals("reused", data.get("source"));
        assertEquals(Boolean.TRUE, data.get("cache_hit"));
        assertEquals("ds-reuse-1", data.get("dataset_id"));
        assertEquals(List.of("ds-reuse-1"), data.get("dataset_ids"));
        verify(stockService, never()).getStockDailyByTsCodeAndDateRange(any());
        verify(datasetWriter, never()).writeDataset(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stockDailyMissFetchesWritesAndRegistersDataset() throws Exception {
        enableDataset();
        when(datasetRegistry.findReusable(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(datasetWriter.writeDataset(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("ds-new-1");
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(dailyItem("000001.SZ", 20240102L, 10.2))
                        .build());

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "20240101", "20240331"));

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertEquals("created", data.get("source"));
        assertEquals(Boolean.FALSE, data.get("cache_hit"));
        assertEquals("ds-new-1", data.get("dataset_id"));
        assertEquals(List.of("ds-new-1"), data.get("dataset_ids"));
        assertEquals(1, ((Number) data.get("rows")).intValue());
        assertEquals(DAILY_HEADERS, data.get("fields"));
        assertFalse(data.containsKey("preview_rows"), "created 路径不输出 preview_rows");
        ArgumentCaptor<DomesticStockDailyByTsCodeAndDateRangeRequest> captor =
                ArgumentCaptor.forClass(DomesticStockDailyByTsCodeAndDateRangeRequest.class);
        verify(stockService).getStockDailyByTsCodeAndDateRange(captor.capture());
        assertEquals("000001.SZ", captor.getValue().getTsCode());
        assertEquals(MS_20240101, captor.getValue().getStartDate());
        assertEquals(MS_20240331, captor.getValue().getEndDate());
        verify(datasetWriter).writeDataset(
                eq("stock_daily"), eq("shared-stock"), eq("000001.SZ"),
                eq("20240101"), eq("20240331"), any(), any(), any());
        verify(datasetRegistry).registerDataset(
                eq("stock_daily"), eq("000001.SZ"), eq("20240101"), eq("20240331"),
                any(), eq("ds-new-1"), eq(1));
    }

    @Test
    void stockDailyInlinePreviewCapWhenWriterDisabled() throws Exception {
        DomesticStockDailyByTsCodeAndDateRangeResponse.Builder builder =
                DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder();
        for (int i = 0; i < 21; i++) {
            builder.addItems(dailyItem("000001.SZ", 20240102L, 10.2));
        }
        when(stockService.getStockDailyByTsCodeAndDateRange(any())).thenReturn(builder.build());

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "20240101", "20240331"));

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertEquals("inline", data.get("source"));
        assertEquals(Boolean.FALSE, data.get("cache_hit"));
        assertEquals("", data.get("dataset_id"), "writer 禁用时 dataset_id 为空串");
        assertEquals(List.of(), data.get("dataset_ids"), "空 dataset_id 时 dataset_ids 为空列表");
        assertEquals(21, ((Number) data.get("rows")).intValue());
        assertEquals(DAILY_HEADERS, data.get("fields"));
        List<Map<String, Object>> preview = listOfMaps(data.get("preview_rows"));
        assertEquals(20, preview.size(), "inline 预览上限 20 条");
        Map<String, Object> row = preview.get(0);
        assertEquals(2, row.size(), "inline 预览行只含 trade_date 与 close 两个字段");
        assertEquals(20240102L, ((Number) row.get("trade_date")).longValue());
        assertEquals(10.2, ((Number) row.get("close")).doubleValue(), 1e-9);
    }

    @Test
    void stockDailyBatchLimitExceededBeforeDubbo() throws Exception {
        // 默认 daily.maxItems=2，3 个代码超限
        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ|600519.SH|000300.SH", "20240101", "20240331"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("tsCode", details.get("argument"));
        assertEquals(3, ((Number) details.get("actual_items")).intValue());
        assertEquals(2, ((Number) details.get("max_items")).intValue());
        assertEquals(List.of("000001.SZ", "600519.SH", "000300.SH"), details.get("requested_values"));
        assertEquals("Call checkParallelLimits before batching, then split the request into batches no larger than max_items.",
                details.get("hint"));
        verify(stockService, never()).getStockDailyByTsCodeAndDateRange(any());
    }

    @Test
    void stockDailyBatchPartialFailureKeepsOverallOk() throws Exception {
        when(stockService.getStockDailyByTsCodeAndDateRange(any())).thenAnswer(invocation -> {
            String tsCode = ((DomesticStockDailyByTsCodeAndDateRangeRequest) invocation.getArgument(0)).getTsCode();
            if ("000001.SZ".equals(tsCode)) {
                return DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(dailyItem("000001.SZ", 20240102L, 10.2)).build();
            }
            return DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        });
        when(stockService.getStockInfoByTsCode(any()))
                .thenReturn(DomesticStockInfoByTsCodeResponse.newBuilder().build());

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ|999999.SH", "20240101", "20240331"));

        assertEquals(Boolean.TRUE, response.get("ok"), "局部失败不拖垮整批");
        Map<String, Object> data = dataOf(response);
        assertEquals("batch", data.get("mode"));
        assertEquals(List.of("000001.SZ", "999999.SH"), data.get("ts_codes"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240331", data.get("end_date"));
        assertEquals(1, ((Number) data.get("success_count")).intValue());
        assertEquals(1, ((Number) data.get("failure_count")).intValue());
        List<Map<String, Object>> results = listOfMaps(data.get("results"));
        assertEquals(2, results.size());
        Map<String, Object> failedRow = results.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("ok"))).findFirst().orElseThrow();
        assertEquals("999999.SH", failedRow.get("ts_code"));
        assertEquals("ASSET_NOT_FOUND", ((Map<?, ?>) failedRow.get("error")).get("code"));
        Map<String, Object> okRow = results.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("ok"))).findFirst().orElseThrow();
        assertEquals("000001.SZ", okRow.get("ts_code"));
        assertEquals("inline", ((Map<?, ?>) okRow.get("data")).get("source"));
    }

    @Test
    void stockDailyToolErrorUsesChineseMessage() throws Exception {
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenThrow(new RuntimeException("stock daily dubbo timeout"));

        Map<String, Object> response = parse(tools.getStockDaily("000001.SZ", "20240101", "20240331"));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("查询失败，请重试或更换工具。如果持续失败，请换一种方式完成任务。", error.get("message"));
        assertEquals("stock daily dubbo timeout", detailsOf(error).get("message"));
    }

    // ---------- searchAssetInfo 公开入口 ----------

    @Test
    void assetInfoSimpleStockOnlyTagsAssetType() throws Exception {
        when(stockService.searchStock(any())).thenReturn(DomesticStockSearchResponse.newBuilder()
                .addItems(DomesticStockInfoSimpleItem.newBuilder()
                        .setTsCode("000001.SZ").setName("平安银行").setIndustry("银行").build())
                .build());

        Map<String, Object> response = parse(tools.searchAssetInfo("平安", "stock", null, null, null));

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("searchAssetInfo", response.get("tool"));
        Map<String, Object> data = dataOf(response);
        assertEquals("平安", data.get("query"));
        assertEquals(List.of("stock"), data.get("asset_types"));
        assertEquals("domestic", data.get("market_scope"));
        assertEquals(1, ((Number) data.get("count")).intValue());
        assertFalse(data.containsKey("partial_errors"), "无局部失败时不输出 partial_errors 键");
        Map<String, Object> row = listOfMaps(data.get("items")).get(0);
        assertEquals("000001.SZ", row.get("ts_code"));
        assertEquals("平安银行", row.get("name"));
        assertEquals("银行", row.get("industry"));
        assertEquals("stock", row.get("asset_type"), "合并行打上 asset_type 标签");
        verify(stockService).searchStock(any());
        verify(fundService, never()).searchDomesticFundInfo(any());
        verify(indexService, never()).searchDomesticIndex(any());
        verify(listedAssetService, never()).searchListedAssets(any());
    }

    @Test
    void assetInfoSimpleDefaultAllTypesWithPartialErrors() throws Exception {
        when(stockService.searchStock(any())).thenReturn(DomesticStockSearchResponse.newBuilder()
                .addItems(DomesticStockInfoSimpleItem.newBuilder()
                        .setTsCode("000001.SZ").setName("平安银行").setIndustry("银行").build())
                .build());
        when(indexService.searchDomesticIndex(any())).thenReturn(DomesticIndexSearchResponse.newBuilder().build());
        when(fundService.searchDomesticFundInfo(any())).thenReturn(DomesticFundSearchResponse.newBuilder().build());
        when(listedAssetService.searchListedAssets(any()))
                .thenThrow(new RuntimeException("listed asset search unavailable"));

        // assetTypes 空白 → 默认覆盖全部四类资产
        Map<String, Object> response = parse(tools.searchAssetInfo("平安", null, null, null, null));

        assertEquals(Boolean.TRUE, response.get("ok"), "部分类型失败时整体仍成功");
        Map<String, Object> data = dataOf(response);
        assertEquals(List.of("stock", "etf", "index", "off_exchange_fund"), data.get("asset_types"));
        assertEquals("domestic", data.get("market_scope"));
        assertEquals(1, ((Number) data.get("count")).intValue());
        Map<String, Object> row = listOfMaps(data.get("items")).get(0);
        assertEquals("stock", row.get("asset_type"));
        List<Map<String, Object>> partialErrors = listOfMaps(data.get("partial_errors"));
        assertEquals(3, partialErrors.size());
        for (Map<String, Object> pe : partialErrors) {
            assertEquals("平安", pe.get("query"));
            assertTrue(pe.get("asset_type") instanceof String);
            assertTrue(pe.get("code") instanceof String);
            assertTrue(pe.get("message") instanceof String);
        }
        assertTrue(partialErrors.stream().anyMatch(pe -> "TOOL_ERROR".equals(pe.get("code"))),
                "ETF 子搜索异常映射为 TOOL_ERROR 局部错误");
        verify(stockService).searchStock(any());
        verify(indexService).searchDomesticIndex(any());
        verify(fundService).searchDomesticFundInfo(any());
        verify(listedAssetService).searchListedAssets(any());
    }

    @Test
    void assetInfoNoDataWhenAllTypesEmpty() throws Exception {
        when(stockService.searchStock(any())).thenReturn(DomesticStockSearchResponse.newBuilder().build());
        when(indexService.searchDomesticIndex(any())).thenReturn(DomesticIndexSearchResponse.newBuilder().build());
        when(fundService.searchDomesticFundInfo(any())).thenReturn(DomesticFundSearchResponse.newBuilder().build());
        when(listedAssetService.searchListedAssets(any())).thenReturn(ListedAssetSearchResponse.newBuilder().build());

        Map<String, Object> response = parse(tools.searchAssetInfo("查无此物", null, null, null, null));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No assets found for query", error.get("message"));
        Map<String, Object> details = detailsOf(error);
        assertEquals("查无此物", details.get("query"));
        assertEquals(List.of("stock", "etf", "index", "off_exchange_fund"), details.get("asset_types"));
    }

    @Test
    void assetInfoInvalidMarketScopeFailsBeforeAnyService() throws Exception {
        Map<String, Object> response = parse(tools.searchAssetInfo("平安", null, "global", null, null));

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("Only marketScope=domestic is supported in v1", error.get("message"));
        assertEquals("global", detailsOf(error).get("marketScope"));
        verify(stockService, never()).searchStock(any());
        verify(indexService, never()).searchDomesticIndex(any());
        verify(fundService, never()).searchDomesticFundInfo(any());
        verify(listedAssetService, never()).searchListedAssets(any());
    }

    @Test
    void assetInfoAdvancedForwardSuccess() throws Exception {
        when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange(any(), anyLong(), anyLong()))
                .thenReturn(List.of(indexWeightPojo("000300.SH", "000001.SZ", "20240115", 5.0)));
        when(listedAssetService.getListedAssetInfo(any())).thenReturn(ListedAssetInfoResponse.newBuilder()
                .setItem(ListedAssetInfoItem.newBuilder().setTsCode("000001.SZ").setName("平安银行").build())
                .build());

        String advancedQuery =
                "{\"asset_type\":\"stock\",\"conditions\":[{\"type\":\"index_component\",\"index_code\":\"000300.SH\","
                        + "\"start_date\":\"20240101\",\"end_date\":\"20241231\"}]}";
        Map<String, Object> response = parse(tools.searchAssetInfo(null, null, null, "advanced", advancedQuery));

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("searchAssetInfo", response.get("tool"));
        Map<String, Object> data = dataOf(response);
        assertEquals("advanced", data.get("mode"));
        assertEquals("stock", data.get("asset_type"));
        assertEquals(1, ((Number) data.get("row_count")).intValue());
        assertEquals("", data.get("dataset_id"), "writer 禁用时 dataset_id 为空串");
        assertEquals("inline", data.get("dataset_status"));
        assertEquals(Boolean.FALSE, data.get("reused"));
        assertEquals(10, ((Number) data.get("preview_limit")).intValue());
        assertTrue(data.containsKey("conditions_meta"));
        assertTrue(data.containsKey("dataset"), "dataset_id 为空时回传完整 dataset 对象");
        List<Map<String, Object>> preview = listOfMaps(data.get("preview_rows"));
        assertEquals(1, preview.size());
        Map<String, Object> row = preview.get(0);
        assertEquals("000001.SZ", row.get("ts_code"));
        assertEquals("平安银行", row.get("name"));
        assertEquals("stock", row.get("asset_type"));
        verify(indexWeightDao).getLatestIndexWeightsByTsCodeAndDateRange(eq("000300.SH"), anyLong(), anyLong());
    }

    @Test
    void assetInfoAdvancedMalformedQueryThrowsAndUnknownModeFallsToSimple() throws Exception {
        // 非法 advancedQuery 在当前实现里直接抛出 AdvancedSearchException（不是 fail JSON），且不触达任何下游
        AdvancedSearchException notJson = assertThrows(AdvancedSearchException.class,
                () -> tools.searchAssetInfo("平安", "stock", null, "advanced", "not-json"));
        assertEquals("advancedQuery must be a JSON object.", notJson.getMessage());
        AdvancedSearchException badJson = assertThrows(AdvancedSearchException.class,
                () -> tools.searchAssetInfo("平安", "stock", null, "advanced", "{invalid json"));
        assertEquals("advancedQuery JSON is invalid.", badJson.getMessage());
        verify(stockService, never()).searchStock(any());
        verify(listedAssetService, never()).searchListedAssets(any());

        // mode 解析宽容：未知 mode 不进入 advanced 分支，按 simple 处理
        when(stockService.searchStock(any())).thenReturn(DomesticStockSearchResponse.newBuilder()
                .addItems(DomesticStockInfoSimpleItem.newBuilder()
                        .setTsCode("000001.SZ").setName("平安银行").setIndustry("银行").build())
                .build());
        Map<String, Object> response = parse(tools.searchAssetInfo("平安", "stock", null, "turbo", null));
        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("平安", dataOf(response).get("query"));
        verify(stockService).searchStock(any());
    }

    private static DomesticStockDailyItem dailyItem(String tsCode, long tradeDate, double close) {
        return DomesticStockDailyItem.newBuilder()
                .setTsCode(tsCode).setTradeDate(tradeDate)
                .setOpen(10.0).setHigh(10.5).setLow(9.8).setClose(close)
                .setPreClose(10.0).setChange(0.2).setPctChg(2.0).setVol(1000.0).setAmount(10000.0)
                .build();
    }

    private static IndexWeight indexWeightPojo(String indexCode, String conCode, String tradeDate, double weight) {
        IndexWeight w = new IndexWeight();
        w.setIndexCode(indexCode);
        w.setConCode(conCode);
        w.setTradeDate(Long.parseLong(tradeDate));
        w.setWeight(weight);
        return w;
    }
}
