package world.willfrog.agent.platform.childrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitMember;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildRunAcceptanceControlsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactParentMemberIdentityBindsOnlyTheSpecifiedChild() throws Exception {
        var context = json.readTree("""
                {"childAcceptanceControls":[
                  {"for":{"planGeneration":2,"nodeId":"branch-a","nodeAttempt":1,
                          "segmentSequence":3,"modelTurn":4,"memberSeq":0,"toolCallId":"call-a"},
                   "acceptanceControlId":"control-a"},
                  {"for":{"planGeneration":2,"nodeId":"branch-a","nodeAttempt":1,
                          "segmentSequence":3,"modelTurn":4,"memberSeq":1,"toolCallId":"call-b"},
                   "acceptanceControlId":"control-b"}]}
                """);
        var first = identity(2, "branch-a", 1, 3, 4, 0, "call-a");

        assertThat(ChildRunAcceptanceControls.controlFor(context, first)).contains("control-a");
        assertThat(ChildRunAcceptanceControls.controlFor(context,
                identity(2, "branch-a", 1, 3, 4, 1, "call-b"))).contains("control-b");
        assertThat(ChildRunAcceptanceControls.controlFor(context,
                identity(2, "branch-a", 1, 3, 5, 0, "call-a"))).isEmpty();
        assertThat(ChildRunAcceptanceControls.controlFor(context,
                identity(2, "branch-a", 1, 4, 4, 0, "call-a"))).isEmpty();
        assertThat(ChildRunAcceptanceControls.controlFor(context,
                identity(3, "branch-a", 1, 3, 4, 0, "call-a"))).isEmpty();
        assertThat(ChildRunAcceptanceControls.controlFor(context,
                identity(2, "branch-b", 1, 3, 4, 0, "call-a"))).isEmpty();
    }

    @Test
    void overlappingOrReusedBindingsAreRejected() throws Exception {
        String one = """
                {"for":{"planGeneration":0,"nodeId":"n","nodeAttempt":0,
                        "segmentSequence":0,"modelTurn":0,"memberSeq":0,"toolCallId":"call-a"},
                 "acceptanceControlId":"control-a"}
                """.trim();
        assertThatThrownBy(() -> ChildRunAcceptanceControls.parse(
                json.readTree("{\"childAcceptanceControls\":[" + one + "," + one + "]}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复绑定");
        String another = one.replace("memberSeq\":0", "memberSeq\":1")
                .replace("call-a", "call-b");
        assertThatThrownBy(() -> ChildRunAcceptanceControls.parse(
                json.readTree("{\"childAcceptanceControls\":[" + one + "," + another + "]}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一控制编号");
    }

    @Test
    void incompleteSelectorIsRejectedBeforeAnyChildRuns() throws Exception {
        var context = json.readTree("""
                {"childAcceptanceControls":[{"for":{"nodeId":"n","memberSeq":0},
                  "acceptanceControlId":"control-a"}]}
                """);
        assertThatThrownBy(() -> ChildRunAcceptanceControls.parse(context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("字段必须恰好");
    }

    @Test
    void persistedIntentMustMatchItsWaitGroupAndSpawnMember() {
        ChildRunOutboxDelivery delivery = new ChildRunOutboxDelivery(9, 7, "token", "child", "op",
                "root", "parent", 4, "member-a", "node-a", "call-a",
                2, 1, 0, "goal", "", "model", "endpoint", 6,
                "DUAL_POOL_V2", "lane", "generation", "digest");
        WaitGroup group = group(4, "parent", 2, "node-a", 1, 3, 4);
        WaitMember member = member(4, "parent", "member-a", 0, "call-a", "spawnSubAgent");

        assertThat(ChildRunAcceptanceControls.requireMemberIdentity(delivery, group, member))
                .isEqualTo(identity(2, "node-a", 1, 3, 4, 0, "call-a"));
        member.setToolCallId("another-call");
        assertThatThrownBy(() -> ChildRunAcceptanceControls.requireMemberIdentity(delivery, group, member))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("身份不一致");
        member.setToolCallId("call-a");
        group.setSegmentSequence(null);
        assertThatThrownBy(() -> ChildRunAcceptanceControls.requireMemberIdentity(delivery, group, member))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("身份不一致");
    }

    private static ChildRunAcceptanceControls.MemberIdentity identity(int plan, String node,
                                                                        int attempt, int segment,
                                                                        int turn, int sequence,
                                                                        String call) {
        return new ChildRunAcceptanceControls.MemberIdentity(plan, node, attempt, segment, turn,
                sequence, call);
    }

    private static WaitGroup group(long id, String run, int plan, String node,
                                   int attempt, int segment, int turn) {
        WaitGroup group = new WaitGroup();
        group.setId(id);
        group.setRunId(run);
        group.setPlanGeneration(plan);
        group.setNodeId(node);
        group.setNodeAttempt(attempt);
        group.setSegmentSequence(segment);
        group.setModelTurn(turn);
        return group;
    }

    private static WaitMember member(long groupId, String run, String identity, int sequence,
                                     String call, String tool) {
        WaitMember member = new WaitMember();
        member.setGroupId(groupId);
        member.setRunId(run);
        member.setMemberIdentity(identity);
        member.setMemberSeq(sequence);
        member.setToolCallId(call);
        member.setToolName(tool);
        return member;
    }
}
