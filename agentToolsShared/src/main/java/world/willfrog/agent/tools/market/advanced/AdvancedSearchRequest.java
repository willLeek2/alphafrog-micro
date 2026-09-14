package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Data
public class AdvancedSearchRequest {

    private String toolName;
    private String assetType;
    private String name;
    private List<AdvancedSearchCondition> conditions = new ArrayList<>();
    private Map<String, Object> canonicalQuery = new LinkedHashMap<>();

    public static boolean isAdvancedMap(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        return "advanced".equalsIgnoreCase(str(params.get("mode")));
    }

    public static AdvancedSearchRequest from(String toolName, Map<String, Object> params, ObjectMapper objectMapper) {
        Map<String, Object> root = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        if (!"advanced".equalsIgnoreCase(str(root.get("mode")))) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "mode=advanced is required.");
        }

        Object advancedQueryObject = root.get("advancedQuery");
        if (advancedQueryObject instanceof String raw && raw.trim().startsWith("{")) {
            try {
                advancedQueryObject = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new AdvancedSearchException("INVALID_ARGUMENT", "advancedQuery JSON is invalid.");
            }
        }
        Map<String, Object> advancedQuery = advancedQueryObject instanceof Map<?, ?> rawMap
                ? stringifyKeys(rawMap)
                : new LinkedHashMap<>();

        AdvancedSearchRequest request = new AdvancedSearchRequest();
        request.toolName = toolName;
        request.assetType = normalizeAssetType(firstString(advancedQuery, "asset_type", "assetType", "assetTypes", "asset_types"));
        request.name = firstString(advancedQuery, "name", "keyword");
        if (request.name.isBlank()) {
            request.name = firstString(root, "name", "keyword");
        }

        Object conditionsRaw = advancedQuery.get("conditions");
        if (conditionsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                request.conditions.add(AdvancedSearchCondition.from(i, list.get(i)));
            }
        } else if (conditionsRaw != null) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "advancedQuery.conditions must be an array.");
        }
        if (request.name.isBlank() && request.conditions.isEmpty()) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "advanced query requires name or conditions.");
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("name", request.name);
        List<Map<String, Object>> conditionMaps = new ArrayList<>();
        for (AdvancedSearchCondition condition : request.conditions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", condition.getType());
            row.put("index_code", condition.getIndexCode());
            row.put("stock_code", condition.getStockCode());
            row.put("industry_code", condition.getIndustryCode());
            row.put("start_date", condition.getStartDate());
            row.put("end_date", condition.getEndDate());
            row.put("min_weight", condition.getMinWeight());
            row.put("max_weight", condition.getMaxWeight());
            conditionMaps.add(row);
        }
        canonical.put("conditions", conditionMaps);
        request.canonicalQuery = canonical;
        return request;
    }

    private static Map<String, Object> stringifyKeys(Map<?, ?> rawMap) {
        Map<String, Object> out = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String firstString(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).trim().isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalizeAssetType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains(",")) {
            value = value.split(",", 2)[0].trim();
        }
        return switch (value) {
            case "stock", "etf", "index" -> value;
            default -> value;
        };
    }
}
