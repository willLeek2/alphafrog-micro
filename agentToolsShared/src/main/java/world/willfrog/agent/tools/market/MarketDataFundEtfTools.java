package world.willfrog.agent.tools.market;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 场外基金净值与 ETF 辅助数据工具族的唯一生产实现。 */
final class MarketDataFundEtfTools {

    private final DomesticFundService domesticFundService;
    private final DomesticListedAssetService domesticListedAssetService;
    private final MarketDataTools support;

    MarketDataFundEtfTools(DomesticFundService domesticFundService,
                           DomesticListedAssetService domesticListedAssetService,
                           MarketDataTools support) {
        this.domesticFundService = domesticFundService;
        this.domesticListedAssetService = domesticListedAssetService;
        this.support = support;
    }

    String getOffExchangeAssetDaily(String tsCode, String startDate, String endDate) {
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
            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nav_date", item.getNavDate());
                row.put("unit_nav", item.getUnitNav());
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
            return support.ok("getOffExchangeAssetDaily", data);
        } catch (Exception e) {
            return support.fail("getOffExchangeAssetDaily", "TOOL_ERROR", "Error fetching fund nav data",
                    Map.of("message", support.nvl(e.getMessage())));
        }
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
