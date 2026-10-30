package world.willfrog.agent.platform.input;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptSetVersionSource;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

/** SDK消息格式保持不变，在同一消息对象内保存明确来源；不序列化任意来源对象。 */
public final class PromptMessageCodec {
    /** SDK工具续轮只传这份来源JSON字符串，避免通用Map解码改变版本号的数值类型。 */
    public static final String SOURCE_ATTRIBUTE_KEY = "alphafrog.promptSourceJson";
    private static final String SOURCE_FIELD = "promptSource";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> VERSION_FIELDS = Set.of("environment", "scopeInstanceId", "promptId", "version",
            "purpose", "selectionId", "experimentValueId", "valueSourceJson", "selectionKey", "promptSetVersion",
            "fragmentName", "experimentId");

    private PromptMessageCodec() { }

    /** 仅编码来源，不把工具结果或其他模型正文复制到attributes。 */
    public static Map<String, Object> sourceAttributes(PromptMessage.Origin origin, List<PromptVersionSource> sources) {
        try {
            ObjectNode attribution = writeAttribution(origin, sources);
            readAttribution(attribution);
            return Map.of(SOURCE_ATTRIBUTE_KEY, attribution.toString());
        } catch (Exception failure) {
            throw PromptMessage.blocked("消息来源属性无法安全编码");
        }
    }

    /** 无来源属性的旧消息保持UNKNOWN；存在但损坏的属性不能降级成运行数据。 */
    public static PromptMessage fromSourceAttributes(ChatMessage message, Map<String, Object> attributes) {
        try {
            if (attributes == null || !attributes.containsKey(SOURCE_ATTRIBUTE_KEY)) return PromptMessage.unknown(message);
            Object value = attributes.get(SOURCE_ATTRIBUTE_KEY);
            if (!(value instanceof String encoded)) throw new IllegalArgumentException();
            Attribution attribution = readAttribution(JSON.readTree(encoded));
            return new PromptMessage(message, attribution.origin(), attribution.sources());
        } catch (Exception failure) {
            throw PromptMessage.blocked("消息来源属性无法读取");
        }
    }

    public static ArrayNode write(List<PromptMessage> messages) {
        try {
            if (messages == null) throw new IllegalArgumentException();
            ArrayNode result = JSON.createArrayNode();
            for (PromptMessage entry : messages) {
                ObjectNode message = object(JSON.readTree(ChatMessageSerializer.messageToJson(entry.message())));
                message.set(SOURCE_FIELD, writeAttribution(entry.origin(), entry.sources()));
                // 与读取使用同一结构检查，坏字符串不能经JSON转义绕过严格UTF-8校验。
                readMessage(message);
                result.add(message);
            }
            return result;
        } catch (Exception failure) {
            throw PromptMessage.blocked("消息及来源无法安全编码");
        }
    }

    public static List<PromptMessage> read(JsonNode messages) {
        try {
            if (messages == null || !messages.isArray()) throw new IllegalArgumentException();
            List<PromptMessage> result = new ArrayList<>();
            for (JsonNode message : messages) result.add(readMessage(message));
            return List.copyOf(result);
        } catch (Exception failure) {
            throw PromptMessage.blocked("消息及来源结构无法读取");
        }
    }

    private static PromptMessage readMessage(JsonNode node) throws Exception {
        ObjectNode encoded = object(node);
        validateUnicode(encoded);
        validateNativeShape(encoded);
        ObjectNode raw = encoded.deepCopy();
        JsonNode attribution = raw.remove(SOURCE_FIELD);
        ChatMessage message = ChatMessageDeserializer.messageFromJson(raw.toString());
        if (message == null) throw new IllegalArgumentException();
        if (attribution == null) return PromptMessage.unknown(message);
        Attribution source = readAttribution(attribution);
        return new PromptMessage(message, source.origin(), source.sources());
    }

    private static ObjectNode writeAttribution(PromptMessage.Origin origin, List<PromptVersionSource> sources) {
        ObjectNode attribution = JSON.createObjectNode();
        attribution.put("origin", origin.name());
        ArrayNode encoded = attribution.putArray("sources");
        for (PromptVersionSource source : sources) encoded.add(writeSource(source));
        return attribution;
    }

    private static Attribution readAttribution(JsonNode node) throws Exception {
        exactFields(object(node), Set.of("origin", "sources"));
        validateUnicode(node);
        PromptMessage.Origin origin = PromptMessage.Origin.valueOf(text(node, "origin", true));
        JsonNode sourceNodes = node.get("sources");
        if (sourceNodes == null || !sourceNodes.isArray()) throw new IllegalArgumentException();
        List<PromptVersionSource> sources = new ArrayList<>();
        for (JsonNode source : sourceNodes) sources.add(readSource(source));
        if (origin == PromptMessage.Origin.APPLICATION_INSTRUCTION ? sources.isEmpty() : !sources.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return new Attribution(origin, List.copyOf(sources));
    }

    private record Attribution(PromptMessage.Origin origin, List<PromptVersionSource> sources) { }

    private static ObjectNode writeSource(PromptVersionSource source) {
        ObjectNode node = JSON.createObjectNode();
        node.put("environment", source.environment());
        node.put("scopeInstanceId", source.scopeInstanceId());
        node.put("promptId", source.promptId());
        node.put("version", source.version());
        node.put("purpose", source.purpose());
        node.put("selectionId", source.selectionId());
        node.put("experimentValueId", source.experimentValueId());
        node.put("valueSourceJson", source.valueSourceJson());
        node.put("selectionKey", source.selectionKey());
        node.put("fragmentName", source.fragmentName());
        node.put("experimentId", source.experimentId());
        PromptSetVersionSource set = source.promptSetVersion();
        if (set == null) node.putNull("promptSetVersion");
        else {
            ObjectNode reference = node.putObject("promptSetVersion");
            reference.put("environment", set.environment());
            reference.put("scopeInstanceId", set.scopeInstanceId());
            reference.put("promptSetId", set.promptSetId());
            reference.put("version", set.version());
        }
        return node;
    }

    private static PromptVersionSource readSource(JsonNode node) throws Exception {
        exactFields(object(node), VERSION_FIELDS);
        PromptSetVersionSource set = null;
        JsonNode setNode = node.get("promptSetVersion");
        if (setNode != null && !setNode.isNull()) {
            exactFields(object(setNode), Set.of("environment", "scopeInstanceId", "promptSetId", "version"));
            set = new PromptSetVersionSource(text(setNode, "environment", true), text(setNode, "scopeInstanceId", true),
                    text(setNode, "promptSetId", true), positiveLong(setNode.get("version")));
        }
        String valueSource = text(node, "valueSourceJson", false);
        ValueSource actualSource = null;
        if (valueSource != null && !valueSource.isEmpty()) {
            JsonNode value = JSON.readTree(valueSource);
            object(value);
            validateUnicode(value);
            ValueSource.Builder parsed = ValueSource.newBuilder();
            JsonFormat.parser().merge(valueSource, parsed);
            actualSource = parsed.build();
            validateValueSource(actualSource);
        }
        PromptVersionSource result = new PromptVersionSource(text(node, "environment", true), text(node, "scopeInstanceId", true),
                text(node, "promptId", true), positiveLong(node.get("version")), text(node, "purpose", true),
                text(node, "selectionId", false), text(node, "experimentValueId", false), valueSource,
                text(node, "selectionKey", false), set, text(node, "fragmentName", false), text(node, "experimentId", false));
        boolean experiment = present(result.experimentId());
        boolean assignment = present(result.experimentValueId());
        if (experiment) {
            if (actualSource == null || (actualSource.getKind() == ValueSourceKind.ENDED_FIXED ? assignment : !assignment)) {
                throw new IllegalArgumentException();
            }
        } else if (assignment || actualSource != null && actualSource.getKind() != ValueSourceKind.ORDINARY_DEFAULT) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    private static void validateValueSource(ValueSource value) {
        if (value.getKind() == ValueSourceKind.VALUE_SOURCE_UNSPECIFIED || value.getKind() == ValueSourceKind.UNRECOGNIZED
                || value.hasConfigurationVersion() && value.getConfigurationVersion() <= 0
                || value.hasCandidateId() && value.getCandidateId().isBlank()) throw new IllegalArgumentException();
        if (value.getKind() == ValueSourceKind.EXPERIMENT || value.getKind() == ValueSourceKind.ENDED_FIXED) {
            if (value.getConfigurationScopeInstanceId().isBlank() || !value.hasConfigurationVersion()) {
                throw new IllegalArgumentException();
            }
        }
        if (value.getKind() == ValueSourceKind.EXPERIMENT && !value.hasCandidateId()) throw new IllegalArgumentException();
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private static long positiveLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException();
            return null;
        }
        if (!value.isTextual() || (required && value.textValue().isBlank())
                || (!value.textValue().isEmpty() && value.textValue().isBlank())) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static ObjectNode object(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        return (ObjectNode) node;
    }

    private static void exactFields(ObjectNode node, Set<String> fields) {
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException();
        });
    }

    private static void validateNativeShape(JsonNode node) {
        String type = text(node, "type", true);
        if ("SYSTEM".equals(type)) nativeText(node, "text", true);
        if ("USER".equals(type) || "TOOL_EXECUTION_RESULT".equals(type)) {
            JsonNode contents = node.get("contents");
            if (contents == null || !contents.isArray() || contents.isEmpty()) throw new IllegalArgumentException();
            for (JsonNode content : contents) {
                object(content);
                if ("TEXT".equals(text(content, "type", true))) {
                    JsonNode value = content.get("text");
                    if (value == null || !value.isTextual()) throw new IllegalArgumentException();
                }
            }
        }
        if ("AI".equals(type)) {
            nativeText(node, "text", false);
            JsonNode requests = node.get("toolExecutionRequests");
            if (requests != null && !requests.isNull()) {
                if (!requests.isArray()) throw new IllegalArgumentException();
                for (JsonNode request : requests) {
                    object(request);
                    nativeText(request, "id", false);
                    nativeText(request, "name", true);
                    nativeText(request, "arguments", false);
                }
            }
        }
        if ("TOOL_EXECUTION_RESULT".equals(type)) {
            nativeText(node, "id", false);
            nativeText(node, "toolName", true);
        }
    }

    private static void nativeText(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException();
        } else if (!value.isTextual()) throw new IllegalArgumentException();
    }

    private static void validateUnicode(JsonNode node) throws Exception {
        if (node.isTextual()) validateString(node.textValue());
        else if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                validateString(field.getKey());
                validateUnicode(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) validateUnicode(element);
        }
    }

    private static void validateString(String value) throws Exception {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException();
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
    }
}
