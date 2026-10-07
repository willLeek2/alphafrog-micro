package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentRunFamilyDeletionServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
    private final AgentRunFamilyDeletionService service = new AgentRunFamilyDeletionService(runs, intents);

    @Test
    void activeChildKeepsRootAndHistoryIntact() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(intents.hasUnsettledDescendants("root")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.beginDeletion("root", "user"));

        verify(runs, never()).deleteByIdAndUser(anyString(), anyString());
        verify(runs, never()).deleteChildIntentsByRoot(anyString());
        verify(runs, never()).markDeletionStarted(anyString(), anyString());
    }

    @Test
    void cancelingOrWaitingForExternalTaskCannotBeginDeletion() {
        for (AgentRunStatus status : List.of(AgentRunStatus.CANCELING,
                AgentRunStatus.WAITING_TOOL_JOB)) {
            reset(runs, intents);
            when(runs.findByIdAndUserForUpdate("root", "user"))
                    .thenReturn(run("root", "user", status));

            assertThrows(IllegalStateException.class, () -> service.beginDeletion("root", "user"));
            verify(runs, never()).markDeletionStarted(anyString(), anyString());
        }
    }

    @Test
    void fullyStoppedChildrenAreDeletedWithRoot() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(run("child-a", "user", AgentRunStatus.COMPLETED),
                        run("child-b", "user", AgentRunStatus.CANCELED)));
        when(runs.markDeletionStarted(anyString(), eq("user"))).thenReturn(1);
        when(runs.isDeletionStarted(anyString())).thenReturn(true);
        when(runs.deleteMarkedByIdAndUser(anyString(), eq("user"))).thenReturn(1);

        AgentRunFamilyDeletionService.DeletionPlan plan = service.beginDeletion("root", "user");
        assertEquals(List.of("child-a", "child-b", "root"), plan.runIds());
        assertEquals(plan.runIds(), plan.workspaceRunIds());
        verify(runs, never()).deleteMarkedByIdAndUser(anyString(), anyString());
        assertEquals(plan.runIds(), service.finishDeletionAfterResourcesConfirmed(
                "root", "user", plan.runIds()));

        var order = inOrder(runs);
        order.verify(runs).markDeletionStarted("child-a", "user");
        order.verify(runs).markDeletionStarted("child-b", "user");
        order.verify(runs).markDeletionStarted("root", "user");
        // 下一层意图有非延迟的 parent_run_id 外键；必须先移除整树关系。
        order.verify(runs).deleteChildIntentsByRoot("root");
        order.verify(runs).deleteMarkedByIdAndUser("child-a", "user");
        order.verify(runs).deleteMarkedByIdAndUser("child-b", "user");
        order.verify(runs).deleteEmptyTreeCapacityByRoot("root");
        order.verify(runs).deleteMarkedByIdAndUser("root", "user");
    }

    @Test
    void nonterminalChildFailsClosedEvenIfCapacityRowLooksSettled() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(run("child", "user", AgentRunStatus.EXECUTING)));

        assertThrows(IllegalStateException.class, () -> service.beginDeletion("root", "user"));
        verify(runs, never()).deleteByIdAndUser(anyString(), anyString());
    }

    @Test
    void incompleteSandboxConfirmationKeepsMarkedRuns() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(run("child", "user", AgentRunStatus.COMPLETED)));
        when(runs.isDeletionStarted(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                service.finishDeletionAfterResourcesConfirmed("root", "user", List.of("root")));
        verify(runs, never()).deleteChildIntentsByRoot(anyString());
        verify(runs, never()).deleteMarkedByIdAndUser(anyString(), anyString());
    }

    @Test
    void missingDurableMarkPreventsPhysicalDelete() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));

        assertThrows(IllegalStateException.class, () ->
                service.finishDeletionAfterResourcesConfirmed("root", "user", List.of("root")));
        verify(runs, never()).deleteMarkedByIdAndUser(anyString(), anyString());
    }

    @Test
    void frozenDisabledRunsStillReceiveDurableDeletionMarksButNeedNoSandbox() {
        AgentRun child = run("child", "user", AgentRunStatus.COMPLETED);
        child.setExt("{\"python_workspace_enabled\":false}");
        AgentRun root = run("root", "user", AgentRunStatus.COMPLETED);
        root.setExt("{\"python_workspace_enabled\":false}");
        when(runs.findByIdAndUserForUpdate("root", "user")).thenReturn(root);
        when(runs.listChildRunsByRootForUpdate("root")).thenReturn(List.of(child));
        when(runs.markDeletionStarted(anyString(), eq("user"))).thenReturn(1);

        var plan = service.beginDeletion("root", "user");

        assertEquals(List.of("child", "root"), plan.runIds());
        assertEquals(List.of(), plan.workspaceRunIds());
        verify(runs).markDeletionStarted("child", "user");
        verify(runs).markDeletionStarted("root", "user");
    }

    @Test
    void mixedTreeOnlySendsPossibleWorkspaceOwnersToSandbox() {
        AgentRun disabled = run("disabled", "user", AgentRunStatus.COMPLETED);
        disabled.setExt("{\"python_workspace_enabled\":false}");
        AgentRun enabled = run("enabled", "user", AgentRunStatus.COMPLETED);
        enabled.setExt("{\"python_workspace_enabled\":true}");
        AgentRun missing = run("missing", "user", AgentRunStatus.COMPLETED);
        AgentRun malformed = run("malformed", "user", AgentRunStatus.COMPLETED);
        malformed.setExt("{\"python_workspace_enabled\":false,\"python_workspace_enabled\":true}");
        AgentRun root = run("root", "user", AgentRunStatus.COMPLETED);
        root.setExt("{\"python_workspace_enabled\":\"false\"}");
        when(runs.findByIdAndUserForUpdate("root", "user")).thenReturn(root);
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(disabled, enabled, missing, malformed));
        when(runs.markDeletionStarted(anyString(), eq("user"))).thenReturn(1);

        var plan = service.beginDeletion("root", "user");

        assertEquals(List.of("disabled", "enabled", "missing", "malformed", "root"), plan.runIds());
        assertEquals(List.of("enabled", "missing", "malformed", "root"), plan.workspaceRunIds());
    }

    private static AgentRun run(String id, String userId, AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setStatus(status);
        return run;
    }
}
