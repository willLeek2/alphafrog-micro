package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MybatisNodeWorkItemActivityTest {
    private final NodeWorkItemMapper mapper = mock(NodeWorkItemMapper.class);
    private final RootTreeActivityBudget activity = mock(RootTreeActivityBudget.class);
    private final MybatisNodeWorkItemStore store = new MybatisNodeWorkItemStore(mapper, activity);
    private final NodeWorkItemIdentity identity = new NodeWorkItemIdentity("run-a", 1, "node-a", 0, 0);
    private final ServiceOwnershipFence fence = new ServiceOwnershipFence("owner", 1);

    @Test
    void claimReservesBeforeConditionalUpdateAndConfirmsOnlyOnSuccess() {
        NodeWorkItem before = item("RUNNABLE", 0);
        when(activity.lockForRun("run-a")).thenReturn("root-a");
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(before);
        when(activity.reserveNode("root-a", before, 1)).thenReturn(RootTreeBudgetStore.State.RESERVED);
        when(mapper.claim(anyString(), anyLong(), anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(OffsetDateTime.class))).thenReturn(1);

        NodeWorkItemClaim claim = store.claim(identity, new NodeWorkItemVersions(2, 0, 0), "worker",
                Duration.ofMinutes(1), SchedulerVersion.DUAL_POOL_V2, fence).orElseThrow();
        assertThat(claim.claimEpoch()).isEqualTo(1);
        var order = inOrder(activity, mapper);
        order.verify(activity).lockForRun("run-a");
        order.verify(mapper).findByIdentity("run-a", 1, "node-a", 0, 0);
        order.verify(activity).reserveNode("root-a", before, 1);
        order.verify(mapper).claim(anyString(), anyLong(), anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(OffsetDateTime.class));
        order.verify(activity).confirmNode(before, 1);
    }

    @Test
    void fullTreeDoesNotClaimNode() {
        NodeWorkItem before = item("RUNNABLE", 0);
        when(activity.lockForRun("run-a")).thenReturn("root-a");
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(before);
        when(activity.reserveNode("root-a", before, 1)).thenReturn(RootTreeBudgetStore.State.REJECTED);
        assertThatThrownBy(() -> store.claim(identity, new NodeWorkItemVersions(2, 0, 0), "worker",
                Duration.ofMinutes(1), SchedulerVersion.DUAL_POOL_V2, fence))
                .isInstanceOf(RootTreeActivityLimitException.class);
        verify(mapper, never()).claim(anyString(), anyLong(), anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void terminalCommitKeepsCapacityUntilWorkerExit() {
        NodeWorkItem before = item("EXECUTING", 3);
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(before);
        when(mapper.commitSegmentResult(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString())).thenReturn(1);
        assertThat(store.commitSegmentResult(identity, new NodeWorkItemVersions(2, 0, 3), "{}", null)
                .applied()).isTrue();
        verify(activity, never()).releaseNode(any(), anyInt());
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(item("RESULT_COMMITTED", 3));
        store.acknowledgeWorkerExit(identity, 3);
        verify(activity).releaseNode(any(), eq(3));
    }

    @Test
    void cancellationKeepsCapacityUntilTheSameWorkerEpochActuallyExits() {
        NodeWorkItem executing = item("EXECUTING", 3);
        NodeWorkItem canceled = item("CANCELED", 3);
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(executing, canceled);
        when(mapper.cancel(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyLong(), anyInt(), anyString()))
                .thenReturn(1);

        assertThat(store.cancel(identity, 0, 3, "user_request").applied()).isTrue();
        verify(activity, never()).releaseNode(any(), anyInt());
        store.acknowledgeWorkerExit(identity, 3);
        verify(activity).releaseNode(canceled, 3);
    }

    @Test
    void recoveryRefusesToReleaseWhileOldProcessCanStillRun() {
        NodeWorkItem before = item("EXECUTING", 3);
        before.setClaimedBy("old-worker");
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(before);
        MybatisNodeWorkItemStore observingStore = new MybatisNodeWorkItemStore(mapper, activity,
                claimant -> false);
        assertThatThrownBy(() -> observingStore.requeueAbandonedClaim(identity,
                new NodeWorkItemVersions(2, 0, 3), fence, SchedulerVersion.DUAL_POOL_V2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未确认退出");
        verify(mapper, never()).requeueAbandonedClaim(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString(), anyLong(), anyString());
        verify(activity, never()).releaseNode(any(), anyInt());
    }

    @Test
    void persistedTerminalEpochIsReleasedWhenOldLocalProcessIsAbsent() {
        NodeWorkItem terminal = item("RESULT_COMMITTED", 3);
        terminal.setClaimedBy("exited-worker");
        when(mapper.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(terminal);
        MybatisNodeWorkItemStore observingStore = new MybatisNodeWorkItemStore(mapper, activity,
                claimant -> "exited-worker".equals(claimant));
        assertThat(observingStore.reconcileExitedWorker(identity, 3)).isTrue();
        verify(activity).releaseNode(terminal, 3);
    }

    private NodeWorkItem item(String state, int epoch) {
        NodeWorkItem item = new NodeWorkItem();
        item.setId(17L);
        item.setRunId(identity.runId());
        item.setPlanGeneration(identity.planGeneration());
        item.setNodeId(identity.nodeId());
        item.setNodeAttempt(identity.nodeAttempt());
        item.setSegmentSequence(identity.segmentSequence());
        item.setState(state);
        item.setClaimEpoch(epoch);
        item.setContextVersion(2L);
        item.setRunControlVersion(0L);
        item.setSchedulerVersion("DUAL_POOL_V2");
        return item;
    }
}
