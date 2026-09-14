package world.willfrog.agent.tools.dataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces conservative pandas loading hints from a dataset column list. */
public final class DatasetSchemaHintResolver {

    private DatasetSchemaHintResolver() {
    }

    public static SchemaHints resolve(List<String> columns) {
        List<String> normalized = columns == null ? List.of() : columns.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, String> dtypes = new LinkedHashMap<>();
        for (String column : normalized) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (lower.equals("ts_code") || lower.endsWith("_code") || lower.equals("symbol")) {
                dtypes.put(column, "category");
            } else if (lower.equals("trade_date") || lower.endsWith("_date")) {
                dtypes.put(column, "Int64");
            } else if (isFloatingMetric(lower)) {
                dtypes.put(column, "float64");
            }
        }

        Map<String, List<String>> profiles = new LinkedHashMap<>();
        List<String> priceVolume = select(normalized,
                "ts_code", "trade_date", "open", "high", "low", "close", "vol", "volume", "amount");
        if (!priceVolume.isEmpty()) {
            profiles.put("price_volume", priceVolume);
        }
        List<String> ohlc = select(normalized,
                "ts_code", "trade_date", "open", "high", "low", "close");
        if (ohlc.size() >= 4) {
            profiles.put("ohlc", ohlc);
        }
        return new SchemaHints(normalized, Map.copyOf(dtypes), Map.copyOf(profiles));
    }

    private static boolean isFloatingMetric(String column) {
        return column.equals("open") || column.equals("high") || column.equals("low")
                || column.equals("close") || column.equals("pre_close") || column.equals("change")
                || column.equals("pct_chg") || column.equals("vol") || column.equals("volume")
                || column.equals("amount") || column.endsWith("_return") || column.endsWith("_ratio");
    }

    private static List<String> select(List<String> columns, String... preferred) {
        List<String> result = new ArrayList<>();
        for (String wanted : preferred) {
            columns.stream()
                    .filter(column -> column.equalsIgnoreCase(wanted))
                    .findFirst()
                    .ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public record SchemaHints(
            List<String> recommendedUsecols,
            Map<String, String> recommendedDtype,
            Map<String, List<String>> readProfiles
    ) {
    }
}
