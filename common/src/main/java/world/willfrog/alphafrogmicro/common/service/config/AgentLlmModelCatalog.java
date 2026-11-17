package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在一份 agent-llm 文档里按模型编号增加、修改、删除。
 * 可启动名单是根上的 models 数组。端点里的 models 对象只保存显示名、baseRate 等登记。
 * 模型编号原样作为键，斜杠和开头的 ~ 不需要另做转义。
 */
public final class AgentLlmModelCatalog {

    public enum Change {
        CREATE, UPDATE, DELETE
    }

    public record Placement(String endpoint,
                            String displayName,
                            Double baseRate,
                            List<String> validProviders,
                            List<String> features,
                            Integer maxTokens) {
    }

    public record ModelRecord(String modelId, boolean startable, List<Placement> placements) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modelId", modelId);
            map.put("startable", startable);
            List<Map<String, Object>> places = new ArrayList<>();
            for (Placement placement : placements) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("endpoint", placement.endpoint());
                one.put("displayName", placement.displayName());
                if (placement.baseRate() != null) {
                    one.put("baseRate", placement.baseRate());
                }
                if (placement.validProviders() != null) {
                    one.put("validProviders", placement.validProviders());
                }
                if (placement.features() != null) {
                    one.put("features", placement.features());
                }
                if (placement.maxTokens() != null) {
                    one.put("maxTokens", placement.maxTokens());
                }
                places.add(one);
            }
            map.put("placements", places);
            return map;
        }
    }

    private AgentLlmModelCatalog() {
    }

    public static List<ModelRecord> list(JsonNode document) {
        ObjectNode doc = requireObject(document);
        List<ModelRecord> records = new ArrayList<>();
        for (String modelId : orderedIds(doc)) {
            records.add(recordOf(doc, modelId));
        }
        return records;
    }

    public static ModelRecord find(JsonNode document, String modelId) {
        ObjectNode doc = requireObject(document);
        String id = requireModelId(modelId);
        if (!exists(doc, id)) {
            return null;
        }
        return recordOf(doc, id);
    }

    public static List<Placement> readPlacements(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("placements 至少要有一个端点");
        }
        List<Placement> placements = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("每个落点必须是对象");
            }
            String endpoint = requiredText(item, "endpoint");
            if (!seen.add(endpoint)) {
                throw new IllegalArgumentException("端点重复: " + endpoint);
            }
            String displayName = requiredText(item, "displayName");
            Double baseRate = requiredBaseRate(item.get("baseRate"));
            List<String> providers = readProviders(item.get("validProviders"));
            List<String> features = readFeatures(item.get("features"));
            Integer maxTokens = readMaxTokens(item.get("maxTokens"));
            placements.add(new Placement(endpoint, displayName, baseRate, providers, features, maxTokens));
        }
        return placements;
    }

    public static ObjectNode create(JsonNode document, String modelId, List<Placement> placements) {
        ObjectNode doc = copy(document);
        String id = requireModelId(modelId);
        requirePlacements(placements);
        if (exists(doc, id)) {
            throw new ConfigConflictException("模型已存在: " + id);
        }
        for (Placement placement : placements) {
            writePlacement(doc, id, placement);
        }
        appendAllowList(doc, id);
        return doc;
    }

    public static ObjectNode update(JsonNode document, String modelId, List<Placement> placements) {
        ObjectNode doc = copy(document);
        String id = requireModelId(modelId);
        requirePlacements(placements);
        if (!exists(doc, id)) {
            throw new ConfigNotFoundException("模型不存在: " + id);
        }
        removeFromAllEndpoints(doc, id);
        for (Placement placement : placements) {
            writePlacement(doc, id, placement);
        }
        appendAllowList(doc, id);
        return doc;
    }

    public static ObjectNode delete(JsonNode document, String modelId) {
        ObjectNode doc = copy(document);
        String id = requireModelId(modelId);
        if (!exists(doc, id)) {
            throw new ConfigNotFoundException("模型不存在: " + id);
        }
        String reference = findExternalReference(doc, id);
        if (reference != null) {
            throw new IllegalArgumentException("模型编号仍被配置引用，不能删除: " + id + " 位置 " + reference);
        }
        removeFromAllowList(doc, id);
        removeFromAllEndpoints(doc, id);
        return doc;
    }

    private static ModelRecord recordOf(ObjectNode doc, String modelId) {
        return new ModelRecord(modelId, inAllowList(doc, modelId), placementsOf(doc, modelId));
    }

    private static List<String> orderedIds(ObjectNode doc) {
        Set<String> ids = new LinkedHashSet<>();
        ArrayNode models = modelsArray(doc, false);
        if (models != null) {
            for (JsonNode item : models) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    ids.add(item.asText());
                }
            }
        }
        JsonNode endpoints = doc.get("endpoints");
        if (endpoints != null && endpoints.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = endpoints.fields();
            while (fields.hasNext()) {
                JsonNode endpointModels = fields.next().getValue().path("models");
                if (!endpointModels.isObject()) {
                    continue;
                }
                endpointModels.fieldNames().forEachRemaining(ids::add);
            }
        }
        return List.copyOf(ids);
    }

    private static boolean exists(ObjectNode doc, String modelId) {
        return inAllowList(doc, modelId) || !placementsOf(doc, modelId).isEmpty();
    }

    private static boolean inAllowList(ObjectNode doc, String modelId) {
        ArrayNode models = modelsArray(doc, false);
        if (models == null) {
            return false;
        }
        for (JsonNode item : models) {
            if (item != null && item.isTextual() && modelId.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<Placement> placementsOf(ObjectNode doc, String modelId) {
        List<Placement> placements = new ArrayList<>();
        JsonNode endpoints = doc.get("endpoints");
        if (endpoints == null || !endpoints.isObject()) {
            return placements;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = endpoints.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode models = field.getValue().path("models");
            if (!models.isObject() || !models.has(modelId)) {
                continue;
            }
            placements.add(readStoredPlacement(field.getKey(), models.get(modelId)));
        }
        return placements;
    }

    private static Placement readStoredPlacement(String endpoint, JsonNode meta) {
        if (meta == null || !meta.isObject()) {
            return new Placement(endpoint, null, null, null, null, null);
        }
        String displayName = meta.path("displayName").isTextual() ? meta.get("displayName").asText() : null;
        Double baseRate = meta.path("baseRate").isNumber() ? meta.get("baseRate").doubleValue() : null;
        List<String> providers = readStoredProviders(meta.get("validProviders"));
        List<String> features = readStoredProviders(meta.get("features"));
        Integer maxTokens = meta.path("maxTokens").isIntegralNumber() ? meta.get("maxTokens").intValue() : null;
        return new Placement(endpoint, displayName, baseRate, providers, features, maxTokens);
    }

    private static List<String> readStoredProviders(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> providers = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                providers.add(item.asText());
            }
        }
        return providers;
    }

    private static void writePlacement(ObjectNode doc, String modelId, Placement placement) {
        ObjectNode models = endpointModels(doc, placement.endpoint(), true);
        ObjectNode meta = models.objectNode();
        meta.put("displayName", placement.displayName());
        meta.put("baseRate", placement.baseRate());
        if (placement.validProviders() != null) {
            ArrayNode providers = meta.putArray("validProviders");
            placement.validProviders().forEach(providers::add);
        }
        if (placement.features() != null) {
            ArrayNode features = meta.putArray("features");
            placement.features().forEach(features::add);
        }
        if (placement.maxTokens() != null) {
            meta.put("maxTokens", placement.maxTokens());
        }
        models.set(modelId, meta);
    }

    private static void appendAllowList(ObjectNode doc, String modelId) {
        if (inAllowList(doc, modelId)) {
            return;
        }
        modelsArray(doc, true).add(modelId);
    }

    private static void removeFromAllowList(ObjectNode doc, String modelId) {
        ArrayNode models = modelsArray(doc, false);
        if (models == null) {
            return;
        }
        for (int i = models.size() - 1; i >= 0; i--) {
            JsonNode item = models.get(i);
            if (item != null && item.isTextual() && modelId.equals(item.asText())) {
                models.remove(i);
            }
        }
    }

    private static void removeFromAllEndpoints(ObjectNode doc, String modelId) {
        JsonNode endpoints = doc.get("endpoints");
        if (endpoints == null || !endpoints.isObject()) {
            return;
        }
        Iterator<JsonNode> values = endpoints.elements();
        while (values.hasNext()) {
            JsonNode endpoint = values.next();
            if (endpoint != null && endpoint.isObject()) {
                JsonNode models = endpoint.get("models");
                if (models != null && models.isObject()) {
                    ((ObjectNode) models).remove(modelId);
                }
            }
        }
    }

    private static ObjectNode endpointModels(ObjectNode doc, String endpoint, boolean create) {
        JsonNode endpoints = doc.get("endpoints");
        if (endpoints == null || !endpoints.isObject()) {
            throw new IllegalArgumentException("配置里没有端点表 endpoints");
        }
        JsonNode endpointNode = endpoints.get(endpoint);
        if (endpointNode == null || !endpointNode.isObject()) {
            throw new IllegalArgumentException("端点不存在: " + endpoint);
        }
        JsonNode models = endpointNode.get("models");
        if (models == null || models.isNull()) {
            if (!create) {
                return null;
            }
            ObjectNode created = ((ObjectNode) endpointNode).objectNode();
            ((ObjectNode) endpointNode).set("models", created);
            return created;
        }
        if (!models.isObject()) {
            throw new IllegalArgumentException("端点的 models 必须是对象: " + endpoint);
        }
        return (ObjectNode) models;
    }

    private static ArrayNode modelsArray(ObjectNode doc, boolean create) {
        JsonNode models = doc.get("models");
        if (models == null || models.isNull()) {
            if (!create) {
                return null;
            }
            ArrayNode created = doc.arrayNode();
            doc.set("models", created);
            return created;
        }
        if (!models.isArray()) {
            throw new IllegalArgumentException("models 必须是数组");
        }
        return (ArrayNode) models;
    }

    private static String findExternalReference(ObjectNode doc, String modelId) {
        return findExternalReference(doc, modelId, "", false);
    }

    private static String findExternalReference(JsonNode node, String modelId, String path, boolean skip) {
        if (skip || node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return modelId.equals(node.asText()) ? path : null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean skipChild = (path.isEmpty() && "models".equals(field.getKey()))
                        || (isEndpointObject(path) && "models".equals(field.getKey()));
                String found = findExternalReference(
                        field.getValue(), modelId, pointer(path, field.getKey()), skipChild);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String found = findExternalReference(node.get(i), modelId, path + "/" + i, false);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isEndpointObject(String path) {
        if (!path.startsWith("/endpoints/")) {
            return false;
        }
        return path.indexOf('/', "/endpoints/".length()) < 0;
    }

    private static String pointer(String path, String key) {
        return path + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static void requirePlacements(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            throw new IllegalArgumentException("placements 至少要有一个端点");
        }
    }

    private static String requireModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (!modelId.equals(modelId.trim())) {
            throw new IllegalArgumentException("modelId 不能带首尾空白");
        }
        return modelId;
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.asText();
    }

    private static Double requiredBaseRate(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new IllegalArgumentException("baseRate 必须大于 0");
        }
        double rate = node.doubleValue();
        if (!Double.isFinite(rate) || rate <= 0) {
            throw new IllegalArgumentException("baseRate 必须大于 0");
        }
        return rate;
    }

    private static List<String> readProviders(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("validProviders 必须是非空数组");
        }
        List<String> providers = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("validProviders 里的每一项都必须是非空字符串");
            }
            providers.add(item.asText());
        }
        return providers;
    }

    private static List<String> readFeatures(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("features 必须是字符串数组");
        }
        List<String> features = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("features 里的每一项都必须是非空字符串");
            }
            features.add(item.asText());
        }
        return features;
    }

    private static Integer readMaxTokens(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.intValue() <= 0) {
            throw new IllegalArgumentException("maxTokens 必须是大于 0 的整数");
        }
        return node.intValue();
    }

    private static ObjectNode requireObject(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("agent-llm 配置必须是 JSON 对象");
        }
        return (ObjectNode) document;
    }

    private static ObjectNode copy(JsonNode document) {
        return requireObject(document).deepCopy();
    }
}
