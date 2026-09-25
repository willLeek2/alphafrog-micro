package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.wait.WaitChainCancelResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoppedRunWaitGroupReconcilerTest {
    private final WaitGroupStore groups = mock(WaitGroupStore.class);

    @Test
    void continuesPastFailedGroupAndRevisitsItAfterCursorWraps() {
        StoppedRunWaitGroupReconciler reconciler = new StoppedRunWaitGroupReconciler(groups, 2);
        WaitGroup first = group(10, "run-a");
        WaitGroup second = group(11, "run-b");
        when(groups.scanOpenGroupsWithStoppedRun(0, 2)).thenReturn(List.of(first, second), List.of(first));
        when(groups.scanOpenGroupsWithStoppedRun(11, 2)).thenReturn(List.of());
        when(groups.cancelChain(10))
                .thenThrow(new IllegalStateException("temporary db failure"))
                .thenReturn(new WaitChainCancelResult(1, 1, 1, 0));

        assertEquals(1, reconciler.runBatch());
        assertEquals(0, reconciler.runBatch());
        assertEquals(1, reconciler.runBatch());
        verify(groups, org.mockito.Mockito.times(2)).cancelChain(10);
        verify(groups).cancelChain(11);
    }

    @Test
    void scanFailureKeepsCursorForRetry() {
        StoppedRunWaitGroupReconciler reconciler = new StoppedRunWaitGroupReconciler(groups, 1);
        WaitGroup first = group(10, "run-a");
        when(groups.scanOpenGroupsWithStoppedRun(0, 1)).thenReturn(List.of(first));
        when(groups.scanOpenGroupsWithStoppedRun(10, 1))
                .thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(List.of());

        assertEquals(1, reconciler.runBatch());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, reconciler::runBatch);
        assertEquals(0, reconciler.runBatch());
        verify(groups, org.mockito.Mockito.times(2)).scanOpenGroupsWithStoppedRun(10, 1);
    }

    @Test
    void canceledGroupsWithMissingStopTasksAreRetriedAfterCursorWrap() {
        StoppedRunWaitGroupReconciler reconciler = new StoppedRunWaitGroupReconciler(groups, 2);
        WaitGroup first = group(20, "run-a");
        WaitGroup second = group(21, "run-b");
        when(groups.scanCanceledGroupsMissingStopTasks(0, 2))
                .thenReturn(List.of(first, second), List.of(first));
        when(groups.scanCanceledGroupsMissingStopTasks(21, 2)).thenReturn(List.of());
        when(groups.ensureCanceledMemberStopTasks(20))
                .thenThrow(new IllegalStateException("temporary db failure"))
                .thenReturn(1);

        assertEquals(1, reconciler.runBatch());
        assertEquals(0, reconciler.runBatch());
        assertEquals(1, reconciler.runBatch());
        verify(groups, org.mockito.Mockito.times(2)).ensureCanceledMemberStopTasks(20);
        verify(groups).ensureCanceledMemberStopTasks(21);
    }

    private static WaitGroup group(long id, String runId) {
        WaitGroup group = new WaitGroup();
        group.setId(id);
        group.setRunId(runId);
        return group;
    }
}
