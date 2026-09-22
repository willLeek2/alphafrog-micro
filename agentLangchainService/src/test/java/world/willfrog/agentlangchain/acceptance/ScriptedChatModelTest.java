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

    @Test
    void theTemplateItselfRefusesToAnswerWithoutAnIdentity() {
        ScriptedChatModel model = model(twoTurns());

        assertThatThrownBy(() -> chat(model))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_call_identity_missing"))
                .hasMessageContaining("没有带调用身份");
    }

    @Test
    void aBoundCallAnswersTheTurnTheStoreGaveIt() {
        when(callStore.claim(eq("run-1"), eq("fx-1"), eq("scenario-a"), any(), eq(IDENTITY)))
                .thenReturn(claim(1, "回合 2 声明 stage=node;nodeId=n1"));

        ChatResponse response = chat(model(twoTurns()).boundTo(IDENTITY));

        assertThat(response.aiMessage().text()).isEqualTo("第二回合");
        // 模型不自己数到第几个回合：该用哪一个由认领表说，问的时候要把身份带上。
        verify(callStore).claim(eq("run-1"), eq("fx-1"), eq("scenario-a"), any(), eq(IDENTITY));
    }

    @Test
    void everyEntryPointGoesThroughTheSameClaim() {
        when(callStore.claim(any(), any(), any(), any(), eq(IDENTITY)))
                .thenReturn(claim(0, "回合 1 声明 stage=node;nodeId=n1"));
        ChatModel bound = model(toolTurn()).boundTo(IDENTITY);

        // 规划回合走 chat(List)、节点回合走 chat(ChatRequest)、DAG 判定直接调 doChat：
        // 三种入口都落在 doChat 上，所以都带上同一个身份去认领。
        AiMessage fromList = bound.chat(List.<ChatMessage>of(UserMessage.from("目标"))).aiMessage();
        AiMessage fromRequest = bound.chat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("继续"))).build()).aiMessage();
        AiMessage fromDoChat = bound.doChat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("判定"))).build()).aiMessage();

        assertThat(fromList.text()).isEqualTo("先查一下");
        assertThat(fromRequest.toolExecutionRequests()).extracting(ToolExecutionRequest::id).containsExactly("call-a");
        assertThat(fromDoChat.text()).isEqualTo("先查一下");
    }

    @Test
    void anIdentityForAnotherRunIsRefused() {
        ScriptedChatModel model = model(twoTurns());

        assertThatThrownBy(() -> model.boundTo(FixtureCallIdentity.nodeSegment("run-9", 0, "n1", 0, 0, 0)))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("run-1")
                .hasMessageContaining("run-9");
    }

    @Test
    void aStoreRefusalComesOutUnchanged() {
        when(callStore.claim(any(), any(), any(), any(), any()))
                .thenThrow(refuse("acceptance_fixture_no_declared_turn", "脚本里没有任何回合声明回答这一次调用"));

        assertThatThrownBy(() -> chat(model(twoTurns()).boundTo(IDENTITY)))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_no_declared_turn"));
    }

    @Test
    void anEmptyIdentityIsRefused() {
        assertThatThrownBy(() -> model(twoTurns()).boundTo(null))
                .hasMessageContaining("调用身份是空的");
    }

    private ScriptedChatModel model(String scriptJson) {
        return new ScriptedChatModel("run-1", "fx-1", "scenario-a",
                FrozenModelScript.parse("fx-1", scriptJson, objectMapper), callStore);
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
