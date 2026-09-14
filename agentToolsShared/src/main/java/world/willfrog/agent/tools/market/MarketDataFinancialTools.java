package world.willfrog.agent.tools.market;

import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockBalancesheetQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockCashflowQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockExpressQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockFinancialQueryRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockIncomeQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务报表工具族的唯一生产实现。
 *
 * <p>{@link MarketDataTools} 只保留带 {@code @Tool} 的兼容入口；报表分发、字段映射和
 * dataset 写入都集中在这里，避免行情门面继续同时承担每个数据域的业务细节。</p>
 */
final class MarketDataFinancialTools {

    private final DomesticStockService domesticStockService;
    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;
    private final MarketDataTools support;

    MarketDataFinancialTools(DomesticStockService domesticStockService,
                             DatasetWriter datasetWriter,
                             DatasetRegistry datasetRegistry,
                             MarketDataTools support) {
        this.domesticStockService = domesticStockService;
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.support = support;
    }

    String getFinancialReport(String tsCode, String reportType, String startPeriod, String endPeriod) {
        try {
            String tool = "getFinancialReport";
            String type = support.nvl(reportType).trim().toLowerCase();
            DomesticStockFinancialQueryRequest request = DomesticStockFinancialQueryRequest.newBuilder()
                    .setTsCode(support.nvl(tsCode))
                    .setStartPeriod(support.compactDate(startPeriod))
                    .setEndPeriod(support.compactDate(endPeriod))
                    .build();

            List<Map<String, Object>> items = fetchItems(type, request);
            if (items == null) {
                return support.fail(tool, "INVALID_ARGUMENT", "Unknown reportType: " + type
                        + ". Must be one of: income, balancesheet, cashflow, express", Map.of("reportType", type));
            }
            if (items.isEmpty()) {
                return support.fail(tool, "NO_DATA", "No financial data found", Map.of(
                        "ts_code", support.nvl(tsCode),
                        "report_type", type,
                        "start_period", support.compactDate(startPeriod),
                        "end_period", support.compactDate(endPeriod)));
            }

            String datasetId = writeDataset(tsCode, startPeriod, endPeriod, type, items);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", support.nvl(tsCode));
            data.put("report_type", type);
            data.put("start_period", support.compactDate(startPeriod));
            data.put("end_period", support.compactDate(endPeriod));
            data.put("count", items.size());
            data.put("items", items);
            if (datasetId != null) {
                data.put("dataset_id", datasetId);
                data.put("dataset_ids", List.of(datasetId));
            }
            return support.ok(tool, data);
        } catch (Exception e) {
            return support.fail("getFinancialReport", "TOOL_ERROR", "Error fetching financial report",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    private List<Map<String, Object>> fetchItems(String type, DomesticStockFinancialQueryRequest request) {
        return switch (type) {
            case "income" -> mapIncome(domesticStockService.queryStockIncome(request));
            case "balancesheet" -> mapBalancesheet(domesticStockService.queryStockBalancesheet(request));
            case "cashflow" -> mapCashflow(domesticStockService.queryStockCashflow(request));
            case "express" -> mapExpress(domesticStockService.queryStockExpress(request));
            default -> null;
        };
    }

    private List<Map<String, Object>> mapIncome(DomesticStockIncomeQueryResponse response) {
        return response.getItemsList().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", item.getTsCode());
            row.put("end_date", item.getEndDate());
            row.put("report_type", item.getReportType());
            row.put("total_revenue", item.getTotalRevenue());
            row.put("revenue", item.getRevenue());
            row.put("n_income", item.getNIncome());
            row.put("n_income_attr_p", item.getNIncomeAttrP());
            row.put("basic_eps", item.getBasicEps());
            row.put("ebit", item.getEbit());
            row.put("ebitda", item.getEbitda());
            row.put("rd_exp", item.getRdExp());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> mapBalancesheet(DomesticStockBalancesheetQueryResponse response) {
        return response.getItemsList().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", item.getTsCode());
            row.put("end_date", item.getEndDate());
            row.put("report_type", item.getReportType());
            row.put("total_assets", item.getTotalAssets());
            row.put("total_liab", item.getTotalLiab());
            row.put("total_cur_assets", item.getTotalCurAssets());
            row.put("total_cur_liab", item.getTotalCurLiab());
            row.put("total_hldr_eqy_exc_min_int", item.getTotalHldrEqyExcMinInt());
            row.put("money_cap", item.getMoneyCap());
            row.put("inventories", item.getInventories());
            row.put("lt_borr", item.getLtBorr());
            row.put("st_borr", item.getStBorr());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> mapCashflow(DomesticStockCashflowQueryResponse response) {
        return response.getItemsList().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", item.getTsCode());
            row.put("end_date", item.getEndDate());
            row.put("report_type", item.getReportType());
            row.put("n_cashflow_act", item.getNCashflowAct());
            row.put("n_cashflow_inv_act", item.getNCashflowInvAct());
            row.put("n_cash_flows_fnc_act", item.getNCashFlowsFncAct());
            row.put("free_cashflow", item.getFreeCashflow());
            row.put("c_fr_sale_sg", item.getCFrSaleSg());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> mapExpress(DomesticStockExpressQueryResponse response) {
        return response.getItemsList().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", item.getTsCode());
            row.put("end_date", item.getEndDate());
            row.put("ann_date", item.getAnnDate());
            row.put("revenue", item.getRevenue());
            row.put("operate_profit", item.getOperateProfit());
            row.put("n_income", item.getNIncome());
            row.put("total_assets", item.getTotalAssets());
            row.put("total_hldr_eqy_exc_min_int", item.getTotalHldrEqyExcMinInt());
            row.put("diluted_eps", item.getDilutedEps());
            row.put("diluted_roe", item.getDilutedRoe());
            row.put("yoy_net_profit", item.getYoyNetProfit());
            row.put("yoy_sales", item.getYoySales());
            row.put("perf_summary", item.getPerfSummary());
            return row;
        }).toList();
    }

    private String writeDataset(String tsCode, String startPeriod, String endPeriod,
                                String type, List<Map<String, Object>> items) {
        if (!datasetWriter.isEnabled()) {
            return null;
        }
        String runId = AgentContext.getRunId();
        String prefix = (runId != null ? runId : "shared") + "-" + type;
        String start = support.compactDate(startPeriod);
        String end = support.compactDate(endPeriod);
        List<String> headers = headers(type);
        String datasetId = datasetWriter.writeDataset(
                "financial_" + type, prefix, tsCode, start, end, items, headers,
                row -> headers.stream().map(header -> row.getOrDefault(header, "")).toList());
        if (datasetRegistry.isEnabled()) {
            datasetRegistry.registerDataset(
                    "financial_" + type, tsCode, start, end, headers, datasetId, items.size());
        }
        return datasetId;
    }

    private List<String> headers(String type) {
        return switch (type) {
            case "income" -> Arrays.asList("ts_code", "end_date", "report_type", "total_revenue", "revenue",
                    "n_income", "n_income_attr_p", "basic_eps", "ebit", "ebitda", "rd_exp");
            case "balancesheet" -> Arrays.asList("ts_code", "end_date", "report_type", "total_assets", "total_liab",
                    "total_cur_assets", "total_cur_liab", "total_hldr_eqy_exc_min_int", "money_cap", "inventories",
                    "lt_borr", "st_borr");
            case "cashflow" -> Arrays.asList("ts_code", "end_date", "report_type", "n_cashflow_act",
                    "n_cashflow_inv_act", "n_cash_flows_fnc_act", "free_cashflow", "c_fr_sale_sg");
            case "express" -> Arrays.asList("ts_code", "end_date", "ann_date", "revenue", "operate_profit", "n_income",
                    "total_assets", "total_hldr_eqy_exc_min_int", "diluted_eps", "diluted_roe", "yoy_net_profit",
                    "yoy_sales", "perf_summary");
            default -> Arrays.asList("ts_code", "end_date");
        };
    }
}
