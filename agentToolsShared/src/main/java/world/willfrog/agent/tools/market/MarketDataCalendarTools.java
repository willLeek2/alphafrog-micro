package world.willfrog.agent.tools.market;

import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        return getTradingDaysSummaryPrompt(startDate, endDate, exchange).text();
    }

    PromptText getTradingDaysSummaryPrompt(String startDate, String endDate, String exchange) {
        String normalizedStart = support.normalizeStrictDate(startDate);
        String normalizedEnd = support.normalizeStrictDate(endDate);
        long startMs = support.convertStrictDateToMsTimestamp(normalizedStart);
        long endMs = support.convertStrictDateToMsTimestamp(normalizedEnd);
        String normalizedExchange = support.normalizeExchange(exchange);
        if (startMs <= 0 || endMs <= 0 || startMs > endMs) {
            return support.failPrompt("getTradingDaysSummary", "INVALID_ARGUMENT",
                    support.policy("marketCalendarRangeAdvice"), Map.of(
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
            // 交易日值统一用毫秒时间戳（与行情数据集里的 trade_date 对齐）；区间无交易日时为 0。
            data.put("first_trading_date", response.getFirstTradingDate());
            data.put("last_trading_date", response.getLastTradingDate());
            data.put("calendar_source", "alphafrog_trade_calendar");
            return support.okPrompt("getTradingDaysSummary", data);
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            return support.failPrompt("getTradingDaysSummary", "TOOL_ERROR", "Error fetching trading day summary",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    String isTradingDay(String date, String exchange) {
        return isTradingDayPrompt(date, exchange).text();
    }

    PromptText isTradingDayPrompt(String date, String exchange) {
        int maxItems = support.resolveMaxParallelCalendarQueries();
        List<String> dates = support.parseBatchValues(date);
        PromptText limitError = support.batchLimitFailureIfExceededPrompt("isTradingDay", "date", dates, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (dates.size() > 1) {
            return batchIsTradingDay(dates, exchange);
        }
        return isTradingDaySingle(dates.isEmpty() ? date : dates.get(0), exchange);
    }

    private PromptText isTradingDaySingle(String date, String exchange) {
        String normalizedDate = support.normalizeStrictDate(date);
        long dateMs = support.convertStrictDateToMsTimestamp(normalizedDate);
        String normalizedExchange = support.normalizeExchange(exchange);
        if (dateMs <= 0) {
            return support.failPrompt("isTradingDay", "INVALID_ARGUMENT", support.policy("marketCalendarDateAdvice"), Map.of(
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
            return support.okPrompt("isTradingDay", data);
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            return support.failPrompt("isTradingDay", "TOOL_ERROR", "Error checking trading day",
                    Map.of("message", support.nvl(e.getMessage())));
        }
    }

    private PromptText batchIsTradingDay(List<String> dates, String exchange) {
        String normalizedExchange = support.normalizeExchange(exchange);
        List<CompletableFuture<CalendarRow>> futures = dates.stream()
                .map(date -> support.supplyAsyncWithAgentContext(() -> {
                    PromptText response = isTradingDaySingle(date, normalizedExchange);
                    Map<String, Object> payload = support.readJsonMap(response.text());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", date);
                    row.put("ok", Boolean.TRUE.equals(payload.get("ok")));
                    row.put("data", support.readNestedMap(payload.get("data")));
                    row.put("error", support.readNestedMap(payload.get("error")));
                    return new CalendarRow(row, response.sources());
                }))
                .toList();
        List<CalendarRow> completed = futures.stream().map(CompletableFuture::join).toList();
        List<Map<String, Object>> results = completed.stream().map(CalendarRow::row).toList();
        long successCount = results.stream().filter(item -> Boolean.TRUE.equals(item.get("ok"))).count();
        PromptText result = support.okPrompt("isTradingDay", Map.of(
                "mode", "batch",
                "dates", dates,
                "exchange", normalizedExchange,
                "results", results,
                "success_count", successCount,
                "failure_count", Math.max(0, results.size() - successCount)));
        List<PromptVersionSource> sources = new ArrayList<>(result.sources());
        completed.forEach(row -> sources.addAll(row.sources()));
        return new PromptText(result.text(), List.copyOf(new LinkedHashSet<>(sources)));
    }

    private record CalendarRow(Map<String, Object> row, List<PromptVersionSource> sources) { }
}
