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
        String mode = str(params.get("mode"));
        if ("advanced".equalsIgnoreCase(mode)) {
            return true;
        }
        Object query = params.get("query");
        return query instanceof Map<?, ?> map && "advanced".equalsIgnoreCase(str(map.get("mode")));
    }

    public static AdvancedSearchRequest from(String toolName, Map<String, Object> params, ObjectMapper objectMapper) {
        Map<String, Object> root = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        Object queryObject = root.get("query");
        if (queryObject instanceof String raw && raw.trim().startsWith("{")) {
            try {
                queryObject = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new AdvancedSearchException("INVALID_ARGUMENT", "query JSON is invalid.");
            }
        }
        Map<String, Object> query = queryObject instanceof Map<?, ?> rawMap ? stringifyKeys(rawMap) : new LinkedHashMap<>();
        if ("advanced".equalsIgnoreCase(str(query.get("mode")))) {
            root.putAll(query);
            Object nested = query.get("query");
            query = nested instanceof Map<?, ?> nestedMap ? stringifyKeys(nestedMap) : query;
        } else if (query.containsKey("conditions") || query.containsKey("name")) {
            root.put("mode", "advanced");
        }

        if (!"advanced".equalsIgnoreCase(str(root.get("mode")))) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "mode=advanced is required.");
        }

        AdvancedSearchRequest request = new AdvancedSearchRequest();
        request.toolName = toolName;
        request.assetType = normalizeAssetType(firstString(root, "asset_type", "assetType", "assetTypes", "asset_types"));
        request.name = firstString(query, "name", "keyword");
        if (request.name.isBlank()) {
            request.name = firstString(root, "name", "keyword");
        }

        Object conditionsRaw = query.containsKey("conditions") ? query.get("conditions") : root.get("conditions");
        if (conditionsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                request.conditions.add(AdvancedSearchCondition.from(i, list.get(i)));
            }
        } else if (conditionsRaw != null) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "query.conditions must be an array.");
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
