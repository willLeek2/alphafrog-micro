package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockBalancesheetQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockCashflowQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockExpressQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockFinancialQueryRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockIncomeQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.StockBalancesheetItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockCashflowItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockExpressItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockIncomeItem;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getFinancialReport 的九例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsFinancialReportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticStockService stockService;
    private DatasetWriter datasetWriter;
    private DatasetRegistry datasetRegistry;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        stockService = mock(DomesticStockService.class);
        datasetWriter = mock(DatasetWriter.class);
        datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
    }

    private Map<String, Object> invoke(String tsCode, String reportType, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getFinancialReport(tsCode, reportType, start, end),
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
    private static List<Map<String, Object>> itemsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @Test
    void incomeHappyPinsEnvelopeAndRowFields() throws Exception {
        when(stockService.queryStockIncome(any())).thenReturn(DomesticStockIncomeQueryResponse.newBuilder()
                .addItems(StockIncomeItem.newBuilder()
                        .setTsCode("600519.SH")
                        .setEndDate("20240331")
                        .setReportType("income")
                        .setTotalRevenue(464.85)
                        .setBasicEps(33.19)
                        .build())
                .build());

        Map<String, Object> response = invoke("600519.SH", "INCOME", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        assertEquals("getFinancialReport", response.get("tool"));
        assertNull(response.get("error"));
        Map<String, Object> data = dataOf(response);
        assertEquals("600519.SH", data.get("ts_code"));
        assertEquals("income", data.get("report_type"));
        assertEquals("20240101", data.get("start_period"));
        assertEquals("20240331", data.get("end_period"));
        assertEquals(1, ((Number) data.get("count")).intValue());
        assertFalse(data.containsKey("dataset_id"), "writer 禁用时不输出 dataset_id");
        assertFalse(data.containsKey("dataset_ids"), "writer 禁用时不输出 dataset_ids");
        List<Map<String, Object>> items = itemsOf(data);
        assertEquals(1, items.size());
        Map<String, Object> row = items.get(0);
        assertEquals("600519.SH", row.get("ts_code"));
        assertEquals("20240331", row.get("end_date"));
        assertEquals("income", row.get("report_type"));
        assertEquals(464.85, ((Number) row.get("total_revenue")).doubleValue(), 1e-9);
        assertEquals(33.19, ((Number) row.get("basic_eps")).doubleValue(), 1e-9);
        for (String field : new String[]{"revenue", "n_income", "n_income_attr_p", "ebit", "ebitda", "rd_exp"}) {
            assertTrue(row.containsKey(field), "income 行必须含字段 " + field);
        }
        verify(stockService).queryStockIncome(argThat(req ->
                "600519.SH".equals(req.getTsCode())
                        && "20240101".equals(req.getStartPeriod())
                        && "20240331".equals(req.getEndPeriod())));
    }

    @Test
    void balancesheetDispatchesToCorrectMethodAndRowFields() throws Exception {
        when(stockService.queryStockBalancesheet(any())).thenReturn(DomesticStockBalancesheetQueryResponse.newBuilder()
                .addItems(StockBalancesheetItem.newBuilder()
                        .setTsCode("000001.SZ")
                        .setEndDate("20240331")
                        .setReportType("balancesheet")
                        .setTotalAssets(5.5e12)
                        .setTotalLiab(5.1e12)
                        .build())
                .build());

        Map<String, Object> response = invoke("000001.SZ", "balancesheet", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> row = itemsOf(dataOf(response)).get(0);
        assertEquals(5.5e12, ((Number) row.get("total_assets")).doubleValue(), 1.0);
        assertEquals(5.1e12, ((Number) row.get("total_liab")).doubleValue(), 1.0);
        for (String field : new String[]{"total_cur_assets", "total_cur_liab", "total_hldr_eqy_exc_min_int",
                "money_cap", "inventories", "lt_borr", "st_borr"}) {
            assertTrue(row.containsKey(field), "balancesheet 行必须含字段 " + field);
        }
        verify(stockService).queryStockBalancesheet(any());
        verify(stockService, never()).queryStockIncome(any());
        verify(stockService, never()).queryStockCashflow(any());
        verify(stockService, never()).queryStockExpress(any());
    }

    @Test
    void cashflowDispatchesToCorrectMethodAndRowFields() throws Exception {
        when(stockService.queryStockCashflow(any())).thenReturn(DomesticStockCashflowQueryResponse.newBuilder()
                .addItems(StockCashflowItem.newBuilder()
                        .setTsCode("600519.SH")
                        .setEndDate("20240331")
                        .setReportType("cashflow")
                        .setNCashflowAct(1.23e11)
                        .setFreeCashflow(9.8e10)
                        .build())
                .build());

        Map<String, Object> response = invoke("600519.SH", "cashflow", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> row = itemsOf(dataOf(response)).get(0);
        assertEquals(1.23e11, ((Number) row.get("n_cashflow_act")).doubleValue(), 1.0);
        assertEquals(9.8e10, ((Number) row.get("free_cashflow")).doubleValue(), 1.0);
        for (String field : new String[]{"n_cashflow_inv_act", "n_cash_flows_fnc_act", "c_fr_sale_sg"}) {
            assertTrue(row.containsKey(field), "cashflow 行必须含字段 " + field);
        }
        verify(stockService).queryStockCashflow(any());
        verify(stockService, never()).queryStockIncome(any());
        verify(stockService, never()).queryStockBalancesheet(any());
        verify(stockService, never()).queryStockExpress(any());
    }

    @Test
    void expressHappyPinsRowFieldsWithoutReportTypeField() throws Exception {
        when(stockService.queryStockExpress(any())).thenReturn(DomesticStockExpressQueryResponse.newBuilder()
                .addItems(StockExpressItem.newBuilder()
                        .setTsCode("600519.SH")
                        .setEndDate("20240331")
                        .setAnnDate("20240403")
                        .setRevenue(4.6e11)
                        .setDilutedEps(33.19)
                        .setYoyNetProfit(19.16)
                        .setPerfSummary("业绩增长稳健")
                        .build())
                .build());

        Map<String, Object> response = invoke("600519.SH", "express", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> row = itemsOf(dataOf(response)).get(0);
        assertEquals("20240403", row.get("ann_date"));
        assertEquals("业绩增长稳健", row.get("perf_summary"));
        assertEquals(19.16, ((Number) row.get("yoy_net_profit")).doubleValue(), 1e-9);
        assertFalse(row.containsKey("report_type"), "express 行不应含 report_type 字段");
        for (String field : new String[]{"revenue", "operate_profit", "n_income", "total_assets",
                "total_hldr_eqy_exc_min_int", "diluted_eps", "diluted_roe", "yoy_sales"}) {
            assertTrue(row.containsKey(field), "express 行必须含字段 " + field);
        }
        verify(stockService).queryStockExpress(any());
    }

    @Test
    void unknownReportTypeFailsBeforeDubbo() throws Exception {
        Map<String, Object> response = invoke("600519.SH", "annual", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        assertEquals(Boolean.TRUE, dataOf(response).isEmpty());
        Map<String, Object> error = errorOf(response);
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertTrue(String.valueOf(error.get("message")).contains("Must be one of: income, balancesheet, cashflow, express"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("annual", details.get("reportType"));
        verify(stockService, never()).queryStockIncome(any());
        verify(stockService, never()).queryStockBalancesheet(any());
        verify(stockService, never()).queryStockCashflow(any());
        verify(stockService, never()).queryStockExpress(any());
    }

    @Test
    void emptyItemsReturnsNoData() throws Exception {
        when(stockService.queryStockIncome(any())).thenReturn(DomesticStockIncomeQueryResponse.newBuilder().build());

        Map<String, Object> response = invoke("600519.SH", "income", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("NO_DATA", error.get("code"));
        assertEquals("No financial data found", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("600519.SH", details.get("ts_code"));
        assertEquals("income", details.get("report_type"));
        assertEquals("20240101", details.get("start_period"));
        assertEquals("20240331", details.get("end_period"));
    }

    @Test
    void dubboExceptionMapsToToolError() throws Exception {
        when(stockService.queryStockIncome(any())).thenThrow(new RuntimeException("dubbo timeout"));

        Map<String, Object> response = invoke("600519.SH", "income", "20240101", "20240331");

        assertEquals(Boolean.FALSE, response.get("ok"));
        Map<String, Object> error = errorOf(response);
        assertEquals("TOOL_ERROR", error.get("code"));
        assertEquals("Error fetching financial report", error.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        assertEquals("dubbo timeout", details.get("message"));
    }

    @Test
    void datasetDisabledOmitsDatasetFieldsAndSkipsRegistry() throws Exception {
        when(stockService.queryStockIncome(any())).thenReturn(DomesticStockIncomeQueryResponse.newBuilder()
                .addItems(StockIncomeItem.newBuilder().setTsCode("600519.SH").setEndDate("20240331").build())
                .build());

        Map<String, Object> response = invoke("600519.SH", "income", "20240101", "20240331");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertFalse(data.containsKey("dataset_id"));
        assertFalse(data.containsKey("dataset_ids"));
        verify(datasetRegistry, never()).registerDataset(any(), any(), any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void datasetEnabledWritesAndRegistersWithSharedPrefix() throws Exception {
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
        when(datasetWriter.writeDataset(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("ds-fin-001");
        when(stockService.queryStockIncome(any())).thenReturn(DomesticStockIncomeQueryResponse.newBuilder()
                .addItems(StockIncomeItem.newBuilder()
                        .setTsCode("600519.SH").setEndDate("20240331").setTotalRevenue(464.85).build())
                .addItems(StockIncomeItem.newBuilder()
                        .setTsCode("600519.SH").setEndDate("20240630").setTotalRevenue(819.77).build())
                .build());

        Map<String, Object> response = invoke("600519.SH", "income", "20240101", "20240630");

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = dataOf(response);
        assertEquals("ds-fin-001", data.get("dataset_id"));
        assertEquals(List.of("ds-fin-001"), data.get("dataset_ids"));
        verify(datasetWriter).writeDataset(
                org.mockito.ArgumentMatchers.eq("financial_income"),
                org.mockito.ArgumentMatchers.eq("shared-income"),
                org.mockito.ArgumentMatchers.eq("600519.SH"),
                org.mockito.ArgumentMatchers.eq("20240101"),
                org.mockito.ArgumentMatchers.eq("20240630"),
                any(), any(), any());
        verify(datasetRegistry).registerDataset(
                org.mockito.ArgumentMatchers.eq("financial_income"),
                org.mockito.ArgumentMatchers.eq("600519.SH"),
                org.mockito.ArgumentMatchers.eq("20240101"),
                org.mockito.ArgumentMatchers.eq("20240630"),
                any(),
                org.mockito.ArgumentMatchers.eq("ds-fin-001"),
                org.mockito.ArgumentMatchers.eq(2));
    }
}
