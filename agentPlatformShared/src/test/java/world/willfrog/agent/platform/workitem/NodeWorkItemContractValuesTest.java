package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态与调度器版本这两个取值集合的合同：终态是哪几个、保留值是哪几个、未知取值必须失败关闭。
 */
class NodeWorkItemContractValuesTest {

    @Test
    void terminalStatesAreTheFourEndedOnes() {
        assertThat(NodeWorkItemState.terminalWireValues())
                .containsExactly("RESULT_COMMITTED", "EXECUTION_FAILED", "CANCELED", "STALE");
        assertThat(NodeWorkItemState.RUNNABLE.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.CLAIMED.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.EXECUTING.isTerminal()).isFalse();
    }

    @Test
    void waitingAndResumableAreLiveNonTerminalStates() {
        assertThat(NodeWorkItemState.WAITING.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.RESUMABLE.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.terminalWireValues())
                .doesNotContain("WAITING", "RESUMABLE");
    }

    @Test
    void onlyRunnableAndResumableCanBeClaimed() {
        assertThat(NodeWorkItemState.claimableWireValues())
                .as("能被领取的只有首次可运行与结果齐备后可恢复两个状态")
                .containsExactly("RUNNABLE", "RESUMABLE");
        assertThat(NodeWorkItemState.allWireValues())
                .as("可领取状态必须都在取值集合里")
                .containsAll(NodeWorkItemState.claimableWireValues());
        assertThat(NodeWorkItemState.terminalWireValues())
                .as("终态不能被领取")
                .doesNotContainAnyElementsOf(NodeWorkItemState.claimableWireValues());
    }

    @Test
    void allWireValuesCoverAllNineStates() {
        List<String> values = NodeWorkItemState.allWireValues();
        assertThat(values).hasSize(9);
        assertThat(values).containsAll(List.of("RUNNABLE", "CLAIMED", "EXECUTING", "RESULT_COMMITTED",
                "EXECUTION_FAILED", "CANCELED", "STALE", "WAITING", "RESUMABLE"));
    }

    @Test
    void unknownStateFailsClosed() {
        assertThat(NodeWorkItemState.fromWire("RUNNABLE")).isEqualTo(NodeWorkItemState.RUNNABLE);
        assertThatThrownBy(() -> NodeWorkItemState.fromWire("DONE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DONE");
        assertThatThrownBy(() -> NodeWorkItemState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSchedulerVersionFailsClosedInsteadOfFallingBackToLegacy() {
        assertThat(SchedulerVersion.fromWire("LEGACY")).isEqualTo(SchedulerVersion.LEGACY);
        assertThat(SchedulerVersion.fromWire("DUAL_POOL_V1")).isEqualTo(SchedulerVersion.DUAL_POOL_V1);
        assertThat(SchedulerVersion.fromWire("DUAL_POOL_V2")).isEqualTo(SchedulerVersion.DUAL_POOL_V2);
        assertThatThrownBy(() -> SchedulerVersion.fromWire("DUAL_POOL_V3"))
                .isInstanceOf(UnknownSchedulerVersionException.class)
                .hasMessageContaining("DUAL_POOL_V3");
        assertThatThrownBy(() -> SchedulerVersion.fromWire("legacy"))
                .as("大小写不一致也算不认识的取值，不许猜")
                .isInstanceOf(UnknownSchedulerVersionException.class);
        assertThatThrownBy(() -> SchedulerVersion.fromWire(" "))
                .isInstanceOf(UnknownSchedulerVersionException.class);
    }

    @Test
    void existingRowsDefaultToLegacy() {
        assertThat(SchedulerVersion.DEFAULT_FOR_EXISTING_ROWS).isEqualTo(SchedulerVersion.LEGACY);
        assertThat(SchedulerVersion.LEGACY.isDualPool()).isFalse();
        assertThat(SchedulerVersion.DUAL_POOL_V1.isDualPool()).isTrue();
        assertThat(SchedulerVersion.DUAL_POOL_V2.isDualPool())
                .as("V2 不是「单工具锚点」那个版本，不能拿它当 V1 用")
                .isFalse();
    }

    @Test
    void dualPoolFamilyAndWaitGroupCapabilityAreSeparateQuestions() {
        assertThat(SchedulerVersion.LEGACY.isDualPoolFamily()).isFalse();
        assertThat(SchedulerVersion.DUAL_POOL_V1.isDualPoolFamily()).isTrue();
        assertThat(SchedulerVersion.DUAL_POOL_V2.isDualPoolFamily()).isTrue();

        assertThat(SchedulerVersion.LEGACY.usesWaitGroups()).isFalse();
        assertThat(SchedulerVersion.DUAL_POOL_V1.usesWaitGroups())
                .as("V1 用 Run 级单工具锚点，撑不起一个分段里的多个工具")
                .isFalse();
        assertThat(SchedulerVersion.DUAL_POOL_V2.usesWaitGroups()).isTrue();

        assertThat(SchedulerVersion.DUAL_POOL_V1.usesRunLevelToolJobAnchor()).isTrue();
        assertThat(SchedulerVersion.DUAL_POOL_V2.usesRunLevelToolJobAnchor()).isFalse();
        assertThat(SchedulerVersion.LEGACY.usesRunLevelToolJobAnchor()).isFalse();
    }

    @Test
    void identityRejectsBlankRunOrNode() {
        assertThatThrownBy(() -> new NodeWorkItemIdentity("", 0, "n1", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeWorkItemIdentity("r1", 0, " ", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new NodeWorkItemIdentity("r1", 1, "n1", 2, 3).describe())
                .isEqualTo("r1/g1/n1/a2/s3");
    }
}
