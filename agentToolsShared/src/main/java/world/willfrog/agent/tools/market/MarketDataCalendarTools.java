package world.willfrog.agent.tools.market;

import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** A 股交易日查询工具族的唯一生产实现。 */
final class MarketDataCalendarTools {

    private final DomesticIndexService domesticIndexService;
    private final MarketDataTools support;

    MarketDataCalendarTools(DomesticIndexService domesticIndexService, MarketDataTools support) {
        this.domesticIndexService = domesticIndexService;
        this.support = support;
    }

    String getTradingDaysSummary(String startDate, String endDate, String exchange) {
        String normalizedStart = support.normalizeStrictDate(startDate);
        String normalizedEnd = support.normalizeStrictDate(endDate);
        long startMs = support.convertStrictDateToMsTimestamp(normalizedStart);
        long endMs = support.convertStrictDateToMsTimestamp(normalizedEnd);
        String normalizedExchange = support.normalizeExchange(exchange);
        if (startMs <= 0 || endMs <= 0 || startMs > endMs) {
            return support.fail("getTradingDaysSummary", "INVALID_ARGUMENT",
                    "Invalid date range, please use YYYYMMDD and ensure startDate <= endDate.", Map.of(
                            "exchange", normalizedExchange,
                            "start_date", support.nvl(startDate),
                            "end_date", support.nvl(endDate)));
        }
        try {
            DomesticTradingDaysCountResponse response = domesticIndexService.getTradingDaysCountByDateRange(
                    DomesticTradingDaysCountRequest.newBuilder()
                            .setExchange(normalizedExchange)
                            .setStartDate(startMs)
                            .setEndDate(endMs)
                            .build());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exchange", normalizedExchange);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("trading_days_count", response.getTradingDaysCount());
            data.put("first_trading_date", support.msTimestampToCompactDate(response.getFirstTradingDate()));
            data.put("last_trading_date", support.msTimestampToCompactDate(response.getLastTradingDate()));
            data.put("calendar_source", "alphafrog_trade_calendar");
            return support.ok("getTradingDaysSummary", data);
        } catch (Exception e) {
            return support.fail("getTradingDaysSummary", "TOOL_ERROR", "Error fetching trading day summary",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    String isTradingDay(String date, String exchange) {
        int maxItems = support.resolveMaxParallelCalendarQueries();
        List<String> dates = support.parseBatchValues(date);
        String limitError = support.batchLimitFailureIfExceeded("isTradingDay", "date", dates, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (dates.size() > 1) {
            return batchIsTradingDay(dates, exchange);
        }
        return isTradingDaySingle(dates.isEmpty() ? date : dates.get(0), exchange);
    }

    private String isTradingDaySingle(String date, String exchange) {
        String normalizedDate = support.normalizeStrictDate(date);
        long dateMs = support.convertStrictDateToMsTimestamp(normalizedDate);
        String normalizedExchange = support.normalizeExchange(exchange);
        if (dateMs <= 0) {
            return support.fail("isTradingDay", "INVALID_ARGUMENT", "Invalid date, please use YYYYMMDD.", Map.of(
                    "exchange", normalizedExchange, "date", support.nvl(date)));
        }
        try {
            DomesticTradingDayStatusResponse response = domesticIndexService.isTradingDay(
                    DomesticTradingDayStatusRequest.newBuilder()
                            .setExchange(normalizedExchange)
                            .setDate(dateMs)
                            .build());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exchange", normalizedExchange);
            data.put("date", normalizedDate);
            data.put("is_trading_day", response.getTradingDay());
            data.put("calendar_record_found", response.getCalendarRecordFound());
            data.put("calendar_source", "alphafrog_trade_calendar");
            return support.ok("isTradingDay", data);
        } catch (Exception e) {
            return support.fail("isTradingDay", "TOOL_ERROR", "Error checking trading day",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    private String batchIsTradingDay(List<String> dates, String exchange) {
        String normalizedExchange = support.normalizeExchange(exchange);
        List<CompletableFuture<Map<String, Object>>> futures = dates.stream()
                .map(date -> support.supplyAsyncWithAgentContext(() -> {
                    Map<String, Object> payload = support.readJsonMap(isTradingDaySingle(date, normalizedExchange));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", date);
                    row.put("ok", Boolean.TRUE.equals(payload.get("ok")));
                    row.put("data", support.readNestedMap(payload.get("data")));
                    row.put("error", support.readNestedMap(payload.get("error")));
                    return row;
                }))
                .toList();
        List<Map<String, Object>> results = futures.stream().map(CompletableFuture::join).toList();
        long successCount = results.stream().filter(item -> Boolean.TRUE.equals(item.get("ok"))).count();
        return support.ok("isTradingDay", Map.of(
                "mode", "batch",
                "dates", dates,
                "exchange", normalizedExchange,
                "results", results,
                "success_count", successCount,
                "failure_count", Math.max(0, results.size() - successCount)));
    }
}
