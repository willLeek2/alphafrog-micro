package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态核对：脚本里声明为必答的回合没有被领走、或者放行策略点名的规则一条都没打中时，
 * 这一次验收不能算通过。
 */
class FixtureScenarioVerdictTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyRequiredTurnBeingClaimedIsTheOnlyPass() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"planning","planPhase":"strategy"},"text":"策略"},
                  {"for":{"stage":"node","nodeId":"n1"},"text":"节点"},
                  {"for":{"stage":"answer"},"text":"答案"}]}
                """);

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(script, Set.of(0, 1, 2));

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.COMPLETE);
        assertThat(verdict.missing()).isEmpty();
        assertThat(verdict.describeMissing()).isNull();
        assertThat(verdict.consumed()).hasSize(3);
        assertThat(verdict.consumed().get(1)).contains("nodeId=n1");
    }

    @Test
    void aRequiredTurnThatNeverHappenedIsNamedInsteadOfBeingSilentlyDropped() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1"},"text":"节点一"},
                  {"for":{"stage":"node","nodeId":"n2"},"text":"节点二"},
                  {"for":{"stage":"answer"},"text":"答案"}]}
                """);

        // 少了节点二那一次：前面的回复照样让 Run 走到终态，这一次验收不能被算成通过。
        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(script, Set.of(0, 2));

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.SCRIPT_INCOMPLETE);
        assertThat(verdict.missing()).hasSize(1);
        assertThat(verdict.missing().get(0)).contains("回合 2").contains("nodeId=n2");
        assertThat(verdict.describeMissing()).contains("实际没有发生");
        assertThat(verdict.consumed()).hasSize(2);
    }

    @Test
    void anOptionalTurnThatNeverHappenedIsNotMissing() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1"},"text":"必答"},
                  {"for":{"stage":"node","nodeId":"n1","modelTurn":1},"optional":true,"text":"可以不发生"}]}
                """);

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(script, Set.of(0));

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.COMPLETE);
        assertThat(verdict.missing()).isEmpty();
        assertThat(verdict.consumed()).hasSize(1);
    }

    @Test
    void nothingClaimedMeansEveryRequiredTurnIsMissing() {
        FrozenModelScript script = parse("""
                {"turns":[
                  {"for":{"stage":"node","nodeId":"n1"},"text":"甲"},
                  {"for":{"stage":"node","nodeId":"n1"},"optional":true,"text":"乙"}]}
                """);

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(script, Set.of());

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.SCRIPT_INCOMPLETE);
        assertThat(verdict.missing()).hasSize(1);
        assertThat(verdict.missing().get(0)).contains("回合 1");
    }

    @Test
    void theSnapshotDeclarationsAreWhatTheVerdictIsComputedFrom() {
        // 终态核对读的是当初落库的声明快照（夹具行可能已经回收），所以这里直接用声明清单算。
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"answer"},"text":"答案"}]}
                """);

        FixtureScenarioVerdict fromDeclarations =
                FixtureScenarioVerdict.evaluate(script.declarations(), Set.of(0));
        FixtureScenarioVerdict fromScript = FixtureScenarioVerdict.evaluate(script, Set.of(0));

        assertThat(fromDeclarations).isEqualTo(fromScript);
        assertThat(fromDeclarations.consumed()).isEqualTo(List.of(script.declarationAt(0).describe()));
    }

    /** 放行策略点了名、但一条成员都没打中：夹具写错了字段名时就是这样，同样算这次验收没跑全。 */
    @Test
    void aRuleThatNeverHitAnyMemberIsNamedInsteadOfBeingSilentlyIgnored() {
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"answer"},"text":"答案"}]}
                """);
        List<FixtureRuleHitStore.RuleFact> rules = List.of(
                new FixtureRuleHitStore.RuleFact(0, "holdUntilPoint", "memberSeq=0;nodeId=n1"),
                new FixtureRuleHitStore.RuleFact(1, "fail", "memberSeq=1;nodeId=n1"));

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(
                script.declarations(), rules, Set.of(0), Set.of(0));

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.SCRIPT_INCOMPLETE);
        assertThat(verdict.missing()).as("必答回合都发生了").isEmpty();
        assertThat(verdict.missingRules()).singleElement()
                .satisfies(rule -> assertThat(rule)
                        .contains("第 1 条规则")
                        .contains("memberSeq=1;nodeId=n1"));
        assertThat(verdict.describeMissing()).contains("一次都没打中任何成员");
    }

    /** 两路都对上才算通过：回合都发生了、点名的规则也都打中了成员。 */
    @Test
    void bothTheTurnsAndTheRulesMustHaveHappened() {
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"answer"},"text":"答案"}]}
                """);
        List<FixtureRuleHitStore.RuleFact> rules =
                List.of(new FixtureRuleHitStore.RuleFact(0, "fail", "memberSeq=0;nodeId=n1"));

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(
                script.declarations(), rules, Set.of(0), Set.of(0));

        assertThat(verdict.verdict()).isEqualTo(FixtureScenarioVerdict.COMPLETE);
        assertThat(verdict.missing()).isEmpty();
        assertThat(verdict.missingRules()).isEmpty();
        assertThat(verdict.describeMissing()).as("没有缺的东西就不写说明").isNull();
    }

    /** 脚本与策略两路都没跑全时，说明里两样都要写清。 */
    @Test
    void theDetailNamesBothKindsOfGaps() {
        FrozenModelScript script = parse("""
                {"turns":[{"for":{"stage":"answer"},"text":"答案"}]}
                """);
        List<FixtureRuleHitStore.RuleFact> rules =
                List.of(new FixtureRuleHitStore.RuleFact(0, "fail", "memberSeq=0;nodeId=n1"));

        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(
                script.declarations(), rules, Set.of(), Set.of());

        assertThat(verdict.describeMissing())
                .contains("实际没有发生")
                .contains("一次都没打中任何成员");
    }

    private FrozenModelScript parse(String json) {
        return FrozenModelScript.parse("fx-1", json, objectMapper);
    }
}
