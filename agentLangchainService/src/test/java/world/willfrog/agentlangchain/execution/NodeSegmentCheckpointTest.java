package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分段检查点的读写自检。
 *
 * <p>要盯住的是「原样存、原样读」：消息序列走 LC4j 自己的编解码，少一条助手消息或工具结果的身份被
 * 丢掉，恢复后的模型历史就与没中断时不一样。</p>
 */
class NodeSegmentCheckpointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messagesSurviveTheRoundTripIncludingToolRequestsAndResults() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("用户目标"),
                AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("call-a").name("getStockDaily").arguments("{\"asset\":\"000001.SZ\"}")
                        .build())),
                ToolExecutionResultMessage.from("call-a", "getStockDaily", "日线数据"),
                AiMessage.from("结论"));

        NodeSegmentCheckpoint checkpoint = new NodeSegmentCheckpoint(2, 1, messages, 3);
        JsonNode payload = payloadWith(checkpoint);

        NodeSegmentCheckpoint read = NodeSegmentCheckpoint.read(payload);

        assertThat(read).isNotNull();
        assertThat(read.modelTurn()).isEqualTo(2);
        assertThat(read.resumeGroupTurn()).isEqualTo(1);
        assertThat(read.toolCallsUsed()).isEqualTo(3);
        assertThat(read.messages()).isEqualTo(messages);
    }

    @Test
    void aSegmentWithoutACheckpointReadsAsNull() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "TODO");
        assertThat(NodeSegmentCheckpoint.read(payload)).isNull();
    }

    @Test
    void theFirstSegmentHasNoGroupToResume() {
        NodeSegmentCheckpoint checkpoint = new NodeSegmentCheckpoint(0, null,
                List.of(UserMessage.from("目标")), 0);
        NodeSegmentCheckpoint read = NodeSegmentCheckpoint.read(payloadWith(checkpoint));
        assertThat(read).isNotNull();
        assertThat(read.resumeGroupTurn()).isNull();
    }

    private JsonNode payloadWith(NodeSegmentCheckpoint checkpoint) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(NodeSegmentCheckpoint.FIELD, checkpoint.toJson(objectMapper));
        return payload;
    }
}
