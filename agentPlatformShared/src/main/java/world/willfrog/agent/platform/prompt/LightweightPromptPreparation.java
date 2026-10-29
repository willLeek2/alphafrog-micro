package world.willfrog.agent.platform.prompt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;

/** 轻量模型的政策与对话包装只读原根集合，历史内容按保存入口或明确元数据归类。 */
@Component
public final class LightweightPromptPreparation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AgentPromptTextReader reader;

    public LightweightPromptPreparation(AgentPromptTextReader reader) {
        this.reader = reader;
    }

    public PromptText policy(String fragment) {
        var root = reader.current();
        return reader.render(reader.read(root, Map.of(fragment, fragment)).get(fragment), Map.of(), Map.of());
    }

    public SummaryPrompts summary(List<AgentRunMessage> messages, int maxChars) {
        var root = reader.current();
        var versions = reader.read(root, Map.of("followUpSummarySystemPrompt", "followUpSummarySystemPrompt",
                "followUpSummaryDialogue", "followUpSummaryDialogue"));
        PromptVersionBody system = versions.get("followUpSummarySystemPrompt");
        requireVariable(system, "maxChars");
        return new SummaryPrompts(reader.render(system, Map.of("maxChars", String.valueOf(maxChars)), Map.of()),
                dialogue(versions.get("followUpSummaryDialogue"), messages));
    }

    public PromptText dialogue(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) return PromptText.runtimeData("");
        var root = reader.current();
        return dialogue(reader.read(root, Map.of("followUpSummaryDialogue", "followUpSummaryDialogue"))
                .get("followUpSummaryDialogue"), messages);
    }

    private PromptText dialogue(PromptVersionBody wrapper, List<AgentRunMessage> messages) {
        requireVariable(wrapper, "dialogue");
        List<PromptText> rows = new ArrayList<>();
        for (AgentRunMessage message : messages) {
            if (message == null) throw missing("历史消息为空");
            String role = message.getRole();
            String label = AgentRunMessage.ROLE_USER.equals(role) ? "用户"
                    : AgentRunMessage.ROLE_ASSISTANT.equals(role) ? "助手"
                    : AgentRunMessage.ROLE_SYSTEM.equals(role) ? "系统" : role;
            if (label == null) throw missing("历史消息缺少角色");
            rows.add(PromptText.concat(PromptText.runtimeData(label + "："), historyContent(message),
                    PromptText.runtimeData("\n\n")));
        }
        PromptText history = PromptText.concat(rows);
        return history.sources().isEmpty()
                ? reader.render(wrapper, Map.of("dialogue", history.text()), Map.of())
                : reader.render(wrapper, Map.of(), Map.of("dialogue", history));
    }

    private PromptText historyContent(AgentRunMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        JsonNode meta = null;
        if (message.getMetaJson() != null && !message.getMetaJson().isBlank()) {
            try {
                meta = JSON.readTree(message.getMetaJson());
                if (meta == null || !meta.isObject()) throw missing("历史消息元数据不是对象");
            } catch (ModelSendBlockedException failure) {
                throw failure;
            } catch (Exception failure) {
                throw missing("历史消息元数据无法读取");
            }
        }
        if (meta != null && meta.has(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY)) {
            JsonNode encoded = meta.get(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY, encoded.isTextual() ? encoded.textValue() : encoded);
            PromptMessage decoded = PromptMessageCodec.fromSourceAttributes(
                    dev.langchain4j.data.message.UserMessage.from(content), attributes);
            if (decoded.origin() == PromptMessage.Origin.UNKNOWN) throw missing("历史消息仍为未知来源");
            return new PromptText(content, decoded.sources());
        }
        // 既有保存入口：EventService原始问题、FollowUpService原始追问、Pipeline最终回答。
        // 身份和消息类型共同核对；未知system或其他拼装历史不能凭角色标为事实。
        boolean persisted = message.getId() != null && message.getId() > 0 && message.getSeq() != null
                && message.getSeq() > 0 && message.getRunId() != null && !message.getRunId().isBlank();
        boolean userInput = AgentRunMessage.ROLE_USER.equals(message.getRole())
                && (AgentRunMessage.MSG_TYPE_INITIAL.equals(message.getMsgType())
                    || AgentRunMessage.MSG_TYPE_FOLLOW_UP.equals(message.getMsgType()));
        boolean modelOutput = AgentRunMessage.ROLE_ASSISTANT.equals(message.getRole())
                && AgentRunMessage.MSG_TYPE_FOLLOW_UP.equals(message.getMsgType());
        if (persisted && (userInput || modelOutput)) return PromptText.runtimeData(content);
        throw missing("历史消息缺少可确认的原始保存类型或指令版本");
    }

    private static void requireVariable(PromptVersionBody body, String name) {
        if (body == null || body.variables().stream().noneMatch(variable -> variable.name().equals(name))) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_VARIABLE_INVALID, name);
        }
    }

    private static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    public record SummaryPrompts(PromptText system, PromptText user) { }
}
