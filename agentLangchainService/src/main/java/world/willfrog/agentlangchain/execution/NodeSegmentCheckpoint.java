package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.Objects;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;

/** 一次节点分段的完整消息及来源；恢复时两者一起读取，不重新猜测旧消息来源。 */
public final class NodeSegmentCheckpoint {
    public static final String FIELD = "nodeLoopCheckpoint";

    private final int modelTurn;
    private final Integer resumeGroupTurn;
    private final List<PromptMessage> promptMessages;
    private final int toolCallsUsed;

    /** 兼容旧构造入口，旧消息全部保留UNKNOWN，直至调用方提供真实来源。 */
    public NodeSegmentCheckpoint(int modelTurn, Integer resumeGroupTurn, List<ChatMessage> messages, int toolCallsUsed) {
        this(modelTurn, resumeGroupTurn, toolCallsUsed, messages == null ? List.of()
                : messages.stream().map(PromptMessage::unknown).toList());
    }

    private NodeSegmentCheckpoint(int modelTurn, Integer resumeGroupTurn, int toolCallsUsed,
                                  List<PromptMessage> promptMessages) {
        if (modelTurn < 0 || (resumeGroupTurn != null && resumeGroupTurn < 0) || toolCallsUsed < 0
                || promptMessages == null || promptMessages.stream().anyMatch(Objects::isNull)) throw invalid();
        this.modelTurn = modelTurn;
        this.resumeGroupTurn = resumeGroupTurn;
        this.toolCallsUsed = toolCallsUsed;
        this.promptMessages = List.copyOf(promptMessages);
    }

    public static NodeSegmentCheckpoint withPromptMessages(int modelTurn, Integer resumeGroupTurn,
            List<PromptMessage> messages, int toolCallsUsed) {
        return new NodeSegmentCheckpoint(modelTurn, resumeGroupTurn, toolCallsUsed, messages);
    }

    public int modelTurn() { return modelTurn; }
    public Integer resumeGroupTurn() { return resumeGroupTurn; }
    public int toolCallsUsed() { return toolCallsUsed; }

    /** 保留旧消费接口，返回与来源快照相同次序的不可变消息列表。 */
    public List<ChatMessage> messages() {
        return promptMessages.stream().map(PromptMessage::message).toList();
    }

    public List<PromptMessage> snapshot() {
        return promptMessages;
    }

    public ModelInputSources modelInputSources() {
        return PromptMessage.toModelInputSources(promptMessages);
    }

    /** 只有完全不存在字段才表示首段；已存在但损坏的检查点必须停止。 */
    public static NodeSegmentCheckpoint read(JsonNode payload) {
        if (payload == null) return null;
        if (!payload.isObject()) throw invalid();
        if (!payload.has(FIELD)) return null;
        JsonNode node = payload.get(FIELD);
        if (!node.isObject()) throw invalid();
        Integer resume = node.get("resumeGroupTurn") == null || node.get("resumeGroupTurn").isNull()
                ? null : count(node.get("resumeGroupTurn"));
        return withPromptMessages(count(node.get("modelTurn")), resume,
                PromptMessageCodec.read(node.get("messages")), count(node.get("toolCallsUsed")));
    }

    /** SDK原消息字段与promptSource在同一数组项中写入，无第二个可变来源列表。 */
    public ObjectNode toJson(ObjectMapper objectMapper) {
        if (objectMapper == null) throw invalid();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelTurn", modelTurn);
        if (resumeGroupTurn == null) node.putNull("resumeGroupTurn");
        else node.put("resumeGroupTurn", resumeGroupTurn);
        node.put("toolCallsUsed", toolCallsUsed);
        node.set("messages", PromptMessageCodec.write(promptMessages));
        return node;
    }

    private static int count(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid();
        }
        return value.intValue();
    }

    private static ModelSendBlockedException invalid() {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "节点消息检查点结构或计数无效");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeSegmentCheckpoint checkpoint && modelTurn == checkpoint.modelTurn
                && toolCallsUsed == checkpoint.toolCallsUsed && Objects.equals(resumeGroupTurn, checkpoint.resumeGroupTurn)
                && promptMessages.equals(checkpoint.promptMessages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelTurn, resumeGroupTurn, promptMessages, toolCallsUsed);
    }

    @Override
    public String toString() {
        return "NodeSegmentCheckpoint[modelTurn=" + modelTurn + ", resumeGroupTurn=" + resumeGroupTurn
                + ", messageCount=" + promptMessages.size() + ", toolCallsUsed=" + toolCallsUsed + "]";
    }
}
