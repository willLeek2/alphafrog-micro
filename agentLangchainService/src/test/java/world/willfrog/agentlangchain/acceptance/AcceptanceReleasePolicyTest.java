package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 夹具的放行策略怎么读、怎么点名：三种动作各自的含义、点名要写清是哪一次调用、认不出的字段一律拒绝。
 *
 * <p>这份策略是验收夹具里唯一能改成员收尾时机的输入，读法从严：写错了要在执行前就报出来，
 * 不能悄悄按「没有这条规则」放过去——那会让一次本该被压住的结果直接落终态，验收看起来还是通过的。</p>
 *
 * <p>点名用选择器而不是光写工具调用编号：编号是模型给的，同一个编号在别的节点、别的分段里可以再出现
 * 一次。这里逐条钉住「换个组、换个节点就不该被命中」。</p>
 */
class AcceptanceReleasePolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void noRulesMeansTheFixtureDoesNotControlReleases() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("   ")).isEmpty();
        assertThat(parse("{\"rules\":[]}")).as("一条规则都没写等于不管放行").isEmpty();
        assertThat(parse("{\"maxHoldSeconds\":30}")).isEmpty();
    }

    @Test
    void theThreeActionsAreReadApart() {
        AcceptanceReleasePolicy policy = parse("""
                {"version":2,"maxHoldSeconds":30,"rules":[
                  {"for":{"nodeId":"n1","segmentSequence":0,"memberSeq":0},"holdUntilPoint":"point-a"},
                  {"for":{"nodeId":"n1","segmentSequence":0,"memberSeq":1},
                   "releaseAfter":[{"memberSeq":0},{"memberSeq":2}]},
                  {"for":{"nodeId":"n1","segmentSequence":0,"memberSeq":2},"fail":"这个场景要造一条失败成员"}]}
                """).orElseThrow();

        assertThat(policy.fixtureId()).isEqualTo("fx-policy");
        assertThat(policy.ruleCount()).isEqualTo(3);
        assertThat(policy.maxHoldSeconds()).isEqualTo(30);
        assertThat(policy.rules().get(0).holds()).isTrue();
        assertThat(policy.rules().get(0).holdReleaseKey()).isEqualTo("point-a");
        assertThat(policy.rules().get(1).waitsForPeers()).isTrue();
        assertThat(policy.rules().get(2).fails()).isTrue();
        assertThat(policy.rules().get(2).failureDetail()).isEqualTo("这个场景要造一条失败成员");
        assertThat(policy.rules().get(0).selectorText())
                .as("选择器的写法按字段名字母序，报错与命中记录里都是这一串")
                .isEqualTo("memberSeq=0;nodeId=n1;segmentSequence=0");
    }

    @Test
    void theOldShapeIsRefusedWithAHint() {
        assertThatThrownBy(() -> parse("""
                {"version":1,"members":{"call-1":{"holdUntilPoint":"point-a"}}}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("members")
                .hasMessageContaining("rules");
    }

    /** 只写工具调用编号定位不到唯一一条成员：别的节点、别的分段里可以再出现同一个编号。 */
    @Test
    void aSelectorThatOnlyNamesTheToolCallIdIsRefused() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"toolCallId":"call-1"},"holdUntilPoint":"point-a"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("toolCallId=call-1")
                .hasMessageContaining("只写了组内成员那一段");
    }

    /** 只写等待组那一级会命中这个组里的每一条成员：说不清点名的是哪一条。 */
    @Test
    void aSelectorThatOnlyNamesTheGroupIsRefused() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","segmentSequence":0},"fail":"故意失败"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("只写了等待组那一段")
                .hasMessageContaining("memberSeq 或 toolCallId");
    }

    /** 字段名打错会一条成员都打不中，所以读策略这一步就把认不出的字段名点出来。 */
    @Test
    void misspelledSelectorFieldsAreRefused() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeID":"n1","memberSeq":0},"holdUntilPoint":"point-a"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("认不出的字段")
                .hasMessageContaining("nodeID")
                .hasMessageContaining("nodeId");
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","member":0},"holdUntilPoint":"point-a"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("认不出的字段")
                .hasMessageContaining("member");
    }

    @Test
    void aRuleMustNameExactlyOneAction() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},
                           "holdUntilPoint":"point-a","fail":"故意失败"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("恰好写一种动作");
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0}}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("恰好写一种动作");
    }

    @Test
    void emptyActionValuesAreRefused() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"holdUntilPoint":"  "}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("holdUntilPoint");
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("releaseAfter");
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":["call-2"]}]}
                """))
                .as("等谁也要写成选择器：光写编号同样定位不到")
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("releaseAfter 第 0 项");
    }

    @Test
    void aSelectorWithoutAMemberPartIsRefusedInPeersToo() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"nodeId":"n1"}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("releaseAfter 第 0 项")
                .hasMessageContaining("哪一条成员");
    }

    @Test
    void anUnknownVersionIsRefused() {
        assertThatThrownBy(() -> parse("""
                {"version":1,"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"x"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("version 只认 2");
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
        assertThatThrownBy(() -> parse("{\"rules\":{}}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("rules 要是一个数组");
        assertThatThrownBy(() -> parse("{\"rules\":[{\"fail\":\"x\"}]}"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("没有写 for 选择器");
    }

    @Test
    void maxHoldSecondsIsOptionalAndMustBePositive() {
        assertThat(parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"故意失败"}]}
                """).orElseThrow().maxHoldSeconds()).as("没写兜底就是不设兜底").isZero();
        assertThatThrownBy(() -> parse("""
                {"maxHoldSeconds":0,"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"故意失败"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("maxHoldSeconds");
    }

    @Test
    void unknownPolicyFieldsAreRefused() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"x"}],"hold":1}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("认不出的字段")
                .hasMessageContaining("hold");
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"holdUntillPoint":"p"}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("holdUntillPoint");
    }

    @Test
    void theDigestFollowsThePolicyText() {
        AcceptanceReleasePolicy first = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"x"}]}
                """).orElseThrow();
        AcceptanceReleasePolicy same = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"x"}]}
                """).orElseThrow();
        AcceptanceReleasePolicy changed = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"y"}]}
                """).orElseThrow();

        assertThat(first.digest()).isEqualTo(same.digest()).hasSize(64);
        assertThat(changed.digest()).isNotEqualTo(first.digest());
    }

    // ==================== 匹配 ====================

    /** 换一个分段就是另一条成员：同一个工具调用编号连着两组出现时，规则只该打中写明的那个分段。 */
    @Test
    void aSelectorPinsTheSegmentSoTheSameCallIdInTheNextGroupIsNotHit() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","segmentSequence":0,"toolCallId":"call_1"},
                           "holdUntilPoint":"point-a"}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> firstGroup = List.of(
                member("n1", 0, 1, 0, "call_1"));
        List<AcceptanceReleasePolicy.MemberFacts> secondGroup = List.of(
                member("n1", 1, 2, 0, "call_1"));

        assertThat(policy.match(firstGroup).targetOf(0)).contains(firstGroup.get(0));
        assertThat(policy.match(secondGroup).targetOf(0))
                .as("下一段的同编号成员不该被这条规则打中")
                .isEmpty();
        assertThat(policy.match(secondGroup).isEmpty()).isTrue();
    }

    /** 换一个节点就是另一条成员：两个节点各自从 call_1 开始编号时，规则只该打中写明的那个节点。 */
    @Test
    void aSelectorPinsTheNodeSoAnotherNodesSameCallIdIsNotHit() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"fail":"这个节点上这条按失败算"}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> otherNode =
                List.of(member("n2", 0, 0, 0, "call_1"));

        assertThat(policy.match(otherNode).isEmpty()).isTrue();
        assertThat(policy.match(List.of(member("n1", 0, 0, 0, "call_1"))).targetOf(0)).isPresent();
    }

    /** 数值型的身份字段写数字也能对上：夹具作者写 0 或 "0" 是同一个意思。 */
    @Test
    void numericSelectorValuesAreMatchedAsNumbers() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","modelTurn":"1","memberSeq":0},"holdUntilPoint":"point-a"}]}
                """).orElseThrow();

        assertThat(policy.match(List.of(member("n1", 0, 1, 0, "call_1"))).targetOf(0)).isPresent();
        assertThat(policy.match(List.of(member("n1", 0, 2, 0, "call_1"))).isEmpty()).isTrue();
    }

    /** 一条规则同时打中两条成员：点名对象不确定，当场拒绝。 */
    @Test
    void oneRuleHittingTwoMembersIsRefused() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","toolCallId":"call_1"},"holdUntilPoint":"point-a"}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> group = List.of(
                member("n1", 0, 0, 0, "call_1"),
                member("n1", 0, 0, 1, "call_1"));

        assertThatThrownBy(() -> policy.match(group))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_ambiguous")
                .hasMessageContaining("同时命中同一批里的 2 条成员");
    }

    /** 两条规则点名同一条成员：会互相盖掉对方，当场拒绝。 */
    @Test
    void twoRulesPointingAtOneMemberAreRefused() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[
                  {"for":{"nodeId":"n1","memberSeq":0},"holdUntilPoint":"point-a"},
                  {"for":{"nodeId":"n1","toolCallId":"call_1"},"fail":"按失败算"}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> group = List.of(member("n1", 0, 0, 0, "call_1"));

        assertThatThrownBy(() -> policy.match(group))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_ambiguous")
                .hasMessageContaining("点名了同一条成员");
    }

    /** 同一条成员被两条规则点名时，规则序号按策略里的先后报出来，排查时能直接找到那两行。 */
    @Test
    void rulesAtFindsTheRuleThatNamesThisMember() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[
                  {"for":{"nodeId":"n1","memberSeq":0},"holdUntilPoint":"point-a"},
                  {"for":{"nodeId":"n1","memberSeq":1},"fail":"按失败算"}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> group = List.of(
                member("n1", 0, 0, 0, "call_1"), member("n1", 0, 0, 1, "call_2"));
        AcceptanceReleasePolicy.RuleMatches matches = policy.match(group);

        assertThat(policy.ruleAt(matches, group.get(1))).get()
                .satisfies(rule -> assertThat(rule.index()).isEqualTo(1));
        assertThat(policy.ruleAt(matches, member("n1", 0, 0, 7, "call-other"))).isEmpty();
    }

    // ==================== 等兄弟成员 ====================

    @Test
    void peersResolveInsideTheSameGroup() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"memberSeq":2}]}]}
                """).orElseThrow();
        Availability group = group();

        assertThat(policy.peersOf(policy.rules().get(0), group.first(), group.members()))
                .containsExactly(group.members().get(2));
    }

    /** 等组里没有的成员：这条等待永远等不到头，当场说清是哪个选择器。 */
    @Test
    void aPeerThatIsNotInTheGroupIsRefused() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"memberSeq":9}]}]}
                """).orElseThrow();
        Availability group = group();

        assertThatThrownBy(() -> policy.peersOf(policy.rules().get(0), group.first(), group.members()))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_peer_unknown")
                .hasMessageContaining("memberSeq=9");
    }

    /** 等谁的选择器对上两条成员（编号重了）：夹具写不清等的是哪一条。 */
    @Test
    void anAmbiguousPeerIsRefused() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"toolCallId":"call_1"}]}]}
                """).orElseThrow();
        List<AcceptanceReleasePolicy.MemberFacts> group = List.of(
                member("n1", 0, 0, 0, "call_1"),
                member("n1", 0, 0, 1, "call_1"),
                member("n1", 0, 0, 2, "call_3"));

        assertThatThrownBy(() -> policy.peersOf(policy.rules().get(0), group.get(0), group))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_ambiguous")
                .hasMessageContaining("对上了 2 条成员");
    }

    /** 等自己：写法不同也要当场拒掉，因为拿到这个组就能算出等的是它自己。 */
    @Test
    void waitingForItselfIsRefusedOnceTheGroupIsKnown() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"memberSeq":0}]}]}
                """).orElseThrow();
        Availability group = group();

        assertThatThrownBy(() -> policy.peersOf(policy.rules().get(0), group.first(), group.members()))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("要等自己")
                .hasMessageContaining("等不到头");
    }

    /** 写成一模一样的自等：读策略这一步就拒掉，不用等到派发。 */
    @Test
    void aSelfWaitWrittenIdenticallyIsRefusedAtParseTime() {
        assertThatThrownBy(() -> parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},
                           "releaseAfter":[{"nodeId":"n1","memberSeq":0}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("等不到头");
    }

    /** 写法一样的两条规则互相等：读策略这一步就能看出这一圈。 */
    @Test
    void aCycleWrittenIdenticallyIsRefusedAtParseTime() {
        assertThatThrownBy(() -> parse("""
                {"rules":[
                  {"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"nodeId":"n1","memberSeq":1}]},
                  {"for":{"nodeId":"n1","memberSeq":1},"releaseAfter":[{"nodeId":"n1","memberSeq":0}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("memberSeq=0;nodeId=n1 → memberSeq=1;nodeId=n1 → memberSeq=0;nodeId=n1");
    }

    /** 三条规则绕一圈、外加一条不相干的规则：照样能找出那一圈。 */
    @Test
    void aLongerCycleIsRefusedAndUnrelatedRulesDoNotConfuseIt() {
        assertThatThrownBy(() -> parse("""
                {"rules":[
                  {"for":{"nodeId":"n1","memberSeq":7},"fail":"不相干的一条"},
                  {"for":{"nodeId":"n1","memberSeq":0},"releaseAfter":[{"nodeId":"n1","memberSeq":1}]},
                  {"for":{"nodeId":"n1","memberSeq":1},"releaseAfter":[{"nodeId":"n1","memberSeq":2}]},
                  {"for":{"nodeId":"n1","memberSeq":2},"releaseAfter":[{"nodeId":"n1","memberSeq":0}]}]}
                """))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_invalid")
                .hasMessageContaining("互相等成了一整圈");
    }

    /**
     * 写法不同的那一圈：拿到这个等待组的成员才判得出来。
     *
     * <p>两条成员互相等，一条写成「哪个节点哪一条」、另一条只写「组里第几条」，读策略时看不出它们
     * 指的是同两条成员；到了派发前，这个组里到底有哪几条成员摆出来了，当场就能拒掉。</p>
     */
    @Test
    void aCycleWrittenDifferentlyIsRefusedOnceTheGroupIsKnown() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[
                  {"for":{"nodeId":"n1","segmentSequence":0,"memberSeq":0},"releaseAfter":[{"memberSeq":1}]},
                  {"for":{"nodeId":"n1","segmentSequence":0,"memberSeq":1},
                   "releaseAfter":[{"nodeId":"n1","segmentSequence":0,"memberSeq":0}]}]}
                """).orElseThrow();
        Availability group = group();

        assertThatThrownBy(() -> policy.rejectCyclesInGroup(group.members(),
                policy.match(group.members())))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_policy_ambiguous")
                .hasMessageContaining("这个等待组内的等待关系绕成了一整圈")
                .hasMessageContaining("第 1 条成员（call_2）");
    }

    /** 等一条不写规则的成员是正常写法：它不参与等待，也就连不成圈。 */
    @Test
    void waitingForAMemberWithoutARuleIsAccepted() {
        AcceptanceReleasePolicy policy = parse("""
                {"rules":[{"for":{"nodeId":"n1","memberSeq":0},
                           "releaseAfter":[{"memberSeq":1},{"memberSeq":2}]}]}
                """).orElseThrow();
        Availability group = group();

        assertThat(policy.peersOf(policy.rules().get(0), group.first(), group.members()))
                .containsExactly(group.members().get(1), group.members().get(2));
        policy.rejectCyclesInGroup(group.members(), policy.match(group.members()));
    }

    private Availability group() {
        List<AcceptanceReleasePolicy.MemberFacts> members = List.of(
                member("n1", 0, 0, 0, "call_1"),
                member("n1", 0, 0, 1, "call_2"),
                member("n1", 0, 0, 2, "call_3"));
        return new Availability(members);
    }

    /** 一个等待组里的成员，外加第一条成员这个常用写法。 */
    private record Availability(List<AcceptanceReleasePolicy.MemberFacts> members) {

        AcceptanceReleasePolicy.MemberFacts first() {
            return members.get(0);
        }
    }

    private static AcceptanceReleasePolicy.MemberFacts member(String nodeId,
                                                             int segmentSequence,
                                                             int modelTurn,
                                                             int memberSeq,
                                                             String toolCallId) {
        return new AcceptanceReleasePolicy.MemberFacts(nodeId, 0, segmentSequence, modelTurn, memberSeq,
                toolCallId);
    }

    private Optional<AcceptanceReleasePolicy> parse(String json) {
        return AcceptanceReleasePolicy.parse("fx-policy", json, objectMapper);
    }
}
