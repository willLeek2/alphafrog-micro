package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.util.List;

/**
 * 一个逻辑节点在两次领取之间的检查点：下一位 Worker 靠它把模型会话接回原样继续跑。
 *
 * <p>它随下一段的载荷一起写入，本身不单独建表。里面有四样东西：本段是第几次模型回复（从 0 起）、
 * 本段要接回哪一次模型回合产生的等待组（首段为空）、到目前为止的完整消息序列、以及本节点已经用掉的
 * 工具调用次数。</p>
 *
 * <p>接回等待组只记模型回合，不记等待组编号：本段的分段序号减一就是产生那一组的段落，
 * 与模型回合合起来唯一确定一个等待组（两者本来就在等待组身份里）。这样挂起语句在写下一段载荷时
 * 不需要先知道等待组编号——那时编号还没生成。</p>
 *
 * <p>消息序列包含系统消息与全部往返。存整段而不是存差量，是因为恢复时它要原样交给模型：
 * 少一条助手消息或顺序错位，模型看到的历史就和没中断时不一样，「最终上下文与不中断执行时等价」
 * 这句话就没法证明。</p>
 */
public record NodeSegmentCheckpoint(int modelTurn,
                                    Integer resumeGroupTurn,
                                    List<ChatMessage> messages,
                                    int toolCallsUsed) {

    /** 载荷里的字段名：分段载荷与工作项检查点都用它。 */
    public static final String FIELD = "nodeLoopCheckpoint";

    public NodeSegmentCheckpoint {
        if (modelTurn < 0) {
            throw new IllegalArgumentException("模型回合不能是负数：" + modelTurn);
        }
        if (resumeGroupTurn != null && resumeGroupTurn < 0) {
            throw new IllegalArgumentException("要接回的模型回合不能是负数：" + resumeGroupTurn);
        }
        if (toolCallsUsed < 0) {
            throw new IllegalArgumentException("工具调用次数不能是负数：" + toolCallsUsed);
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** 从分段载荷里读出检查点；没有这个字段说明这一段还没进过模型，返回空。 */
    public static NodeSegmentCheckpoint read(JsonNode payload) {
        if (payload == null || !payload.path(FIELD).isObject()) {
            return null;
        }
        JsonNode node = payload.path(FIELD);
        Integer resumeGroupTurn = node.path("resumeGroupTurn").isNumber()
                ? node.path("resumeGroupTurn").asInt() : null;
        JsonNode messagesNode = node.path("messages");
        List<ChatMessage> messages = messagesNode.isArray()
                ? ChatMessageDeserializer.messagesFromJson(messagesNode.toString())
                : List.of();
        return new NodeSegmentCheckpoint(
                Math.max(0, node.path("modelTurn").asInt(0)),
                resumeGroupTurn,
                messages,
                Math.max(0, node.path("toolCallsUsed").asInt(0)));
    }

    /** 写进分段载荷的形式：消息序列直接嵌成数组，不在外面再套一层转义。 */
    public ObjectNode toJson(ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelTurn", modelTurn);
        if (resumeGroupTurn == null) {
            node.putNull("resumeGroupTurn");
        } else {
            node.put("resumeGroupTurn", resumeGroupTurn);
        }
        node.put("toolCallsUsed", toolCallsUsed);
        ArrayNode messagesNode = node.putArray("messages");
        if (!messages.isEmpty()) {
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(ChatMessageSerializer.messagesToJson(messages));
            } catch (Exception e) {
                throw new IllegalStateException("节点检查点的消息序列无法编码", e);
            }
            if (parsed.isArray()) {
                messagesNode.addAll((ArrayNode) parsed);
            }
        }
        return node;
    }
}
