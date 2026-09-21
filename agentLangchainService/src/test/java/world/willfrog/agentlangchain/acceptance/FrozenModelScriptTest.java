package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 夹具脚本的读法：读得懂才让跑，读不懂当场拒绝，并且指出是哪个回合哪个字段。
 */
class FrozenModelScriptTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void textTurnsAndToolTurnsAreReadInOrder() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"text":"{\\"items\\":[{\\"id\\":\\"t1\\"}]}"},
                  {"text":"先查两个数据源","toolCalls":[
                     {"id":"call-a","name":"query","argumentsJson":{"symbol":"AAPL","days":5}},
                     {"id":"call-b","name":"query","argumentsJson":"{\\"symbol\\": \\"MSFT\\"}"}]},
                  {"text":"答案"}
                ]}
                """);

        assertThat(script.size()).isEqualTo(3);
        // 第一个回合是规划回合：文本原样带出去，交给原来的计划解析。
        assertThat(script.turns().get(0).text()).isEqualTo("{\"items\":[{\"id\":\"t1\"}]}");
        assertThat(script.turns().get(0).toolCalls()).isEmpty();
        // 第二个回合三个字段都在：身份、工具名、参数，顺序照夹具。
        FrozenModelScript.ToolCall first = script.turns().get(1).toolCalls().get(0);
        FrozenModelScript.ToolCall second = script.turns().get(1).toolCalls().get(1);
        assertThat(first.id()).isEqualTo("call-a");
        assertThat(first.name()).isEqualTo("query");
        assertThat(first.argumentsJson()).isEqualTo("{\"symbol\":\"AAPL\",\"days\":5}");
        // 参数写成字符串时原样保留，不重新排版。
        assertThat(second.argumentsJson()).isEqualTo("{\"symbol\": \"MSFT\"}");
        assertThat(script.turns().get(2).text()).isEqualTo("答案");
    }

    @Test
    void oneTurnCanCarryBothTextAndToolCalls() {
        FrozenModelScript script = parse("""
                {"turns":[{"text":"我先查一下","toolCalls":[
                    {"id":"c1","name":"query","argumentsJson":{}}]}]}
                """);

        assertThat(script.turns().get(0).text()).isEqualTo("我先查一下");
        assertThat(script.turns().get(0).toolCalls()).hasSize(1);
    }

    @Test
    void theSameCallIdMayAppearInDifferentTurns() {
        // 每个回合都是一条新的助手消息，身份只在一个回合内要求各不相同。
        FrozenModelScript script = parse("""
                {"turns":[
                  {"toolCalls":[{"id":"c1","name":"query","argumentsJson":{}}]},
                  {"toolCalls":[{"id":"c1","name":"query","argumentsJson":{}}]}]}
                """);

        assertThat(script.size()).isEqualTo(2);
        assertThat(script.turns().get(1).toolCalls().get(0).id()).isEqualTo("c1");
    }

    @Test
    void aScriptWithoutTurnsIsRejected() {
        assertThatThrownBy(() -> parse("{\"turn\":[]}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"))
                .hasMessageContaining("认不出的字段 turn");
    }

    @Test
    void anEmptyScriptIsRejected() {
        assertThatThrownBy(() -> parse("  "))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("模型脚本是空的");

        assertThatThrownBy(() -> parse("{\"turns\":[]}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("turns 要是非空数组");
    }

    @Test
    void aScriptThatIsNotAJsonObjectIsRejected() {
        assertThatThrownBy(() -> parse("[{\"text\":\"答案\"}]"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("最外层要是一个 JSON 对象");

        assertThatThrownBy(() -> parse("{oops}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("模型脚本不是合法 JSON");
    }

    @Test
    void anUnknownFieldInsideATurnIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"text":"答案","toolcalls":[]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"))
                .hasMessageContaining("第 1 个回合里有认不出的字段 toolcalls");
    }

    @Test
    void aTurnThatSaysNothingIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"text":"答案"},{"text":"  "}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("第 2 个回合的 text 要是非空字符串");

        assertThatThrownBy(() -> parse("{\"turns\":[{}]}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("第 1 个回合既没有文本也没有工具调用");
    }

    @Test
    void twoCallsWithTheSameIdInOneTurnAreRejected() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[
                  {"id":"c1","name":"query","argumentsJson":{"symbol":"AAPL"}},
                  {"id":"c1","name":"query","argumentsJson":{"symbol":"MSFT"}}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("有两个工具调用的 id 都是 c1");
    }

    @Test
    void everyCallNeedsAnIdentityAToolNameAndArguments() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"name":"query","argumentsJson":{}}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("第 1 个工具调用的 id 要是非空字符串");

        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"id":"c1","argumentsJson":{}}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("第 1 个工具调用的 name 要是非空字符串");

        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"id":"c1","name":"query","argumentJson":{}}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("第 1 个回合的第 1 个工具调用里有认不出的字段 argumentJson");
    }

    @Test
    void toolArgumentsMustBeAJsonObject() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"id":"c1","name":"query"}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("没写 argumentsJson");

        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"id":"c1","name":"query","argumentsJson":[1,2]}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("要么写成 JSON 对象，要么写成一段装着 JSON 对象的字符串");

        assertThatThrownBy(() -> parse("""
                {"turns":[{"toolCalls":[{"id":"c1","name":"query","argumentsJson":"不是 JSON"}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("argumentsJson不是合法 JSON");
    }

    private FrozenModelScript parse(String json) {
        return FrozenModelScript.parse("fx-1", json, objectMapper);
    }

    private static String code(Throwable error) {
        return ((AcceptanceFixtureExecutionException) error).code();
    }
}
