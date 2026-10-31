package world.willfrog.agentlangchain.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.workflow.StructuredPlanningSupport;
import world.willfrog.agentlangchain.execution.RootTreeCallBudget;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 规划专用正文、结构说明和本次输入来源，不读取本地导入材料作为备用。 */
@Component
public final class PlanningPromptPreparation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AgentPromptTextReader reader;
    private final AgentModelInputs inputs;

    public PlanningPromptPreparation(AgentPromptTextReader reader, AgentModelInputs inputs) {
        this.reader = reader;
        this.inputs = inputs;
    }

    /** 管理员只维护固定键的说明文字，协议字段、类型和约束由Java生成。 */
    public SchemaDescriptions descriptions() {
        PromptVersionBody body = read("planningSchemaDescriptions");
        if (body.type() != PromptBodyType.FIXED) throw unreadable("规划结构说明需要固定JSON字典版本");
        PromptText text = reader.render(body, Map.of(), Map.of());
        try {
            JsonNode dictionary = JSON.readTree(text.text());
            if (dictionary == null || !dictionary.isObject()) throw unreadable("规划结构说明不是JSON对象");
            var keys = new LinkedHashSet<String>();
            dictionary.fieldNames().forEachRemaining(keys::add);
            if (!StructuredPlanningSupport.acceptsPlanningDescriptionKeys(keys)) {
                throw unreadable("规划结构说明缺项或包含未知字段");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : keys) {
                JsonNode value = dictionary.get(key);
                if (!value.isTextual() || value.textValue().isBlank()) throw unreadable("规划结构说明需要非空文字");
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value.textValue()));
                values.put(key, value.textValue());
            }
            return new SchemaDescriptions(values, text.sources());
        } catch (Exception failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            throw unreadable("规划结构说明无法按严格JSON字典读取");
        }
    }

    public PromptText budgetNote(RootTreeCallBudget.ToolCallBudgetStatus status) {
        if (status == null) return PromptText.runtimeData("");
        String fragment = status.unlimited() ? "planningToolBudgetUnlimited" : "planningToolBudgetLimited";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("scopeNote", String.valueOf(status.scopeNote()));
        if (!status.unlimited()) {
            values.put("used", String.valueOf(status.used()));
            values.put("limit", String.valueOf(status.limit()));
            values.put("remaining", String.valueOf(status.remaining()));
        }
        return reader.render(read(fragment), values, Map.of());
    }

    /** 旧裸字符串必须与调用方明确提供的带分类正文相符，不能在此猜测其来源。 */
    public PromptText history(String legacyText, PromptText classified) {
        if (classified == null) {
            if (legacyText != null && !legacyText.isBlank()) {
                throw missing("规划历史尚未保留正文来源");
            }
            return PromptText.runtimeData("");
        }
        if (legacyText != null && !legacyText.isBlank() && !legacyText.equals(classified.text())) {
            throw missing("规划历史正文与来源载体不一致");
        }
        return classified;
    }

    public PromptText userMessage(PromptText prefix, PromptText stage, String userGoal, PromptText history) {
        if (history == null || history.text().isBlank()) {
            return PromptText.concat(prefix, PromptText.runtimeData("\n"), stage,
                    PromptText.runtimeData("\n\n用户需求：" + userGoal));
        }
        return PromptText.concat(prefix, PromptText.runtimeData("\n"), stage,
                PromptText.runtimeData("\n\n历史对话压缩内容：\n"), history,
                PromptText.runtimeData("\n\n当前轮次用户需求：" + userGoal));
    }

    public ModelInputScope open(String endpoint, List<PromptMessage> messages, AgentContext.StructuredOutputSpec spec) {
        List<ModelInputSource> sources = new ArrayList<>(PromptMessage.toModelInputSources(messages).parts());
        if (spec != null) {
            if (spec.instructionSources().isEmpty()) throw missing("规划输出结构说明没有版本来源");
            sources.add(new ModelInputSource("/response_format", ModelInputSource.Origin.APPLICATION_INSTRUCTION,
                    spec.instructionSources()));
        }
        return inputs.open(endpoint, new ModelInputSources(sources));
    }

    /** 核对实际SDK消息后保留原请求参数，仅补本次规划明确生成的结构字段。 */
    public ChatRequest request(ChatRequest request, List<PromptMessage> expected, AgentContext.StructuredOutputSpec spec) {
        if (request == null || !request.messages().equals(expected.stream().map(PromptMessage::message).toList())) {
            throw missing("规划实际消息与登记来源快照不一致");
        }
        if (spec == null) return request;
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (request.parameters() instanceof OpenAiChatRequestParameters openAi && openAi.customParameters() != null) {
            parameters.putAll(openAi.customParameters());
        }
        parameters.put("response_format", spec.asResponseFormat());
        return request.toBuilder().parameters(OpenAiChatRequestParameters.builder().overrideWith(request.parameters())
                .customParameters(parameters).build()).build();
    }

    private PromptVersionBody read(String fragment) {
        var root = reader.current();
        PromptVersionBody body = reader.read(root, Map.of(fragment, fragment)).get(fragment);
        if (body == null) throw unreadable("规划正文版本不可读取");
        return body;
    }

    private static ModelSendBlockedException unreadable(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE, reason);
    }

    private static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    public record SchemaDescriptions(Map<String, String> values, List<PromptVersionSource> sources) {
        public SchemaDescriptions {
            values = Map.copyOf(values);
            sources = List.copyOf(sources);
        }
        public boolean expectedToolsEnabled() {
            return values.containsKey(StructuredPlanningSupport.EXPECTED_TOOLS_DESCRIPTION);
        }
        @Override public String toString() { return "SchemaDescriptions[fieldCount=" + values.size() + ", sourceCount=" + sources.size() + "]"; }
    }
}
