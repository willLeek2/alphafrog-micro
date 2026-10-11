package world.willfrog.agent.tools.market;

import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizeItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 场外基金净值与 ETF 辅助数据工具族的唯一生产实现。 */
final class MarketDataFundEtfTools {

    /**
     * 指标库扩充：场外基金净值数据集表头，顺序对齐
     * ProtoFieldExtractor.createFundNavFieldMapping()（tsCode, navDate, unitNav, accumNav, adjNav）。
     */
    private static final List<String> FUND_NAV_DATASET_HEADERS = List.of(
            "ts_code", "nav_date", "unit_nav", "accum_nav", "adj_nav");

    private final DomesticFundService domesticFundService;
    private final DomesticListedAssetService domesticListedAssetService;
    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;
    private final MarketDataTools support;

    MarketDataFundEtfTools(DomesticFundService domesticFundService,
                           DomesticListedAssetService domesticListedAssetService,
                           DatasetWriter datasetWriter,
                           DatasetRegistry datasetRegistry,
                           MarketDataTools support) {
        this.domesticFundService = domesticFundService;
        this.domesticListedAssetService = domesticListedAssetService;
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.support = support;
    }

    String getOffExchangeAssetDaily(String tsCode, String startDate, String endDate, String includeDataset) {
        String normalizedTsCode = support.nvl(tsCode).trim();
        String normalizedStart = support.compactDate(startDate);
        String normalizedEnd = support.compactDate(endDate);
        long startMs = support.convertToMsTimestamp(normalizedStart);
        long endMs = support.convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return support.fail("getOffExchangeAssetDaily", "INVALID_ARGUMENT",
                    "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        // 指标库扩充：整段净值序列数据集做成传参才走——不传/false 保持 20 行轻量预览（与现状一致）
        String flag = support.nvl(includeDataset).trim().toLowerCase();
        boolean datasetMode;
        if (flag.isEmpty() || "false".equals(flag)) {
            datasetMode = false;
        } else if ("true".equals(flag)) {
            datasetMode = true;
        } else {
            return support.fail("getOffExchangeAssetDaily", "INVALID_ARGUMENT",
                    "includeDataset 只接受 true/false：true 时把整段净值序列写入数据集并返回 dataset_id；不传或 false 保持 20 行预览。",
                    Map.of("includeDataset", support.nvl(includeDataset)));
        }
        try {
            DomesticFundNavsByTsCodeAndDateRangeResponse response =
                    domesticFundService.getDomesticFundNavsByTsCodeAndDateRange(
                            DomesticFundNavsByTsCodeAndDateRangeRequest.newBuilder()
                                    .setTsCode(normalizedTsCode)
                                    .setStartDateTimestamp(startMs)
                                    .setEndDateTimestamp(endMs)
                                    .build());
            if (response.getItemsCount() <= 0) {
                return support.fail("getOffExchangeAssetDaily", "NO_DATA", "No fund nav data found", Map.of(
                        "ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
            }
            if (datasetMode && datasetWriter.isEnabled()) {
                return offExchangeNavDatasetResponse(response, normalizedTsCode, normalizedStart, normalizedEnd);
            }
            // 默认轻量预览；预览行补累计净值（未披露时为 null，不是 0）
            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nav_date", item.getNavDate());
                row.put("unit_nav", item.getUnitNav());
                row.put("accum_nav", item.hasAccumNav() ? item.getAccumNav() : null);
                row.put("adj_nav", item.getAdjNav());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "off_exchange_fund");
            data.put("rows", response.getItemsCount());
            data.put("preview_rows", previewRows);
            if (datasetMode) {
                data.put("dataset_note", "includeDataset=true 但数据集写入未启用，返回轻量预览");
            }
            return support.ok("getOffExchangeAssetDaily", data);
        } catch (Exception e) {
            return support.fail("getOffExchangeAssetDaily", "TOOL_ERROR", "Error fetching fund nav data",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    /**
     * 整段净值序列写数据集（fund_nav）：先查登记表复用，未命中再写入并登记。
     *
     * <p>累计净值区间收益类方法需要整段序列，20 行预览不够；此路径仅在
     * includeDataset=true 且数据集启用时进入，默认查询路径不受影响。</p>
     */
    private String offExchangeNavDatasetResponse(DomesticFundNavsByTsCodeAndDateRangeResponse response,
                                                 String tsCode,
                                                 String start,
                                                 String end) {
        if (datasetRegistry.isEnabled()) {
            var reused = datasetRegistry.findReusable("fund_nav", tsCode, start, end, FUND_NAV_DATASET_HEADERS);
            if (reused.isPresent()) {
                // 复用命中报数据集实际覆盖区间（超集复用时与请求窗口不同），与 getStockDaily
                // 复用分支的 datasetDataFromMeta 语义一致——rows 是数据集实际行数，区间必须配套，
                // 否则下游按「首行到末行」算区间收益会把长窗口当成请求窗口。
                String metaStart = reused.get().getStartDate();
                String metaEnd = reused.get().getEndDate();
                return support.ok("getOffExchangeAssetDaily", fundNavDatasetData(
                        tsCode,
                        metaStart == null || metaStart.isBlank() ? start : metaStart,
                        metaEnd == null || metaEnd.isBlank() ? end : metaEnd,
                        reused.get().getDatasetId(), reused.get().getRowCount(), "reused", true));
            }
        }
        String runId = AgentContext.getRunId();
        String prefix = (runId != null ? runId : "shared") + "-fundnav";
        String datasetId = datasetWriter.writeDataset("fund_nav", prefix, tsCode, start, end,
                response.getItemsList(), FUND_NAV_DATASET_HEADERS, item -> Arrays.asList(
                        item.getTsCode(),
                        item.getNavDate(),
                        item.getUnitNav(),
                        item.hasAccumNav() ? item.getAccumNav() : null,
                        item.getAdjNav()));
        if (datasetRegistry.isEnabled()) {
            datasetRegistry.registerDataset("fund_nav", tsCode, start, end,
                    FUND_NAV_DATASET_HEADERS, datasetId, response.getItemsCount());
        }
        return support.ok("getOffExchangeAssetDaily", fundNavDatasetData(
                tsCode, start, end, datasetId, response.getItemsCount(), "created", false));
    }

    private Map<String, Object> fundNavDatasetData(String tsCode,
                                                   String startDate,
                                                   String endDate,
                                                   String datasetId,
                                                   int rows,
                                                   String source,
                                                   boolean cacheHit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ts_code", tsCode);
        data.put("start_date", startDate);
        data.put("end_date", endDate);
        data.put("asset_type", "off_exchange_fund");
        data.put("rows", rows);
        data.put("fields", FUND_NAV_DATASET_HEADERS);
        data.put("source", source);
        data.put("cache_hit", cacheHit);
        data.put("dataset_id", support.nvl(datasetId));
        data.put("dataset_ids", datasetId == null || datasetId.isBlank() ? List.of() : List.of(datasetId));
        return data;
    }

    String getEtfAdj(String tsCode, String startDate, String endDate) {
        if (!support.isAdjFactorEnabled()) {
            return support.fail("getEtfAdj", "CAPABILITY_DISABLED",
                    "ETF adj factor is disabled (adjFactorEnabled=false)", Map.of("adjFactorEnabled", false));
        }
        String normalizedTsCode = support.nvl(tsCode).trim();
        String normalizedStart = support.compactDate(startDate);
        String normalizedEnd = support.compactDate(endDate);
        long startMs = support.convertToMsTimestamp(normalizedStart);
        long endMs = support.convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return support.fail("getEtfAdj", "INVALID_ARGUMENT", "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        try {
            ListedAssetAdjFactorResponse response = domesticListedAssetService.getListedAssetAdjFactors(
                    ListedAssetAdjFactorRequest.newBuilder()
                            .setTsCode(normalizedTsCode)
                            .setStartDate(startMs)
                            .setEndDate(endMs)
                            .build());
            if (response.getItemsCount() <= 0) {
                return support.fail("getEtfAdj", "NO_DATA", "No ETF adj factor data found", Map.of(
                        "ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
            }
            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("adj_factor", item.getAdjFactor());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "etf");
            data.put("rows", response.getItemsCount());
            data.put("preview_rows", previewRows);
            return support.ok("getEtfAdj", data);
        } catch (Exception e) {
            return support.fail("getEtfAdj", "TOOL_ERROR", "Error fetching ETF adj factors",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    String getListedAssetShareSize(String tsCode, String startDate, String endDate, String exchange) {
        String normalizedTsCode = support.nvl(tsCode).trim();
        String normalizedStart = support.compactDate(startDate);
        String normalizedEnd = support.compactDate(endDate);
        String normalizedExchange = support.nvl(exchange).trim().toUpperCase();
        long startMs = support.convertToMsTimestamp(normalizedStart);
        long endMs = support.convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return support.fail("getListedAssetShareSize", "INVALID_ARGUMENT",
                    "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        if (!normalizedExchange.isBlank() && !Set.of("SSE", "SZSE", "BSE").contains(normalizedExchange)) {
            return support.fail("getListedAssetShareSize", "INVALID_ARGUMENT",
                    "exchange must be SSE, SZSE, or BSE", Map.of("exchange", support.nvl(exchange)));
        }
        try {
            DomesticEtfShareSizesByTsCodeAndDateRangeResponse response =
                    domesticFundService.getDomesticEtfShareSizesByTsCodeAndDateRange(
                            DomesticEtfShareSizesByTsCodeAndDateRangeRequest.newBuilder()
                                    .setTsCode(normalizedTsCode)
                                    .setStartDateTimestamp(startMs)
                                    .setEndDateTimestamp(endMs)
                                    .build());
            List<DomesticEtfShareSizeItem> items = response.getItemsList();
            if (!normalizedExchange.isBlank()) {
                items = items.stream()
                        .filter(item -> normalizedExchange.equalsIgnoreCase(support.nvl(item.getExchange())))
                        .toList();
            }
            if (items.isEmpty()) {
                return support.fail("getListedAssetShareSize", "NO_DATA", "No ETF share size data found", Map.of(
                        "ts_code", normalizedTsCode, "start_date", normalizedStart,
                        "end_date", normalizedEnd, "exchange", normalizedExchange));
            }
            List<Map<String, Object>> previewRows = new ArrayList<>();
            items.stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("total_share", item.hasTotalShare() ? item.getTotalShare() : null);
                row.put("total_size", item.hasTotalSize() ? item.getTotalSize() : null);
                row.put("exchange", item.getExchange());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "etf");
            if (!normalizedExchange.isBlank()) {
                data.put("exchange", normalizedExchange);
            }
            data.put("rows", items.size());
            data.put("preview_rows", previewRows);
            return support.ok("getListedAssetShareSize", data);
        } catch (Exception e) {
            return support.fail("getListedAssetShareSize", "TOOL_ERROR", "Error fetching ETF share size",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }
}
