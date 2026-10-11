package world.willfrog.agent.tools.market;

import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.CbDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticCbDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticCbDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStkAhByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStkAhByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.StkAhItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 股资产特色数据工具族的唯一生产实现（指标库扩充新工具）。
 *
 * <p>收可转债日线与 AH 比价两类小众资产数据：转债/AH 不属于股票/ETF/指数/场外基金
 * 四大资产类别，塞进既有日线工具会扭曲工具本意，因此单独用这一个特色数据工具承载
 * （仅此一个工具收这两类资产）。</p>
 *
 * <p>与日线工具族同机制：数据集启用时整段序列写入 dataset（方法计算转债日收益/
 * 溢价率、AH 溢价需要整段序列），未启用时返回 20 行预览；数值缺值写 null 不写 0。
 * 返回与落盘行序不保证，需要顺序的使用方自行按 trade_date 排序。</p>
 */
final class MarketDataSpecialAssetTools {

    private static final List<String> CB_DAILY_HEADERS = List.of(
            "ts_code", "trade_date", "pre_close", "open", "high", "low", "close",
            "change", "pct_chg", "vol", "amount", "premium"
    );

    private static final List<String> STK_AH_HEADERS = List.of(
            "ts_code", "hk_code", "trade_date", "close", "hk_close", "pct_chg",
            "hk_pct_chg", "ah_comparison", "ah_premium"
    );

    private final DomesticStockService domesticStockService;
    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;
    private final MarketDataTools support;

    MarketDataSpecialAssetTools(DomesticStockService domesticStockService,
                                DatasetWriter datasetWriter,
                                DatasetRegistry datasetRegistry,
                                MarketDataTools support) {
        this.domesticStockService = domesticStockService;
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.support = support;
    }

    String getSpecialAssetDaily(String tsCode, String assetType, String startDate, String endDate) {
        String tool = "getSpecialAssetDaily";
        String normalizedTsCode = support.nvl(tsCode).trim();
        String normalizedStart = support.compactDate(startDate);
        String normalizedEnd = support.compactDate(endDate);
        long startMs = support.convertToMsTimestamp(normalizedStart);
        long endMs = support.convertToMsTimestamp(normalizedEnd);
        String type = normalizeAssetType(assetType);
        if (type == null) {
            return support.fail(tool, "INVALID_ARGUMENT",
                    "assetType 必须是 cb（可转债）或 ah（AH 比价），当前值不被识别。",
                    Map.of("assetType", support.nvl(assetType), "allowed", List.of("cb", "ah")));
        }
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return support.fail(tool, "INVALID_ARGUMENT",
                    "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        try {
            return "cb".equals(type)
                    ? fetchCbDaily(normalizedTsCode, normalizedStart, normalizedEnd)
                    : fetchStkAh(normalizedTsCode, normalizedStart, normalizedEnd);
        } catch (Exception e) {
            return support.fail(tool, "TOOL_ERROR", "查询失败，请重试或更换工具。如果持续失败，请换一种方式完成任务。",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    /** cb / convertible_bond / cb_daily → cb；ah / stk_ah / stk_ah_comparison → ah；其余（含空）→ null 表示非法。 */
    private String normalizeAssetType(String assetType) {
        String raw = support.nvl(assetType).trim().toLowerCase();
        return switch (raw) {
            case "cb", "convertible_bond", "cb_daily" -> "cb";
            case "ah", "stk_ah", "stk_ah_comparison" -> "ah";
            default -> null;
        };
    }

    /** 复用命中时取数据集实际覆盖日期，meta 为空才回落到请求窗口。 */
    private static String reusedMetaDate(String metaDate, String requestDate) {
        return metaDate == null || metaDate.isBlank() ? requestDate : metaDate;
    }

    private String fetchCbDaily(String tsCode, String startDate, String endDate) {
        String tool = "getSpecialAssetDaily";
        if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
            var reused = datasetRegistry.findReusable("cb_daily", tsCode, startDate, endDate, CB_DAILY_HEADERS);
            if (reused.isPresent()) {
                // 复用命中报数据集实际覆盖区间（超集复用时与请求窗口不同），语义对齐
                // getStockDaily 复用分支：rows 是数据集实际行数，区间必须配套。
                return support.ok(tool, specialAssetData(
                        tsCode,
                        reusedMetaDate(reused.get().getStartDate(), startDate),
                        reusedMetaDate(reused.get().getEndDate(), endDate),
                        "cb", CB_DAILY_HEADERS, reused.get().getDatasetId(), reused.get().getRowCount(),
                        "reused", true, List.of()));
            }
        }
        DomesticCbDailyByTsCodeAndDateRangeResponse response = domesticStockService.getCbDailyByTsCodeAndDateRange(
                DomesticCbDailyByTsCodeAndDateRangeRequest.newBuilder()
                        .setTsCode(tsCode)
                        .setStartDate(support.convertToMsTimestamp(startDate))
                        .setEndDate(support.convertToMsTimestamp(endDate))
                        .build());
        if (response.getItemsCount() <= 0) {
            return support.fail(tool, "NO_DATA",
                    "该可转债在指定日期范围内无日线记录，请检查代码或调整起止日期。",
                    Map.of("ts_code", tsCode, "asset_type", "cb",
                            "start_date", startDate, "end_date", endDate));
        }
        List<CbDailyItem> items = response.getItemsList();
        if (datasetWriter.isEnabled()) {
            String runId = AgentContext.getRunId();
            String prefix = (runId != null ? runId : "shared") + "-cb";
            String datasetId = datasetWriter.writeDataset("cb_daily", prefix, tsCode, startDate, endDate,
                    items, CB_DAILY_HEADERS, item -> Arrays.asList(
                            item.getTsCode(), item.getTradeDate(),
                            item.hasPreClose() ? item.getPreClose() : null,
                            item.hasOpen() ? item.getOpen() : null,
                            item.hasHigh() ? item.getHigh() : null,
                            item.hasLow() ? item.getLow() : null,
                            item.hasClose() ? item.getClose() : null,
                            item.hasChange() ? item.getChange() : null,
                            item.hasPctChg() ? item.getPctChg() : null,
                            item.hasVol() ? item.getVol() : null,
                            item.hasAmount() ? item.getAmount() : null,
                            item.getPremium().isEmpty() ? null : item.getPremium()));
            if (datasetRegistry.isEnabled()) {
                datasetRegistry.registerDataset("cb_daily", tsCode, startDate, endDate,
                        CB_DAILY_HEADERS, datasetId, items.size());
            }
            return support.ok(tool, specialAssetData(tsCode, startDate, endDate, "cb",
                    CB_DAILY_HEADERS, datasetId, items.size(), "created", false, List.of()));
        }
        List<Map<String, Object>> previewRows = new ArrayList<>();
        items.stream().limit(20).forEach(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trade_date", item.getTradeDate());
            row.put("close", item.hasClose() ? item.getClose() : null);
            row.put("premium", item.getPremium().isEmpty() ? null : item.getPremium());
            previewRows.add(row);
        });
        return support.ok(tool, specialAssetData(tsCode, startDate, endDate, "cb",
                CB_DAILY_HEADERS, "", items.size(), "inline", false, previewRows));
    }

    private String fetchStkAh(String tsCode, String startDate, String endDate) {
        String tool = "getSpecialAssetDaily";
        if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
            var reused = datasetRegistry.findReusable("stk_ah", tsCode, startDate, endDate, STK_AH_HEADERS);
            if (reused.isPresent()) {
                return support.ok(tool, specialAssetData(
                        tsCode,
                        reusedMetaDate(reused.get().getStartDate(), startDate),
                        reusedMetaDate(reused.get().getEndDate(), endDate),
                        "ah", STK_AH_HEADERS, reused.get().getDatasetId(), reused.get().getRowCount(),
                        "reused", true, List.of()));
            }
        }
        DomesticStkAhByTsCodeAndDateRangeResponse response = domesticStockService.getStkAhByTsCodeAndDateRange(
                DomesticStkAhByTsCodeAndDateRangeRequest.newBuilder()
                        .setTsCode(tsCode)
                        .setStartDate(support.convertToMsTimestamp(startDate))
                        .setEndDate(support.convertToMsTimestamp(endDate))
                        .build());
        if (response.getItemsCount() <= 0) {
            return support.fail(tool, "NO_DATA",
                    "该股票在指定日期范围内无 AH 比价记录（仅同时登录 A/H 两地的股票有此数据），请检查代码或调整起止日期。",
                    Map.of("ts_code", tsCode, "asset_type", "ah",
                            "start_date", startDate, "end_date", endDate));
        }
        List<StkAhItem> items = response.getItemsList();
        if (datasetWriter.isEnabled()) {
            String runId = AgentContext.getRunId();
            String prefix = (runId != null ? runId : "shared") + "-ah";
            String datasetId = datasetWriter.writeDataset("stk_ah", prefix, tsCode, startDate, endDate,
                    items, STK_AH_HEADERS, item -> Arrays.asList(
                            item.getTsCode(), item.getHkCode(), item.getTradeDate(),
                            item.hasClose() ? item.getClose() : null,
                            item.hasHkClose() ? item.getHkClose() : null,
                            item.hasPctChg() ? item.getPctChg() : null,
                            item.hasHkPctChg() ? item.getHkPctChg() : null,
                            item.hasAhComparison() ? item.getAhComparison() : null,
                            item.hasAhPremium() ? item.getAhPremium() : null));
            if (datasetRegistry.isEnabled()) {
                datasetRegistry.registerDataset("stk_ah", tsCode, startDate, endDate,
                        STK_AH_HEADERS, datasetId, items.size());
            }
            return support.ok(tool, specialAssetData(tsCode, startDate, endDate, "ah",
                    STK_AH_HEADERS, datasetId, items.size(), "created", false, List.of()));
        }
        List<Map<String, Object>> previewRows = new ArrayList<>();
        items.stream().limit(20).forEach(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trade_date", item.getTradeDate());
            row.put("close", item.hasClose() ? item.getClose() : null);
            row.put("hk_close", item.hasHkClose() ? item.getHkClose() : null);
            row.put("ah_premium", item.hasAhPremium() ? item.getAhPremium() : null);
            previewRows.add(row);
        });
        return support.ok(tool, specialAssetData(tsCode, startDate, endDate, "ah",
                STK_AH_HEADERS, "", items.size(), "inline", false, previewRows));
    }

    private Map<String, Object> specialAssetData(String tsCode,
                                                 String startDate,
                                                 String endDate,
                                                 String assetType,
                                                 List<String> fields,
                                                 String datasetId,
                                                 int rows,
                                                 String source,
                                                 boolean cacheHit,
                                                 List<Map<String, Object>> previewRows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ts_code", tsCode);
        data.put("asset_type", assetType);
        data.put("start_date", startDate);
        data.put("end_date", endDate);
        data.put("rows", rows);
        data.put("fields", fields);
        data.put("source", source);
        data.put("cache_hit", cacheHit);
        data.put("dataset_id", support.nvl(datasetId));
        data.put("dataset_ids", datasetId == null || datasetId.isBlank() ? List.of() : List.of(datasetId));
        if (previewRows != null && !previewRows.isEmpty()) {
            data.put("preview_rows", previewRows);
        }
        return data;
    }
}
