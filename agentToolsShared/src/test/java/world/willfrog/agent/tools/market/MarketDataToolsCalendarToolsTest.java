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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getTradingDaysSummary / isTradingDay 的十二例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsCalendarToolsTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final long MS_20240102 = 1704124800000L; // 2024-01-02T00:00:00+08:00
    private static final long MS_20240105 = 1704384000000L; // 2024-01-05T00:00:00+08:00

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticIndexService indexService;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        indexService = mock(DomesticIndexService.class);
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
    }

    private Map<String, Object> invokeSummary(String start, String end, String exchange) throws Exception {
        return objectMapper.readValue(tools.getTradingDaysSummary(start, end, exchange),
                new TypeReference<>() {});
    }

    private Map<String, Object> invokeIsTradingDay(String date, String exchange) throws Exception {
        return objectMapper.readValue(tools.isTradingDay(date, exchange), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("error");
    }

    // ---------- getTradingDaysSummary ----------

    @Test
    void summaryHappyPinsOverviewFieldsAndExchangeNormalization() throws Exception {
        when(indexService.getTradingDaysCountByDateRange(any()))
                .thenReturn(DomesticTradingDaysCountResponse.newBuilder()
                        .setTradingDaysCount(3)
                        .setFirstTradingDate(MS_20240102)
                        .setLastTradingDate(MS_20240105)
                        .build());

        // 空白 exchange 归一化为 SSE
        Map<String, Object> response = invokeSummary("20240101", "20240331", " ");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("getTradingDaysSummary", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("SSE", data.get("exchange"));
        assertEquals("20240101", data.get("start_date"));
        assertEquals("20240331", data.get("end_date"));
        assertEquals(3, ((Number) data.get("trading_days_count")).intValue());
        assertEquals("20240102", data.get("first_trading_date"));
        assertEquals("20240105", data.get("last_trading_date"));
        assertEquals("alphafrog_trade_calendar", data.get("calendar_source"));

        // 小写 exchange 归一化为大写后下发
        invokeSummary("20240101", "20240331", "szse");

        ArgumentCaptor<DomesticTradingDaysCountRequest> captor =
                ArgumentCaptor.forClass(DomesticTradingDaysCountRequest.class);
        verify(indexService, org.mockito.Mockito.times(2)).getTradingDaysCountByDateRange(captor.capture());
        assertEquals("SSE", captor.getAllValues().get(0).getExchange());
        assertEquals("SZSE", captor.getAllValues().get(1).getExchange());
        for (DomesticTradingDaysCountRequest req : captor.getAllValues()) {
            assertEquals(MS_20240101, req.getStartDate());
            assertEquals(MS_20240331, req.getEndDate());
        }
    }

    @Test
    void summaryEmptyRangeReturnsNoneStrings() throws Exception {
        when(indexService.getTradingDaysCountByDateRange(any()))
                .thenReturn(DomesticTradingDaysCountResponse.newBuilder()
                        .setTradingDaysCount(0)
                        .setFirstTradingDate(0)
                        .setLastTradingDate(0)
                        .build());

        Map<String, Object> response = invokeSummary("20240101", "20240331", "SSE");

        assertEquals(Boolean.TRUE, response.get("ok"), "区间无交易日仍是成功响应");
        Map<String, Object> data = dataOf(response);
        assertEquals(0, ((Number) data.get("trading_days_count")).intValue());
        assertEquals("NONE", data.get("first_trading_date"));
        assertEquals("NONE", data.get("last_trading_date"));
        assertEquals("alphafrog_trade_calendar", data.get("calendar_source"));
    }

    @Test
    void summaryInvalidDateFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = invokeSummary("20231301", "20240331", "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals(Boolean.TRUE, dataOf(response).isEmpty());
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertTrue(String.valueOf(error.get("message")).contains("ensure startDate <= endDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("SSE", details.get("exchange"));
        assertEquals("20231301", details.get("start_date"), "details 保留原始输入值");
        assertEquals("20240331", details.get("end_date"));
        verify(indexService, never()).getTradingDaysCountByDateRange(any());
    }

    @Test
    void summaryStartAfterEndFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = invokeSummary("20240331", "20240101", "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertTrue(String.valueOf(error.get("message")).contains("ensure startDate <= endDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("20240331", details.get("start_date"));
        assertEquals("20240101", details.get("end_date"));
        verify(indexService, never()).getTradingDaysCountByDateRange(any());
    }

    @Test
    void summaryDubboExceptionMapsToToolError() throws Exception {
        when(indexService.getTradingDaysCountByDateRange(any()))
                .thenThrow(new RuntimeException("trading days dubbo timeout"));

        Map<String, Object> response = invokeSummary("20240101", "20240331", "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error fetching trading day summary", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("trading days dubbo timeout", details.get("message"));
    }

    // ---------- isTradingDay ----------

    @Test
    void singleTradingDayHappyPinsFields() throws Exception {
        when(indexService.isTradingDay(any())).thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                .setTradingDay(true)
                .setCalendarRecordFound(true)
                .build());

        Map<String, Object> response = invokeIsTradingDay("20240102", " ");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("isTradingDay", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("SSE", data.get("exchange"));
        assertEquals("20240102", data.get("date"));
        assertEquals(Boolean.TRUE, data.get("is_trading_day"));
        assertEquals(Boolean.TRUE, data.get("calendar_record_found"));
        assertEquals("alphafrog_trade_calendar", data.get("calendar_source"));
        ArgumentCaptor<DomesticTradingDayStatusRequest> captor =
                ArgumentCaptor.forClass(DomesticTradingDayStatusRequest.class);
        verify(indexService).isTradingDay(captor.capture());
        assertEquals("SSE", captor.getValue().getExchange());
        assertEquals(MS_20240102, captor.getValue().getDate());
    }

    @Test
    void nonTradingDayDistinguishesRecordFoundVsMissing() throws Exception {
        when(indexService.isTradingDay(any()))
                .thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                        .setTradingDay(false).setCalendarRecordFound(true).build())
                .thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                        .setTradingDay(false).setCalendarRecordFound(false).build());

        Map<String, Object> closed = invokeIsTradingDay("20240106", "SSE");
        assertEquals(Boolean.TRUE, closed.get("ok"));
        Map<String, Object> closedData = dataOf(closed);
        assertEquals(Boolean.FALSE, closedData.get("is_trading_day"));
        assertEquals(Boolean.TRUE, closedData.get("calendar_record_found"), "有记录但休市");

        Map<String, Object> noRecord = invokeIsTradingDay("20240107", "SSE");
        assertEquals(Boolean.TRUE, noRecord.get("ok"));
        Map<String, Object> noRecordData = dataOf(noRecord);
        assertEquals(Boolean.FALSE, noRecordData.get("is_trading_day"));
        assertEquals(Boolean.FALSE, noRecordData.get("calendar_record_found"), "无记录与休市必须可区分");
    }

    @Test
    void singleInvalidDateFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = invokeIsTradingDay("20240230", "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals(Boolean.TRUE, dataOf(response).isEmpty());
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertEquals("Invalid date, please use YYYYMMDD.", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("SSE", details.get("exchange"));
        assertEquals("20240230", details.get("date"));
        verify(indexService, never()).isTradingDay(any());
    }

    @Test
    void batchLimitExceededFailsBeforeDubbo() throws Exception {
        // 默认 calendar.maxItems=50，构造 51 个合法日期（2024 年 1 月 31 天 + 2 月前 20 天）
        List<String> dates = new ArrayList<>();
        for (int d = 1; d <= 31; d++) {
            dates.add(String.format("202401%02d", d));
        }
        for (int d = 1; d <= 20; d++) {
            dates.add(String.format("202402%02d", d));
        }
        assertEquals(51, dates.size());

        Map<String, Object> response = invokeIsTradingDay(String.join("|", dates), "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals(Boolean.TRUE, dataOf(response).isEmpty());
        Map<String, Object> error = errorOf(response);
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
        assertEquals("Batch size exceeds the current parallel limit.", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("date", details.get("argument"));
        assertEquals(51, ((Number) details.get("actual_items")).intValue());
        assertEquals(50, ((Number) details.get("max_items")).intValue());
        assertEquals(dates, details.get("requested_values"));
        assertEquals("Call checkParallelLimits before batching, then split the request into batches no larger than max_items.",
                details.get("hint"));
        verify(indexService, never()).isTradingDay(any());
    }

    @Test
    void batchHappyAcceptsPipeAndJsonArrayInputs() throws Exception {
        when(indexService.isTradingDay(any())).thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                .setTradingDay(true).setCalendarRecordFound(true).build());

        for (String input : new String[]{"20240102|20240103", "[\"20240102\",\"20240103\"]"}) {
            Map<String, Object> response = invokeIsTradingDay(input, "SSE");

            assertEquals(Boolean.TRUE, response.get("ok"), "输入: " + input);
            Map<String, Object> data = dataOf(response);
            assertEquals("batch", data.get("mode"));
            assertEquals(List.of("20240102", "20240103"), data.get("dates"));
            assertEquals("SSE", data.get("exchange"));
            assertEquals(2, ((Number) data.get("success_count")).intValue());
            assertEquals(0, ((Number) data.get("failure_count")).intValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
            assertEquals(2, results.size());
            for (int i = 0; i < 2; i++) {
                Map<String, Object> row = results.get(i);
                assertEquals(List.of("20240102", "20240103").get(i), row.get("date"));
                assertEquals(Boolean.TRUE, row.get("ok"));
                @SuppressWarnings("unchecked")
                Map<String, Object> rowData = (Map<String, Object>) row.get("data");
                assertEquals(Boolean.TRUE, rowData.get("is_trading_day"));
                @SuppressWarnings("unchecked")
                Map<String, Object> rowError = (Map<String, Object>) row.get("error");
                assertTrue(rowError.isEmpty(), "成功行 error 为空对象");
            }
        }
    }

    @Test
    void batchPartialFailureKeepsOverallOk() throws Exception {
        when(indexService.isTradingDay(any())).thenReturn(DomesticTradingDayStatusResponse.newBuilder()
                .setTradingDay(true).setCalendarRecordFound(true).build());

        Map<String, Object> response = invokeIsTradingDay("20240102|not-a-date|20240103", "SSE");

        assertEquals(Boolean.TRUE, response.get("ok"), "局部失败不拖垮整批");
        Map<String, Object> data = dataOf(response);
        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((Number) data.get("success_count")).intValue());
        assertEquals(1, ((Number) data.get("failure_count")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertEquals(3, results.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> failedRow = results.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("ok")))
                .findFirst().orElseThrow();
        assertEquals("not-a-date", failedRow.get("date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> failedData = (Map<String, Object>) failedRow.get("data");
        assertTrue(failedData.isEmpty(), "失败行 data 为空对象");
        @SuppressWarnings("unchecked")
        Map<String, Object> failedError = (Map<String, Object>) failedRow.get("error");
        assertEquals("INVALID_ARGUMENT", failedError.get("code"));
    }

    @Test
    void singleDubboExceptionMapsToToolError() throws Exception {
        when(indexService.isTradingDay(any())).thenThrow(new RuntimeException("isTradingDay dubbo timeout"));

        Map<String, Object> response = invokeIsTradingDay("20240102", "SSE");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error checking trading day", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("isTradingDay dubbo timeout", details.get("message"));
    }
}
