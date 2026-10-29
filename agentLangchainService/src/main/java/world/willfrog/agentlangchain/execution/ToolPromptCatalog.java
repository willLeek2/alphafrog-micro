package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 工具协议继续由代码生成；发送给模型的总说明和参数说明按原根集合替换。 */
@Component
public final class ToolPromptCatalog {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AgentPromptTextReader reader;

    public ToolPromptCatalog(AgentPromptTextReader reader) {
        this.reader = reader;
    }

    public Prepared prepare(List<ToolSpecification> specifications) {
        try {
            if (specifications == null || specifications.isEmpty()) {
                return new Prepared(List.of(), new ModelInputSources(List.of()));
            }
            var root = reader.current();
            List<ToolSpecification> prepared = new ArrayList<>();
            List<ModelInputSource> sources = new ArrayList<>();
            for (ToolSpecification specification : specifications) {
                if (specification == null) throw blocked();
                AgentToolRegistry.require(specification.name());
                String descriptionFragment = "tool.description." + specification.name();
                Map<String, Object> parameters = specification.parameters() == null ? null
                        : JsonSchemaElementJsonUtils.toMap(specification.parameters());
                Map<String, String> originalDescriptions = descriptionTexts(parameters);
                Map<String, String> expected = new LinkedHashMap<>();
                expected.put(descriptionFragment, "TOOL_DESCRIPTION");
                String parameterFragment = "tool.parameters." + specification.name();
                if (!originalDescriptions.isEmpty()) expected.put(parameterFragment, "TOOL_PARAMETER_DESCRIPTIONS");
                var bodies = reader.read(root, expected);
                PromptText description = reader.render(bodies.get(descriptionFragment), Map.of(), Map.of());
                List<PromptVersionSource> toolSources = new ArrayList<>(description.sources());
                JsonObjectSchema schema = specification.parameters();
                if (!originalDescriptions.isEmpty()) {
                    if (bodies.get(parameterFragment).type() != PromptBodyType.FIXED) throw blocked();
                    PromptText parameterText = reader.render(bodies.get(parameterFragment), Map.of(), Map.of());
                    Map<String, String> replacements = parseDescriptions(parameterText.text());
                    if (!originalDescriptions.keySet().equals(replacements.keySet())) throw blocked();
                    Map<String, Object> rewritten = JSON.convertValue(parameters, new TypeReference<>() { });
                    replaceDescriptions(rewritten, "", replacements);
                    var changed = JsonSchemaElementJsonUtils.fromMap(rewritten);
                    if (!(changed instanceof JsonObjectSchema objectSchema)
                            || !rewritten.equals(JsonSchemaElementJsonUtils.toMap(changed))) throw blocked();
                    schema = objectSchema;
                    toolSources.addAll(parameterText.sources());
                }
                prepared.add(specification.toBuilder().description(description.text()).parameters(schema).build());
                sources.add(new ModelInputSource("/tools/" + sources.size(),
                        ModelInputSource.Origin.APPLICATION_INSTRUCTION, toolSources));
            }
            return new Prepared(prepared, new ModelInputSources(sources));
        } catch (RuntimeException failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            throw blocked();
        }
    }

    /** 字典只包含真实说明位置，不允许借说明版本修改协议结构。 */
    private static Map<String, String> parseDescriptions(String body) {
        try {
            var node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw blocked();
            Map<String, String> result = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()) throw blocked();
                String text = field.getValue().textValue();
                if (text.indexOf('\0') >= 0) throw blocked();
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
                result.put(field.getKey(), field.getValue().textValue());
            }
            return result;
        } catch (Exception failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            throw blocked();
        }
    }

    /** 供离线导入采集使用；运行时只用位置集合验证，不沿用这些旧正文。 */
    public static Map<String, String> descriptionTexts(Map<String, Object> parameters) {
        Map<String, String> result = new LinkedHashMap<>();
        collectDescriptions(parameters, "", result);
        return Map.copyOf(result);
    }

    private static void collectDescriptions(Object value, String pointer, Map<String, String> result) {
        if (value instanceof Map<?, ?> map) {
            for (var field : map.entrySet()) {
                String key = String.valueOf(field.getKey());
                String position = pointer + "/" + escape(key);
                if (isDescription(key) && field.getValue() instanceof String text && !text.isBlank()) {
                    result.put(position, text);
                }
                collectDescriptions(field.getValue(), position, result);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectDescriptions(list.get(i), pointer + "/" + i, result);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceDescriptions(Object value, String pointer, Map<String, String> replacements) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            for (var field : map.entrySet()) {
                String position = pointer + "/" + escape(field.getKey());
                if (isDescription(field.getKey()) && field.getValue() instanceof String text && !text.isBlank()) {
                    field.setValue(replacements.get(position));
                } else replaceDescriptions(field.getValue(), position, replacements);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) replaceDescriptions(list.get(i), pointer + "/" + i, replacements);
        }
    }

    private static boolean isDescription(String key) {
        return "description".equals(key) || "title".equals(key) || "$comment".equals(key);
    }

    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    private static ModelSendBlockedException blocked() {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                "工具说明版本、参数说明或协议结构无法确认");
    }

    public record Prepared(List<ToolSpecification> specifications, ModelInputSources sources) {
        public Prepared {
            specifications = List.copyOf(specifications);
            if (sources == null) throw blocked();
        }
    }
}
