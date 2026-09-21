package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 等待组、等待成员、恢复通知三个取值集合，以及成员稳定身份的生成规则。
 */
class WaitContractValuesTest {

    @Test
    void groupStatesAreWaitingReadyResumedCanceled() {
        assertThat(WaitGroupState.allWireValues())
                .containsExactly("WAITING", "READY", "RESUMED", "CANCELED");
        assertThat(WaitGroupState.WAITING.acceptsMemberResult()).isTrue();
        assertThat(WaitGroupState.READY.acceptsMemberResult()).isFalse();
        assertThat(WaitGroupState.RESUMED.acceptsMemberResult()).isFalse();
        assertThat(WaitGroupState.CANCELED.acceptsMemberResult()).isFalse();
        assertThat(WaitGroupState.CANCELED.isTerminal()).isTrue();
        assertThat(WaitGroupState.RESUMED.isTerminal())
                .as("交接给下一段之后这个组的事情就办完了，不会再迁移、也不会再产生恢复资格")
                .isTrue();
        assertThat(WaitGroupState.READY.isTerminal())
                .as("齐备的组还等着分发器来接手，不算办完")
                .isFalse();
        assertThat(WaitGroupState.WAITING.isTerminal()).isFalse();
    }

    @Test
    void memberStatesSeparateEndedFromCounted() {
        assertThat(WaitMemberState.allWireValues())
                .containsExactly("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "LATE");
        assertThat(WaitMemberState.terminalWireValues())
                .containsExactly("SUCCEEDED", "FAILED", "CANCELED", "LATE");
        assertThat(WaitMemberState.completedWireValues())
                .as("只有成功与失败算组的一次有效结束")
                .containsExactly("SUCCEEDED", "FAILED");
        assertThat(WaitMemberState.CANCELED.isTerminal()).isTrue();
        assertThat(WaitMemberState.CANCELED.countsAsCompleted())
                .as("被取消的成员即使已经终态，也不能把组推向恢复")
                .isFalse();
        assertThat(WaitMemberState.LATE.countsAsCompleted()).isFalse();
        assertThat(WaitMemberState.RUNNING.isTerminal()).isFalse();
    }

    @Test
    void notificationStatesAreWaitingConsumedCanceled() {
        assertThat(RecoveryNotificationState.allWireValues())
                .containsExactly("WAITING", "CONSUMED", "CANCELED");
        assertThat(RecoveryNotificationState.WAITING.isTerminal()).isFalse();
        assertThat(RecoveryNotificationState.CONSUMED.isTerminal()).isTrue();
    }

    @Test
    void unknownValuesFailClosed() {
        assertThatThrownBy(() -> WaitGroupState.fromWire("DONE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DONE");
        assertThatThrownBy(() -> WaitMemberState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryNotificationState.fromWire(" waiting "))
                .as("前后空白会被去掉，大小写不一致仍然算不认识")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaitMemberState.fromWire(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 成员稳定身份 =====

    @Test
    void memberIdentityUsesToolCallIdWhenModelProvidedOne() {
        WaitGroupIdentity group = group(3);
        assertThat(WaitMemberIdentity.stableIdentity(" call_3 ", group, 0))
                .as("模型给了身份就用它，只去掉首尾空白")
                .isEqualTo("call_3");
        assertThat(WaitMemberIdentity.isDerived("call_3")).isFalse();
    }

    @Test
    void memberIdentityIsDerivedDeterministicallyWhenMissing() {
        WaitGroupIdentity group = group(3);
        String first = WaitMemberIdentity.stableIdentity(null, group, 0);
        String second = WaitMemberIdentity.stableIdentity("   ", group, 0);
        assertThat(first).as("没给身份时重算必须一样，不能带时钟或随机数").isEqualTo(second);
        assertThat(WaitMemberIdentity.isDerived(first)).isTrue();
        assertThat(first)
                .as("派生身份是定长摘要：长度与组身份多长无关")
                .startsWith(WaitMemberIdentity.DERIVED_PREFIX)
                .hasSize(WaitMemberIdentity.DERIVED_IDENTITY_LENGTH);
        assertThat(WaitMemberIdentity.DERIVED_IDENTITY_LENGTH)
                .as("定长身份不可能超过库里那一列")
                .isLessThanOrEqualTo(WaitMemberIdentity.MAX_LENGTH);
    }

    @Test
    void derivedIdentitySurvivesTheLongestNodeIdentity() {
        String longNodeId = "n".repeat(256);
        WaitGroupIdentity longest = new WaitGroupIdentity(
                new NodeWorkItemIdentity("r".repeat(64), 7, longNodeId, 1, 3), 12);
        String identity = WaitMemberIdentity.stableIdentity(null, longest, 5);
        assertThat(identity)
                .as("节点身份本身可以顶到 256 个字符，派生身份不能因此超长")
                .hasSize(WaitMemberIdentity.DERIVED_IDENTITY_LENGTH)
                .isEqualTo(WaitMemberIdentity.stableIdentity(null, longest, 5));
        assertThat(identity).doesNotContain(longNodeId.substring(0, 32))
                .as("派生身份不搬原文，只搬摘要");
    }

    @Test
    void derivedIdentityChangesWithEveryIdentityField() {
        WaitGroupIdentity base = group(3);
        String baseline = WaitMemberIdentity.stableIdentity(null, base, 0);
        List<WaitGroupIdentity> changed = List.of(
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-2", 7, "node-9", 1, 3), 3),
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 8, "node-9", 1, 3), 3),
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 7, "node-10", 1, 3), 3),
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 7, "node-9", 2, 3), 3),
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 7, "node-9", 1, 4), 3),
                new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 7, "node-9", 1, 3), 4));
        for (WaitGroupIdentity other : changed) {
            assertThat(WaitMemberIdentity.stableIdentity(null, other, 0))
                    .as("组身份的任一字段不同，派生身份就必须不同：" + other.describe())
                    .isNotEqualTo(baseline);
        }
        assertThat(WaitMemberIdentity.stableIdentity(null, base, 1))
                .as("同一个组里不同序号的成员不能算出同一个身份")
                .isNotEqualTo(baseline);
    }

    @Test
    void derivedIdentityDiffersBySequenceAndByGroup() {
        WaitGroupIdentity group = group(3);
        assertThat(WaitMemberIdentity.stableIdentity(null, group, 0))
                .isNotEqualTo(WaitMemberIdentity.stableIdentity(null, group, 1));
        WaitGroupIdentity nextTurn = new WaitGroupIdentity(group.segment(), 4);
        assertThat(WaitMemberIdentity.stableIdentity(null, group, 0))
                .as("下一个模型回合属于另一个等待组，派生身份不会撞")
                .isNotEqualTo(WaitMemberIdentity.stableIdentity(null, nextTurn, 0));
    }

    @Test
    void sameToolCallIdInAnotherGroupIsNotAConflict() {
        WaitGroupIdentity first = group(1);
        WaitGroupIdentity second = group(2);
        assertThat(WaitMemberIdentity.stableIdentity("call_3", first, 0))
                .as("唯一范围只在组内，跨组复用同一原始调用身份是允许的")
                .isEqualTo(WaitMemberIdentity.stableIdentity("call_3", second, 0));
    }

    @Test
    void identityLongerThanColumnFailsClosed() {
        WaitGroupIdentity group = group(3);
        assertThatThrownBy(() -> WaitMemberIdentity.stableIdentity("x".repeat(257), group, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度上限");
        assertThatThrownBy(() -> WaitMemberIdentity.stableIdentity(null, group, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaitMemberIdentity.stableIdentity(null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupIdentityRejectsNegativeModelTurn() {
        assertThatThrownBy(() -> new WaitGroupIdentity(group(0).segment(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitGroupIdentity(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(group(3).describe()).isEqualTo("run-1/g7/node-9/a1/s3/t3");
    }

    private static WaitGroupIdentity group(int modelTurn) {
        return new WaitGroupIdentity(new NodeWorkItemIdentity("run-1", 7, "node-9", 1, 3), modelTurn);
    }
}
