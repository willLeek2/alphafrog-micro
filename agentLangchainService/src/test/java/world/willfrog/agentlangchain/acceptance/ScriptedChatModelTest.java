package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 脚本模型只按调用身份作答：谁问、以什么身份问，决定了它拿哪一份回复。
 */
class ScriptedChatModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FixtureCallStore callStore = mock(FixtureCallStore.class);

    private static final FixtureCallIdentity IDENTITY =
            FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 0, 0);












    private ScriptedChatModel model(String scriptJson) {
        FrozenModelScript script = FrozenModelScript.parse("fx-1", scriptJson, objectMapper);
        // 这个替身里的夹具内容不会变：每次调用前重读拿到的就是同一份，摘要与建对象时一致。
        return new ScriptedChatModel("run-1", "fx-1", "scenario-a", script, callStore,
                () -> new ScriptedChatModel.FixtureScript("fx-1", "scenario-a", script));
    }

    private static FixtureCallStore.Claim claim(int turnIndex, String declaredFor) {
        return new FixtureCallStore.Claim(turnIndex, IDENTITY.describe(), declaredFor, false,
                "a".repeat(64), OffsetDateTime.now());
    }

    private static ChatResponse chat(ChatModel model) {
        return model.chat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("继续")))
                .build());
    }

    private static ChatModel replies(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }

    private static String twoTurns() {
        return """
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1"},"text":"第一回合"},
                  {"for":{"stage":"node","nodeId":"n1"},"text":"第二回合"}]}
                """;
    }

    private static String toolTurn() {
        return """
                {"turns":[{"for":{"stage":"node","nodeId":"n1"},"text":"先查一下","toolCalls":[
                   {"id":"call-a","name":"query","argumentsJson":{"symbol":"AAPL"}}]}]}
                """;
    }

    private static String code(Throwable error) {
        return error instanceof AcceptanceFixtureExecutionException fixture ? fixture.code() : null;
    }
}
