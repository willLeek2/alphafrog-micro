package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本读得懂、每个回合都声明了它回答哪一次调用、认不出的写法一律拒绝。
 */
class FrozenModelScriptTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void textTurnsAndToolTurnsAreReadInOrder() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"planning","planAttempt":1,"planPhase":"todos"},"text":"计划"},
                  {"for":{"stage":"node","nodeId":"n1"},"text":"先查一下","toolCalls":[
                     {"id":"call-a","name":"query","argumentsJson":{"symbol":"AAPL"}},
                     {"id":"call-b","name":"fetch","argumentsJson":"{\\"url\\":\\"https://example.test/a\\"}"}]},
                  {"for":{"stage":"answer"},"text":"答案"}]}
                """);

        assertThat(script.size()).isEqualTo(3);
        assertThat(script.turns().get(0).text()).isEqualTo("计划");
        assertThat(script.turns().get(0).toolCalls()).isEmpty();
        assertThat(script.turns().get(1).toolCalls()).extracting(FrozenModelScript.ToolCall::id)
                .containsExactly("call-a", "call-b");
        // 参数写成对象或装着对象的字符串都收，最终都是同一段 JSON 文本。
        assertThat(script.turns().get(1).toolCalls().get(0).argumentsJson()).isEqualTo("{\"symbol\":\"AAPL\"}");
        assertThat(script.turns().get(1).toolCalls().get(1).argumentsJson())
                .isEqualTo("{\"url\":\"https://example.test/a\"}");
        assertThat(script.turns().get(2).text()).isEqualTo("答案");
    }

    @Test
    void oneTurnCanCarryBothTextAndToolCalls() {
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"node"},"text":"这就去查","toolCalls":[
                   {"id":"c1","name":"query","argumentsJson":{}}]}]}
                """);

        assertThat(script.turns().get(0).text()).isEqualTo("这就去查");
        assertThat(script.turns().get(0).toolCalls()).hasSize(1);
    }

    @Test
    void theSameCallIdMayAppearInDifferentTurns() {
        // 等待组合同允许不同组复用同一个工具调用编号，脚本不许把这件事当成写错。
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","segmentSequence":0},"toolCalls":[
                     {"id":"call_3","name":"query","argumentsJson":{}}]},
                  {"for":{"stage":"node","segmentSequence":1},"toolCalls":[
                     {"id":"call_3","name":"query","argumentsJson":{}}]}]}
                """);

        assertThat(script.turns()).hasSize(2);
    }

    @Test
    void declarationsAreReadWithTheirScopeAndOptionalFlag() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1","modelTurn":1},"text":"甲"},
                  {"for":{"stage":"node","nodeId":"n2"},"optional":true,"text":"乙"}]}
                """);

        FrozenModelScript.TurnDeclaration first = script.declarationAt(0);
        assertThat(first.stage()).isEqualTo("node");
        assertThat(first.scope()).isEqualTo(Map.of("nodeId", "n1", "modelTurn", "1"));
        assertThat(first.optional()).isFalse();
        assertThat(first.describe()).contains("回合 1").contains("nodeId=n1").contains("modelTurn=1");

        FrozenModelScript.TurnDeclaration second = script.declarationAt(1);
        assertThat(second.optional()).isTrue();
        assertThat(second.describe()).contains("可以不发生");
        assertThat(script.declarationSummary()).hasSize(2);
    }

    @Test
    void candidatesAreTheDeclaredTurnsThisCallMatchesInOrder() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"planning","planPhase":"strategy"},"text":"甲"},
                  {"for":{"stage":"node","nodeId":"n1"},"text":"乙"},
                  {"for":{"stage":"node"},"text":"丙"},
                  {"for":{"stage":"node","nodeId":"n2"},"text":"丁"}]}
                """);

        // 只写节点号的声明只匹配那个节点；只写阶段的声明任何节点都能领（通配）。
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 0, 0)))
                .extracting(FrozenModelScript.TurnDeclaration::turnIndex).containsExactly(1, 2);
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n9", 0, 0, 0)))
                .extracting(FrozenModelScript.TurnDeclaration::turnIndex).containsExactly(2);
        assertThat(script.candidatesFor(FixtureCallIdentity.planning("run-1", 0, 1, "strategy")))
                .extracting(FrozenModelScript.TurnDeclaration::turnIndex).containsExactly(0);
        assertThat(script.candidatesFor(FixtureCallIdentity.answer("run-1", 0))).isEmpty();
    }

    @Test
    void aDeclarationCanPinTheModelTurnOfASegment() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1","modelTurn":0},"text":"第一回合"},
                  {"for":{"stage":"node","nodeId":"n1","modelTurn":1},"text":"接回结果之后"}]}
                """);

        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 0, 0)))
                .extracting(FrozenModelScript.TurnDeclaration::turnIndex).containsExactly(0);
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 1, 1)))
                .extracting(FrozenModelScript.TurnDeclaration::turnIndex).containsExactly(1);
    }

    @Test
    void everyTurnMustDeclareWhichCallItAnswers() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"text\":\"甲\"}]}"))
                .hasMessageContaining("没写 for")
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"));
    }

    @Test
    void aDeclarationWithAnUnknownFieldIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"stage\":\"node\",\"nodeid\":\"n1\"},\"text\":\"甲\"}]}"))
                .hasMessageContaining("认不出的字段")
                .hasMessageContaining("nodeid");
    }

    @Test
    void aDeclarationWithAnUnknownStageIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"stage\":\"retry\"},\"text\":\"甲\"}]}"))
                .hasMessageContaining("stage 认不出来");
    }

    @Test
    void aDeclarationWithoutAStageIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"nodeId\":\"n1\"},\"text\":\"甲\"}]}"))
                .hasMessageContaining("必须写 stage");
    }

    @Test
    void anOptionalFlagThatIsNotABooleanIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"stage\":\"node\"},\"optional\":\"yes\",\"text\":\"甲\"}]}"))
                .hasMessageContaining("optional 要写成 true 或 false");
    }

    @Test
    void aScriptWithoutTurnsIsRejected() {
        assertThatThrownBy(() -> parse("{}"))
                .hasMessageContaining("turns 要是非空数组")
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"));
    }

    @Test
    void anEmptyScriptIsRejected() {
        assertThatThrownBy(() -> parse("   "))
                .hasMessageContaining("模型脚本是空的");
    }

    @Test
    void aScriptThatIsNotAJsonObjectIsRejected() {
        assertThatThrownBy(() -> parse("[]"))
                .hasMessageContaining("最外层要是一个 JSON 对象");
    }

    @Test
    void anUnknownFieldInsideATurnIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"stage\":\"node\"},\"texts\":\"甲\"}]}"))
                .hasMessageContaining("认不出的字段");
    }

    @Test
    void aTurnThatSaysNothingIsRejected() {
        assertThatThrownBy(() -> parse("{\"turns\":[{\"for\":{\"stage\":\"node\"}}]}"))
                .hasMessageContaining("既没有文本也没有工具调用");
    }

    @Test
    void twoCallsWithTheSameIdInOneTurnAreRejected() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[
                   {"id":"c1","name":"query","argumentsJson":{}},
                   {"id":"c1","name":"fetch","argumentsJson":{}}]}]}
                """))
                .hasMessageContaining("同一个回合里的调用身份要各不一样");
    }

    @Test
    void everyCallNeedsAnIdentityAToolNameAndArguments() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[{"name":"query","argumentsJson":{}}]}]}
                """))
                .hasMessageContaining("id 要是非空字符串");
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[{"id":"c1","argumentsJson":{}}]}]}
                """))
                .hasMessageContaining("name 要是非空字符串");
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[{"id":"c1","name":"query"}]}]}
                """))
                .hasMessageContaining("没写 argumentsJson");
    }

    @Test
    void toolArgumentsMustBeAJsonObject() {
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[
                   {"id":"c1","name":"query","argumentsJson":"not json"}]}]}
                """))
                .hasMessageContaining("不是合法 JSON");
        assertThatThrownBy(() -> parse("""
                {"turns":[{"for":{"stage":"node"},"toolCalls":[
                   {"id":"c1","name":"query","argumentsJson":[1,2]}]}]}
                """))
                .hasMessageContaining("要么写成 JSON 对象");
    }

    @Test
    void theSameScriptTextAlwaysHasTheSameDigestAndDifferentTextDoesNot() {
        FrozenModelScript one = parse("{\"turns\":[{\"for\":{\"stage\":\"answer\"},\"text\":\"甲\"}]}");
        FrozenModelScript again = parse("{\"turns\":[{\"for\":{\"stage\":\"answer\"},\"text\":\"甲\"}]}");
        FrozenModelScript other = parse("{\"turns\":[{\"for\":{\"stage\":\"answer\"},\"text\":\"乙\"}]}");

        assertThat(one.digest()).hasSize(64).isEqualTo(again.digest());
        assertThat(other.digest()).isNotEqualTo(one.digest());
        assertThat(one.declarations()).hasSize(1);
    }

    private FrozenModelScript parse(String json) {
        return FrozenModelScript.parse("fx-1", json, objectMapper);
    }

    private static String code(Throwable error) {
        return error instanceof AcceptanceFixtureExecutionException fixture ? fixture.code() : null;
    }

    @Test
    void stageNamesAreCaseInsensitiveInDeclarations() {
        FrozenModelScript script = parse("{\"turns\":[{\"for\":{\"stage\":\"NODE\"},\"text\":\"甲\"}]}");

        assertThat(script.declarationAt(0).stage()).isEqualTo("node");
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 0, 0)))
                .hasSize(1);
    }

    @Test
    void numbersInADeclarationAreReadAsTheirTextForm() {
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"node","nodeId":"n1","segmentSequence":2},"text":"甲"}]}
                """);

        assertThat(script.declarationAt(0).scope()).containsEntry("segmentSequence", "2");
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 2, 0)))
                .hasSize(1);
        assertThat(script.candidatesFor(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 3, 0)))
                .isEmpty();
    }

    @Test
    void declarationsAreListedInTurnOrderForErrorMessages() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"planning","planAttempt":1,"planPhase":"strategy"},"text":"甲"},
                  {"for":{"stage":"node","nodeId":"n1"},"text":"乙"}]}
                """);

        List<String> summary = script.declarationSummary();
        assertThat(summary).hasSize(2);
        assertThat(summary.get(0)).contains("回合 1").contains("planning");
        assertThat(summary.get(1)).contains("回合 2").contains("nodeId=n1");
    }
}
