package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MybatisWaitGroupActivityTest {
    private final WaitGroupMapper mapper = mock(WaitGroupMapper.class);
    private final NodeWorkItemMapper workItems = mock(NodeWorkItemMapper.class);
    private final RootTreeActivityBudget activity = mock(RootTreeActivityBudget.class);
    private final MybatisWaitGroupStore store = new MybatisWaitGroupStore(mapper, workItems, activity);
    private final NodeWorkItemIdentity segment = new NodeWorkItemIdentity("run-a", 1, "node-a", 0, 0);

    @Test
    void suspensionReservesWaitBeforeBusinessWriteThenReleasesExecutingNode() {
        WaitSuspensionRequest request = request();
        NodeWorkItem executing = new NodeWorkItem();
        executing.setState("EXECUTING");
        when(activity.lockForRun("run-a")).thenReturn("root-a");
        when(workItems.findByIdentity("run-a", 1, "node-a", 0, 0)).thenReturn(executing);
        when(activity.reserveWait("root-a", request)).thenReturn(RootTreeBudgetStore.State.RESERVED);
        WaitSuspensionRow row = new WaitSuspensionRow();
        row.setClosedSegments(1);
        row.setCreatedGroupId(7L);
        row.setWrittenMembers(1);
        row.setWrittenNextSegments(1);
        row.setNextSegmentSequence(1);
        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(row);

        assertThat(store.suspendSegment(request).suspended()).isTrue();
        var order = inOrder(activity, workItems, mapper);
        order.verify(activity).lockForRun("run-a");
        order.verify(workItems).findByIdentity("run-a", 1, "node-a", 0, 0);
        order.verify(activity).reserveWait("root-a", request);
        order.verify(mapper).suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString());
        order.verify(activity).confirmWait(request);
        order.verify(activity).releaseNode(executing, 1);
    }

    @Test
    void fullWaitTreeDoesNotSuspendTheExecutingNode() {
        WaitSuspensionRequest request = request();
        when(activity.lockForRun("run-a")).thenReturn("root-a");
        when(activity.reserveWait("root-a", request)).thenReturn(RootTreeBudgetStore.State.REJECTED);
        assertThatThrownBy(() -> store.suspendSegment(request))
                .isInstanceOf(RootTreeActivityLimitException.class);
        verify(mapper, never()).suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void cancellationReleasesOneWaitingGroup() {
        WaitGroup group = new WaitGroup();
        group.setRunId("run-a");
        group.setPlanGeneration(1);
        group.setNodeId("node-a");
        group.setNodeAttempt(0);
        group.setSegmentSequence(0);
        group.setModelTurn(2);
        group.setState("WAITING");
        when(mapper.findGroupById(7L)).thenReturn(group);
        WaitChainCancelRow row = new WaitChainCancelRow();
        row.setGroupsCanceled(1);
        when(mapper.cancelChain(7L)).thenReturn(row);

        assertThat(store.cancelChain(7L).canceled()).isTrue();
        verify(activity).releaseWait(segment, 2);
    }

    private WaitSuspensionRequest request() {
        return new WaitSuspensionRequest(segment, new NodeWorkItemVersions(1, 0, 1), "worker", 2,
                SchedulerVersion.DUAL_POOL_V2,
                List.of(new WaitMemberDraft(0, "call-a", "executePython", "op-a")),
                "{}", "{}");
    }
}
