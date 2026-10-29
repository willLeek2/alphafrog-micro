package world.willfrog.alphafrogmicro.common.racing.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 不可变来源快照。调用方明确归类，发送器只核对实际请求的位置。 */
public record ModelInputSources(List<ModelInputSource> parts) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ModelInputSources {
        parts = List.copyOf(parts);
        if (parts.stream().map(ModelInputSource::jsonPointer).distinct().count() != parts.size()) {
            throw new IllegalArgumentException("同一请求位置不能重复登记来源");
        }
    }

    public void validate(String finalJson) {
        JsonNode body;
        try {
            body = JSON.readTree(finalJson);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw blocked("最终模型输入不是有效JSON");
        }
        if (body == null || !body.isObject()) {
            throw blocked("最终模型输入必须为JSON对象");
        }
        Map<String, ModelInputSource> locations = new LinkedHashMap<>();
        for (ModelInputSource source : parts) {
            if (!validPointer(source.jsonPointer())) {
                throw blocked("指令来源位置无效");
            }
            JsonNode actual;
            try {
                actual = body.at(source.jsonPointer());
            } catch (IllegalArgumentException failure) {
                throw blocked("指令来源位置无效");
            }
            if (actual.isMissingNode() || actual.isNull()) {
                throw blocked("实际输入已不存在来源位置：" + source.jsonPointer());
            }
            locations.put(source.jsonPointer(), source);
        }
        // OpenAI兼容接口与DashScope均在这两个已知位置保存消息。
        String prefix = body.has("messages") ? "/messages" : "/input/messages";
        JsonNode messages = body.at(prefix);
        boolean queryInput = !body.has("messages") && !body.has("input") && body.has("query");
        if (queryInput) {
            if (!body.path("query").isTextual() || body.path("query").asText().isBlank()) {
                throw blocked("搜索输入需要非空查询正文");
            }
            Set<String> allowedQuery = new LinkedHashSet<>();
            allowedQuery.add("/query");
            require(locations, "/query", false);
            JsonNode schema = body.get("outputSchema");
            if (schema != null && !schema.isNull()) {
                validateStructure(locations, allowedQuery, "/outputSchema", schema);
            }
            if (body.has("tools") || body.has("response_format")
                    || !allowedQuery.containsAll(locations.keySet())) {
                throw blocked("搜索输入来源必须对应查询或输出结构");
            }
            return;
        }
        if (!messages.isArray() || messages.isEmpty()) {
            throw blocked("模型输入缺少消息列表");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            String position = prefix + "/" + i;
            allowed.add(position);
            String role = messages.get(i).path("role").asText();
            require(locations, position, role.equals("system") || role.equals("developer"));
        }
        JsonNode tools = body.get("tools");
        if (tools != null && !tools.isNull()) {
            if (!tools.isArray()) {
                throw blocked("工具定义必须为列表");
            }
            for (int i = 0; i < tools.size(); i++) {
                validateStructure(locations, allowed, "/tools/" + i, tools.get(i));
            }
        }
        JsonNode output = body.get("response_format");
        if (output != null && !output.isNull()) {
            validateStructure(locations, allowed, "/response_format", output);
        }
        if (!allowed.containsAll(locations.keySet())) {
            throw blocked("指令来源必须对应实际消息、工具说明或输出结构说明");
        }
    }

    private static void validateStructure(Map<String, ModelInputSource> locations, Set<String> allowed,
                                          String position, JsonNode structure) {
        allowed.add(position);
        // 整体来源随结构保留；只有协议字段的结构允许标记为数据，不编造正文版本。
        require(locations, position, false);
        Set<String> descriptions = new LinkedHashSet<>();
        descriptionLocations(structure, position, descriptions);
        allowed.addAll(descriptions);
        ModelInputSource whole = locations.get(position);
        for (String description : descriptions) {
            if (whole.origin() != ModelInputSource.Origin.APPLICATION_INSTRUCTION
                    || locations.containsKey(description)) {
                require(locations, description, true);
            }
        }
    }

    private static void descriptionLocations(JsonNode value, String position, Set<String> descriptions) {
        if (value.isObject()) {
            value.fields().forEachRemaining(field -> {
                String pointer = position + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
                if ((field.getKey().equals("description") || field.getKey().equals("title")
                        || field.getKey().equals("$comment")) && field.getValue().isTextual()
                        && !field.getValue().asText().isBlank()) {
                    descriptions.add(pointer);
                }
                descriptionLocations(field.getValue(), pointer, descriptions);
            });
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                descriptionLocations(value.get(i), position + "/" + i, descriptions);
            }
        }
    }

    private static boolean validPointer(String pointer) {
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '~') {
                if (++i >= pointer.length() || (pointer.charAt(i) != '0' && pointer.charAt(i) != '1')) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void require(Map<String, ModelInputSource> locations, String position,
                                boolean applicationInstruction) {
        ModelInputSource source = locations.get(position);
        if (source == null || (applicationInstruction
                && source.origin() != ModelInputSource.Origin.APPLICATION_INSTRUCTION)) {
            throw blocked("模型输入缺少指令来源或数据归类：" + position);
        }
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
