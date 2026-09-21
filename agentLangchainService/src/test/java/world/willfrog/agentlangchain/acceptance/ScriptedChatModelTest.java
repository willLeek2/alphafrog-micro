package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本模型按顺序作答，并且只按脚本作答：用完就报错，不换真实模型。
 */
class ScriptedChatModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repliesComeOutInTheOrderTheScriptWroteThem() {
        ScriptedChatModel model = model("""
                {"turns":[
                  {"text":"计划"},
                  {"text":"先查一下","toolCalls":[
                     {"id":"call-a","name":"query","argumentsJson":{"symbol":"AAPL"}},
                     {"id":"call-b","name":"query","argumentsJson":{"symbol":"MSFT"}},
                     {"id":"call-c","name":"fetch","argumentsJson":{"url":"https://example.test/a"}}]},
                  {"text":"答案"}]}
                """);

        assertThat(chat(model).aiMessage().text()).isEqualTo("计划");
        AiMessage toolTurn = chat(model).aiMessage();
        assertThat(toolTurn.text()).isEqualTo("先查一下");
        // 工具调用的身份、工具名、参数与先后顺序照脚本，一个不少一个不错。
        assertThat(toolTurn.toolExecutionRequests()).extracting(ToolExecutionRequest::id)
                .containsExactly("call-a", "call-b", "call-c");
        assertThat(toolTurn.toolExecutionRequests()).extracting(ToolExecutionRequest::name)
                .containsExactly("query", "query", "fetch");
        assertThat(toolTurn.toolExecutionRequests().get(0).arguments()).isEqualTo("{\"symbol\":\"AAPL\"}");
        assertThat(chat(model).aiMessage().text()).isEqualTo("答案");
        assertThat(model.consumedTurns()).isEqualTo(3);
        assertThat(model.scriptSize()).isEqualTo(3);
    }

    @Test
    void aTextOnlyTurnCarriesNoToolCalls() {
        ScriptedChatModel model = model("{\"turns\":[{\"text\":\"答案\"}]}");

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("帮我查一下")))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("答案");
        assertThat(response.aiMessage().toolExecutionRequests()).isEmpty();
    }

    @Test
    void aTurnWithOnlyToolCallsHasNoText() {
        ScriptedChatModel model = model("""
                {"turns":[{"toolCalls":[{"id":"c1","name":"query","argumentsJson":{}}]}]}
                """);

        AiMessage reply = chat(model).aiMessage();

        assertThat(reply.text()).isNull();
        assertThat(reply.toolExecutionRequests()).hasSize(1);
    }

    @Test
    void aCallBeyondTheEndOfTheScriptIsRefusedInsteadOfFallingBackToAProvider() {
        ScriptedChatModel model = model("{\"turns\":[{\"text\":\"只有一回合\"}]}");

        assertThat(chat(model).aiMessage().text()).isEqualTo("只有一回合");
        assertThatThrownBy(() -> chat(model))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_script_exhausted"))
                .hasMessageContaining("只有 1 个回合")
                .hasMessageContaining("这次执行要第 2 个")
                .hasMessageContaining("不换真实模型接着跑");
        // 读数停在实际回合数上，失败的那次不跟着一起涨。
        assertThat(model.consumedTurns()).isEqualTo(1);
    }

    @Test
    void theSameInstanceServesEveryStageOfOneRun() {
        ScriptedChatModel model = model("""
                {"turns":[{"text":"计划"},{"text":"节点结果"},{"text":"答案"}]}
                """);

        // 规划回合用 chat(List)，节点回合用 chat(ChatRequest)，DAG 判定直接调 doChat：
        // 三种入口都落在同一个消费位置上。
        assertThat(model.chat(List.<ChatMessage>of(UserMessage.from("目标"))).aiMessage().text())
                .isEqualTo("计划");
        assertThat(chat(model).aiMessage().text()).isEqualTo("节点结果");
        assertThat(model.doChat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("判定"))).build())
                .aiMessage().text()).isEqualTo("答案");
    }

    private ScriptedChatModel model(String scriptJson) {
        return new ScriptedChatModel("fx-1", "scenario-a",
                FrozenModelScript.parse("fx-1", scriptJson, objectMapper));
    }

    private ChatResponse chat(ScriptedChatModel model) {
        return model.chat(ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("继续")))
                .build());
    }
}
