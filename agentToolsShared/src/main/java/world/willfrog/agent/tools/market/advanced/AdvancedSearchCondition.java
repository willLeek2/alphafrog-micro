package world.willfrog.agent.tools.market.advanced;

import lombok.Data;

import java.util.Locale;
import java.util.Map;

@Data
public class AdvancedSearchCondition {

    public static final long MIN_DATE = 19000101L;
    public static final long MAX_DATE = 20991231L;

    private int index;
    private String type;
    private String indexCode;
    private String stockCode;
    private String startDate;
    private String endDate;
    private Long startDateValue;
    private Long endDateValue;
    private Double minWeight;
    private Double maxWeight;

    @SuppressWarnings("unchecked")
    public static AdvancedSearchCondition from(int index, Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "Each condition must be an object.");
        }
        AdvancedSearchCondition condition = new AdvancedSearchCondition();
        condition.index = index;
        condition.type = normalizeString(source.get("type")).toLowerCase(Locale.ROOT);
        condition.indexCode = normalizeString(first(source, "index_code", "indexCode"));
        condition.stockCode = normalizeString(first(source, "stock_code", "stockCode"));
        condition.startDate = normalizeDateBoundary(first(source, "start_date", "startDate"), "start_date");
        condition.endDate = normalizeDateBoundary(first(source, "end_date", "endDate"), "end_date");
        condition.startDateValue = dateValue(condition.startDate);
        condition.endDateValue = dateValue(condition.endDate);
        condition.minWeight = doubleOrNull(first(source, "min_weight", "minWeight"));
        condition.maxWeight = doubleOrNull(first(source, "max_weight", "maxWeight"));
        if (condition.type.isBlank()) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "condition.type is required.");
        }
        if (condition.effectiveStartDate() > condition.effectiveEndDate()) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "start_date must be <= end_date.");
        }
        if (condition.minWeight != null && condition.maxWeight != null
                && condition.minWeight > condition.maxWeight) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "min_weight must be <= max_weight.");
        }
        return condition;
    }

    public boolean hasWeightFilter() {
        return minWeight != null || maxWeight != null;
    }

    public boolean matchesWeight(double weight) {
        if (minWeight != null && weight < minWeight) {
            return false;
        }
        return maxWeight == null || weight <= maxWeight;
    }

    private static Object first(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    private static String normalizeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalizeDateBoundary(Object value, String field) {
        String raw = normalizeString(value);
        if (raw.isBlank()) {
            return "start_date".equals(field) ? "NONE" : "NONE";
        }
        if ("NONE".equals(raw)) {
            return raw;
        }
        if (!raw.matches("\\d{8}")) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", field + " must be YYYYMMDD or NONE.");
        }
        try {
            java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (java.time.DateTimeException e) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", field + " must be a valid YYYYMMDD date.");
        }
        return raw;
    }

    private static Long dateValue(String value) {
        if ("NONE".equals(value)) {
            return null;
        }
        return Long.parseLong(value);
    }

    public long effectiveStartDate() {
        return startDateValue == null ? MIN_DATE : startDateValue;
    }

    public long effectiveEndDate() {
        return endDateValue == null ? MAX_DATE : endDateValue;
    }

    private static Double doubleOrNull(Object value) {
        if (value == null || String.valueOf(value).trim().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "weight boundaries must be numeric.");
        }
    }
}
