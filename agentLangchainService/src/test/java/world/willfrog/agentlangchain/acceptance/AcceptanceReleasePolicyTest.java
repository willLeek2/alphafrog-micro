package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 夹具的放行策略怎么读：三种动作各自的含义、一份规则一条、认不出的字段一律拒绝。
 *
 * <p>这份策略是验收夹具里唯一能改成员收尾时机的输入，读法从严：写错了要在执行前就报出来，
 * 不能悄悄按「没有这条规则」放过去——那会让一次本该被压住的结果直接落终态，验收看起来还是通过的。</p>
 */
class AcceptanceReleasePolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void noRulesMeansTheFixtureDoesNotControlReleases() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("   ")).isEmpty();
        assertThat(parse("{\"members\":{}}")).as("一条成员都没点名等于不管放行").isEmpty();
        assertThat(parse("{\"maxHoldSeconds\":30}")).isEmpty();
    }

    @Test
    void theThreeActionsAreReadApart() {
        AcceptanceReleasePolicy policy = parse("""
                {"version":1,"maxHoldSeconds":30,"members":{
                  "call-hold":{"holdUntilPoint":"point-a"},
                  "call-after":{"releaseAfter":["call-hold","call-x"]},
                  "call-fail":{"fail":"这个场景要造一条失败成员"}}}""").orElseThrow();

        assertThat(policy.size()).isEqualTo(3);
        assertThat(policy.maxHoldSeconds()).isEqualTo(30);
        assertThat(policy.releasePointKey("call-hold")).contains("point-a");
        assertThat(policy.releaseAfter("call-after")).containsExactly("call-hold", "call-x");
        assertThat(policy.designatedFailure("call-fail")).contains("这个场景要造一条失败成员");
        // 三种动作各管一件事：点名压住的那条不该被当成要按失败收尾。
        assertThat(policy.designatedFailure("call-hold")).isEmpty();
        assertThat(policy.releasePointKey("call-fail")).isEmpty();
        assertThat(policy.releaseAfter("call-fail")).isEmpty();
        assertThat(policy.covers("call-hold")).isTrue();
        assertThat(policy.covers("call-other")).isFalse();
    }

    @Test
    void aMemberWithoutAnyRuleIsNotCovered() {
        AcceptanceReleasePolicy policy =
                parse("{\"members\":{\"call-a\":{\"holdUntilPoint\":\"point-a\"}}}").orElseThrow();

        assertThat(policy.covers("call-b")).isFalse();
        assertThat(policy.releasePointKey("call-b")).isEmpty();
        assertThat(policy.releaseAfter("call-b")).isEmpty();
        assertThat(policy.designatedFailure("call-b")).isEmpty();
    }

    @Test
    void maxHoldSecondsIsOptionalAndMustBePositive() {
        assertThat(parse("{\"members\":{\"call-a\":{\"fail\":\"故意失败\"}}}").orElseThrow()
                .maxHoldSeconds()).as("没写兜底就是不设兜底").isZero();
        assertThatThrownBy(() -> parse(
                "{\"maxHoldSeconds\":0,\"members\":{\"call-a\":{\"fail\":\"故意失败\"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("maxHoldSeconds");
    }

    @Test
    void aMemberWithTwoActionsIsRefused() {
        assertThatThrownBy(() -> parse(
                "{\"members\":{\"call-a\":{\"holdUntilPoint\":\"point-a\",\"fail\":\"故意失败\"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("恰好写一种动作");
    }

    @Test
    void unknownFieldsAreRefusedAtEveryLevel() {
        assertThatThrownBy(() -> parse("{\"members\":{\"call-a\":{\"fail\":\"x\"}},\"hold\":1}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("认不出的字段")
                .hasMessageContaining("hold");
        assertThatThrownBy(() -> parse("{\"members\":{\"call-a\":{\"holdUntillPoint\":\"p\"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("holdUntillPoint");
    }

    @Test
    void anUnknownVersionIsRefused() {
        assertThatThrownBy(() -> parse("{\"version\":2,\"members\":{\"call-a\":{\"fail\":\"x\"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("version 只认 1");
    }

    @Test
    void emptyActionValuesAreRefused() {
        assertThatThrownBy(() -> parse("{\"members\":{\"call-a\":{\"holdUntilPoint\":\"  \"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("holdUntilPoint");
        assertThatThrownBy(() -> parse("{\"members\":{\"call-a\":{\"releaseAfter\":[]}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("releaseAfter");
        assertThatThrownBy(() -> parse("{\"members\":{\" \":{\"fail\":\"x\"}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("工具调用身份是空的");
    }

    @Test
    void aBrokenDocumentIsRefusedWithTheFixtureNamed() {
        assertThatThrownBy(() -> parse("{不是 JSON"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("fx-policy");
        assertThatThrownBy(() -> parse("[1,2,3]"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("最外层要是一个 JSON 对象");
    }

    /** 等不出头的等待关系当场拒掉：自己等自己。 */
    @Test
    void aMemberWaitingForItselfIsRefused() {
        assertThatThrownBy(() -> parse("{\"members\":{\"call-a\":{\"releaseAfter\":[\"call-a\"]}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("call-a")
                .hasMessageContaining("等不到头");
    }

    /** 等不出头的等待关系当场拒掉：两条成员互相等；报错里要写清是哪一圈。 */
    @Test
    void aCycleOfWaitingMembersIsRefused() {
        assertThatThrownBy(() -> parse("{\"members\":{"
                + "\"call-a\":{\"releaseAfter\":[\"call-b\"]},"
                + "\"call-b\":{\"releaseAfter\":[\"call-a\"]}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("call-a → call-b → call-a");
    }

    /** 三条成员绕一圈、外加一条不相干的规则：照样能找出那一圈。 */
    @Test
    void aLongerCycleIsRefusedAndUnrelatedRulesDoNotConfuseIt() {
        assertThatThrownBy(() -> parse("{\"members\":{"
                + "\"call-x\":{\"fail\":\"不相干的一条\"},"
                + "\"call-a\":{\"releaseAfter\":[\"call-b\"]},"
                + "\"call-b\":{\"releaseAfter\":[\"call-c\"]},"
                + "\"call-c\":{\"releaseAfter\":[\"call-a\"]}}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("call-a → call-b → call-c → call-a");
    }

    /** 等一条不写规则的成员是正常写法：它不参与等待，也就连不成圈。 */
    @Test
    void waitingForAMemberWithoutARuleIsAccepted() {
        AcceptanceReleasePolicy policy = parse("{\"members\":{"
                + "\"call-a\":{\"releaseAfter\":[\"call-b\",\"call-c\"]}}}").orElseThrow();

        assertThat(policy.releaseAfter("call-a")).containsExactly("call-b", "call-c");
        assertThat(policy.covers("call-b")).isFalse();
    }

    private Optional<AcceptanceReleasePolicy> parse(String json) {
        return AcceptanceReleasePolicy.parse("fx-policy", json, objectMapper);
    }
}
