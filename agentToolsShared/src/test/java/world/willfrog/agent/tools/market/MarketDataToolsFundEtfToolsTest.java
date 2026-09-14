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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizeItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getOffExchangeAssetDaily / getEtfAdj / getListedAssetShareSize 的十五例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsFundEtfToolsTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final long MS_20240102 = 1704124800000L; // 2024-01-02T00:00:00+08:00

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticFundService fundService;
    private DomesticListedAssetService listedAssetService;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private AgentLlmProperties llmProperties;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        fundService = mock(DomesticFundService.class);
        listedAssetService = mock(DomesticListedAssetService.class);
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        llmProperties = new AgentLlmProperties();
        tools = new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                llmProperties, objectMapper);
        ReflectionTestUtils.setField(tools, "domesticFundService", fundService);
        ReflectionTestUtils.setField(tools, "domesticListedAssetService", listedAssetService);
    }

    private Map<String, Object> invokeOffExchange(String tsCode, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getOffExchangeAssetDaily(tsCode, start, end),
                new TypeReference<>() {});
    }

    private Map<String, Object> invokeEtfAdj(String tsCode, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getEtfAdj(tsCode, start, end), new TypeReference<>() {});
    }

    private Map<String, Object> invokeShareSize(String tsCode, String start, String end, String exchange)
            throws Exception {
        return objectMapper.readValue(tools.getListedAssetShareSize(tsCode, start, end, exchange),
                new TypeReference<>() {});
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
    private static List<Map<String, Object>> previewRowsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("preview_rows");
    }

    private void enableAdjFactor() {
        llmProperties.getRuntime().getExecution().setAdjFactorEnabled(true);
    }

    // ---------- getOffExchangeAssetDaily ----------

    @Test
    void offExchangeHappyPinsPreviewCapAndCompactDateLeniency() throws Exception {
        DomesticFundNavsByTsCodeAndDateRangeResponse.Builder builder =
                DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder();
        for (int i = 0; i < 21; i++) {
            builder.addItems(DomesticFundNavItem.newBuilder()
                    .setNavDate(MS_20240102)
                    .setUnitNav(1.234)
                    .setAdjNav(1.567)
                    .build());
        }
        when(fundService.getDomesticFundNavsByTsCodeAndDateRange(any())).thenReturn(builder.build());

        Map<String, Object> response = invokeOffExchange("110022.OF", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("getOffExchangeAssetDaily", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("110022.OF", data.get("ts_code"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240331", data.get("end_date"));
        assertEquals("off_exchange_fund", data.get("asset_type"));
        assertEquals(21, ((Number) data.get("rows")).intValue());
        List<Map<String, Object>> preview = previewRowsOf(data);
        assertEquals(20, preview.size(), "preview_rows 上限 20，rows 保留服务端总数");
        Map<String, Object> row = preview.get(0);
        assertEquals(MS_20240102, ((Number) row.get("nav_date")).longValue());
        assertEquals(1.234, ((Number) row.get("unit_nav")).doubleValue(), 1e-9);
        assertEquals(1.567, ((Number) row.get("adj_nav")).doubleValue(), 1e-9);

        // compactDate 数字提取路径：带连字符的日期同样被接受且得到相同的毫秒时间戳
        Map<String, Object> dashed = invokeOffExchange("110022.OF", "2024-01-01", "2024-03-31");
        assertEquals(Boolean.TRUE, dashed.get("ok"));

        ArgumentCaptor<DomesticFundNavsByTsCodeAndDateRangeRequest> captor =
                ArgumentCaptor.forClass(DomesticFundNavsByTsCodeAndDateRangeRequest.class);
        verify(fundService, times(2)).getDomesticFundNavsByTsCodeAndDateRange(captor.capture());
        for (DomesticFundNavsByTsCodeAndDateRangeRequest req : captor.getAllValues()) {
            assertEquals("110022.OF", req.getTsCode());
            assertEquals(MS_20240101, req.getStartDateTimestamp());
            assertEquals(MS_20240331, req.getEndDateTimestamp());
        }
    }

    @Test
    void offExchangeInvalidInputFailsBeforeDubbo() throws Exception {
        // 空白 tsCode
        Map<String, Object> blankTsCode = invokeOffExchange("  ", "20240101", "20240331");
        assertEquals(Boolean.FALSE, blankTsCode.get("ok"));
        assertEquals("getOffExchangeAssetDaily", blankTsCode.get("tool"));
        assertEquals(Boolean.TRUE, dataOf(blankTsCode).isEmpty());
        Map<String, Object> error = errorOf(blankTsCode);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("Invalid tsCode or date range, use YYYYMMDD", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("", details.get("ts_code"));
        assertEquals("20240101", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));

        // 非日期字符串
        Map<String, Object> garbageDate = invokeOffExchange("110022.OF", "not-a-date", "20240331");
        assertEquals(Boolean.FALSE, garbageDate.get("ok"));
        assertEquals("INVALID_ARGUMENT", errorOf(garbageDate).get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details2 = (Map<String, Object>) errorOf(garbageDate).get("details");
        assertEquals("110022.OF", details2.get("ts_code"));
        assertEquals("not-a-date", details2.get("start_date"));

        // 13 位毫秒串被 compactDate 截断成前 8 位 "17040672"，宽容解析到 1704 年（毫秒为负）→ 同样拒绝
        Map<String, Object> msString = invokeOffExchange("110022.OF", "1704067200000", "20240331");
        assertEquals(Boolean.FALSE, msString.get("ok"));
        assertEquals("INVALID_ARGUMENT", errorOf(msString).get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details3 = (Map<String, Object>) errorOf(msString).get("details");
        assertEquals("17040672", details3.get("start_date"));

        verify(fundService, never()).getDomesticFundNavsByTsCodeAndDateRange(any());
    }

    @Test
    void offExchangeEmptyItemsReturnsNoData() throws Exception {
        when(fundService.getDomesticFundNavsByTsCodeAndDateRange(any()))
                .thenReturn(DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder().build());

        Map<String, Object> response = invokeOffExchange("110022.OF", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No fund nav data found", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("110022.OF", details.get("ts_code"));
        assertEquals("20240101", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));
    }

    @Test
    void offExchangeDubboExceptionMapsToToolError() throws Exception {
        when(fundService.getDomesticFundNavsByTsCodeAndDateRange(any()))
                .thenThrow(new RuntimeException("fund nav dubbo timeout"));

        Map<String, Object> response = invokeOffExchange("110022.OF", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error fetching fund nav data", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("fund nav dubbo timeout", details.get("message"));
    }

    // ---------- getEtfAdj ----------

    @Test
    void etfAdjCapabilityGateFiresBeforeArgumentValidation() throws Exception {
        // 默认 adjFactorEnabled=false；即使参数非法也先撞 capability 门
        Map<String, Object> response = invokeEtfAdj("  ", "bad", "bad");

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals("getEtfAdj", response.get("tool"));
        assertEquals(Boolean.TRUE, dataOf(response).isEmpty());
        Map<String, Object> error = errorOf(response);
        assertEquals("CAPABILITY_DISABLED", error.get("code"));
        assertEquals("ETF adj factor is disabled (adjFactorEnabled=false)", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals(Boolean.FALSE, details.get("adjFactorEnabled"));
        verify(listedAssetService, never()).getListedAssetAdjFactors(any());
    }

    @Test
    void etfAdjHappyPinsEtfAssetTypeAndRowFields() throws Exception {
        enableAdjFactor();
        when(listedAssetService.getListedAssetAdjFactors(any())).thenReturn(ListedAssetAdjFactorResponse.newBuilder()
                .addItems(ListedAssetAdjFactorItem.newBuilder()
                        .setTradeDate(MS_20240102)
                        .setAdjFactor(1.5)
                        .build())
                .build());

        Map<String, Object> response = invokeEtfAdj("510300.SH", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("getEtfAdj", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("510300.SH", data.get("ts_code"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240331", data.get("end_date"));
        assertEquals("etf", data.get("asset_type"));
        assertEquals(1, ((Number) data.get("rows")).intValue());
        Map<String, Object> row = previewRowsOf(data).get(0);
        assertEquals(MS_20240102, ((Number) row.get("trade_date")).longValue());
        assertEquals(1.5, ((Number) row.get("adj_factor")).doubleValue(), 1e-9);
        ArgumentCaptor<ListedAssetAdjFactorRequest> captor = ArgumentCaptor.forClass(ListedAssetAdjFactorRequest.class);
        verify(listedAssetService).getListedAssetAdjFactors(captor.capture());
        assertEquals("510300.SH", captor.getValue().getTsCode());
        assertEquals(MS_20240101, captor.getValue().getStartDate());
        assertEquals(MS_20240331, captor.getValue().getEndDate());
    }

    @Test
    void etfAdjEnabledButInvalidInputFailsAfterGate() throws Exception {
        enableAdjFactor();

        Map<String, Object> response = invokeEtfAdj("  ", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("Invalid tsCode or date range, use YYYYMMDD", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("", details.get("ts_code"));
        verify(listedAssetService, never()).getListedAssetAdjFactors(any());
    }

    @Test
    void etfAdjEmptyItemsReturnsNoData() throws Exception {
        enableAdjFactor();
        when(listedAssetService.getListedAssetAdjFactors(any()))
                .thenReturn(ListedAssetAdjFactorResponse.newBuilder().build());

        Map<String, Object> response = invokeEtfAdj("510300.SH", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No ETF adj factor data found", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("510300.SH", details.get("ts_code"));
        assertEquals("20240101", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));
    }

    @Test
    void etfAdjDubboExceptionMapsToToolError() throws Exception {
        enableAdjFactor();
        when(listedAssetService.getListedAssetAdjFactors(any()))
                .thenThrow(new RuntimeException("adj factor dubbo timeout"));

        Map<String, Object> response = invokeEtfAdj("510300.SH", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error fetching ETF adj factors", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("adj factor dubbo timeout", details.get("message"));
    }

    // ---------- getListedAssetShareSize ----------

    @Test
    void shareSizeWithoutExchangeOmitsExchangeKey() throws Exception {
        when(fundService.getDomesticEtfShareSizesByTsCodeAndDateRange(any()))
                .thenReturn(DomesticEtfShareSizesByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102)
                                .setTotalShare(1.0e9)
                                .setTotalSize(1.5e9)
                                .setExchange("SSE")
                                .build())
                        .build());

        Map<String, Object> response = invokeShareSize("510300.SH", "20240101", "20240331", " ");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("getListedAssetShareSize", response.get("tool"));
        Map<String, Object> data = dataOf(response);
        assertEquals("510300.SH", data.get("ts_code"));
        assertEquals("etf", data.get("asset_type"));
        assertFalse(data.containsKey("exchange"), "exchange 为空白时 data 不输出 exchange 键");
        assertEquals(1, ((Number) data.get("rows")).intValue());
        Map<String, Object> row = previewRowsOf(data).get(0);
        assertEquals(MS_20240102, ((Number) row.get("trade_date")).longValue());
        assertEquals(1.0e9, ((Number) row.get("total_share")).doubleValue(), 1.0);
        assertEquals(1.5e9, ((Number) row.get("total_size")).doubleValue(), 1.0);
        assertEquals("SSE", row.get("exchange"));
        ArgumentCaptor<DomesticEtfShareSizesByTsCodeAndDateRangeRequest> captor =
                ArgumentCaptor.forClass(DomesticEtfShareSizesByTsCodeAndDateRangeRequest.class);
        verify(fundService).getDomesticEtfShareSizesByTsCodeAndDateRange(captor.capture());
        assertEquals("510300.SH", captor.getValue().getTsCode());
        assertEquals(MS_20240101, captor.getValue().getStartDateTimestamp());
        assertEquals(MS_20240331, captor.getValue().getEndDateTimestamp());
    }

    @Test
    void shareSizeExchangeFiltersClientSideCaseInsensitive() throws Exception {
        when(fundService.getDomesticEtfShareSizesByTsCodeAndDateRange(any()))
                .thenReturn(DomesticEtfShareSizesByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102).setTotalShare(1.0).setTotalSize(2.0)
                                .setExchange("SSE").build())
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102).setTotalShare(3.0).setTotalSize(4.0)
                                .setExchange("SZSE").build())
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102).setTotalShare(5.0).setTotalSize(6.0)
                                .setExchange("szse").build())
                        .build());

        // 小写输入也命中：过滤用 equalsIgnoreCase，且 exchange 不下发 Dubbo
        Map<String, Object> response = invokeShareSize("510300.SH", "20240101", "20240331", "szse");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertEquals("SZSE", data.get("exchange"), "data.exchange 输出归一化后的大写值");
        assertEquals(2, ((Number) data.get("rows")).intValue());
        List<Map<String, Object>> preview = previewRowsOf(data);
        assertEquals(2, preview.size());
        for (Map<String, Object> row : preview) {
            assertTrue("SZSE".equalsIgnoreCase(String.valueOf(row.get("exchange"))),
                    "过滤后只允许 equalsIgnoreCase 命中 SZSE 的行，实际: " + row.get("exchange"));
        }
        verify(fundService, times(1)).getDomesticEtfShareSizesByTsCodeAndDateRange(any());
    }

    @Test
    void shareSizeIllegalExchangeFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = invokeShareSize("510300.SH", "20240101", "20240331", "NYSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("exchange must be SSE, SZSE, or BSE", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("NYSE", details.get("exchange"), "details.exchange 保留原始输入值");
        verify(fundService, never()).getDomesticEtfShareSizesByTsCodeAndDateRange(any());
    }

    @Test
    void shareSizeFilteredEmptyReturnsNoDataWithExchangeKey() throws Exception {
        when(fundService.getDomesticEtfShareSizesByTsCodeAndDateRange(any()))
                .thenReturn(DomesticEtfShareSizesByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102).setExchange("SSE").build())
                        .build())
                .thenReturn(DomesticEtfShareSizesByTsCodeAndDateRangeResponse.newBuilder().build());

        // 过滤后为空：details 四键含 exchange
        Map<String, Object> filtered = invokeShareSize("510300.SH", "20240101", "20240331", "SZSE");
        assertEquals(Boolean.FALSE, filtered.get("ok"));
        Map<String, Object> error = errorOf(filtered);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No ETF share size data found", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("510300.SH", details.get("ts_code"));
        assertEquals("20240101", details.get("start_date"));
        assertEquals("20240331", details.get("end_date"));
        assertEquals("SZSE", details.get("exchange"));

        // 响应本身为空且 exchange 空白：details 仍输出 exchange 键（值为空串）
        Map<String, Object> blankExchange = invokeShareSize("510300.SH", "20240101", "20240331", " ");
        assertEquals(Boolean.FALSE, blankExchange.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details2 = (Map<String, Object>) errorOf(blankExchange).get("details");
        assertEquals("", details2.get("exchange"));
    }

    @Test
    void shareSizeMissingProtoFieldsSerializeAsNull() throws Exception {
        when(fundService.getDomesticEtfShareSizesByTsCodeAndDateRange(any()))
                .thenReturn(DomesticEtfShareSizesByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticEtfShareSizeItem.newBuilder()
                                .setTradeDate(MS_20240102)
                                .build())
                        .build());

        Map<String, Object> response = invokeShareSize("510300.SH", "20240101", "20240331", " ");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> row = previewRowsOf(dataOf(response)).get(0);
        assertTrue(row.containsKey("total_share"), "hasTotalShare()=false 时 total_share 键存在且值为 null");
        assertNull(row.get("total_share"));
        assertTrue(row.containsKey("total_size"));
        assertNull(row.get("total_size"));
        assertEquals("", row.get("exchange"), "proto exchange 未设置时 getExchange() 返回空串");
    }

    @Test
    void shareSizeDubboExceptionMapsToToolError() throws Exception {
        when(fundService.getDomesticEtfShareSizesByTsCodeAndDateRange(any()))
                .thenThrow(new RuntimeException("share size dubbo timeout"));

        Map<String, Object> response = invokeShareSize("510300.SH", "20240101", "20240331", " ");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error fetching ETF share size", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("share size dubbo timeout", details.get("message"));
    }
}
