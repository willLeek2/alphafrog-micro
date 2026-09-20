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
    void waitingAndResumableAreReservedNotTerminal() {
        assertThat(NodeWorkItemState.WAITING.isReserved()).isTrue();
        assertThat(NodeWorkItemState.RESUMABLE.isReserved()).isTrue();
        assertThat(NodeWorkItemState.WAITING.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.RESUMABLE.isTerminal()).isFalse();
        assertThat(NodeWorkItemState.terminalWireValues())
                .doesNotContain("WAITING", "RESUMABLE");
    }

    @Test
    void allWireValuesCoverSevenActivePlusTwoReserved() {
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
        assertThatThrownBy(() -> SchedulerVersion.fromWire("DUAL_POOL_V2"))
                .isInstanceOf(UnknownSchedulerVersionException.class)
                .hasMessageContaining("DUAL_POOL_V2");
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
