package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SandboxRunWorkspaceDeletionCoordinatorTest {
    private final RunWorkspaceResourceGateway gateway = mock(RunWorkspaceResourceGateway.class);
    private final SandboxRunWorkspaceDeletionCoordinator coordinator = new SandboxRunWorkspaceDeletionCoordinator();

    private void connectGateway() {
        ReflectionTestUtils.setField(coordinator, "gateway", gateway);
    }

    @Test
    void absentWorkspaceStillRequiresDurableSealBeforeRunCanBeDeleted() {
        connectGateway();
        when(gateway.findWorkspaceByRun("run-a"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.NEVER_CREATED, null, "run-a"));

        coordinator.sealFindAndDelete(List.of("run-a"));

        var order = inOrder(gateway);
        order.verify(gateway).sealRun("run-a");
        order.verify(gateway).findWorkspaceByRun("run-a");
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());
    }

    @Test
    void entireFamilyIsSealedBeforeAnyWorkspaceIsFoundOrDeleted() {
        connectGateway();
        when(gateway.findWorkspaceByRun("child"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.ACTIVE, "workspace-child", "child"));
        when(gateway.findWorkspaceByRun("root"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.DIRTY, "workspace-root", "root"));

        coordinator.sealFindAndDelete(List.of("child", "root"));

        var order = inOrder(gateway);
        order.verify(gateway).sealRun("child");
        order.verify(gateway).sealRun("root");
        order.verify(gateway).findWorkspaceByRun("child");
        order.verify(gateway).deleteWorkspace("child", "workspace-child", "run-workspace-delete:child");
        order.verify(gateway).findWorkspaceByRun("root");
        order.verify(gateway).deleteWorkspace("root", "workspace-root", "run-workspace-delete:root");
    }

    @Test
    void failureAfterFirstDeleteLeavesAllRunsSealedAndRetryUsesSameKey() {
        connectGateway();
        when(gateway.findWorkspaceByRun("child"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.ACTIVE, "workspace-child", "child"),
                        lookup(RunWorkspaceResourceGateway.WorkspaceState.DELETED, "workspace-child", "child"));
        when(gateway.findWorkspaceByRun("root"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.ACTIVE, "workspace-root", "root"));
        doThrow(new IllegalStateException("disk busy")).doNothing()
                .when(gateway).deleteWorkspace("root", "workspace-root", "run-workspace-delete:root");

        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("child", "root")));
        var firstAttempt = inOrder(gateway);
        firstAttempt.verify(gateway).sealRun("child");
        firstAttempt.verify(gateway).sealRun("root");
        firstAttempt.verify(gateway).findWorkspaceByRun("child");
        firstAttempt.verify(gateway).deleteWorkspace("child", "workspace-child", "run-workspace-delete:child");
        firstAttempt.verify(gateway).findWorkspaceByRun("root");
        firstAttempt.verify(gateway).deleteWorkspace("root", "workspace-root", "run-workspace-delete:root");

        coordinator.sealFindAndDelete(List.of("child", "root"));
        verify(gateway, times(2)).sealRun("child");
        verify(gateway, times(2)).sealRun("root");
        verify(gateway).deleteWorkspace("child", "workspace-child", "run-workspace-delete:child");
        verify(gateway, times(2)).deleteWorkspace("root", "workspace-root", "run-workspace-delete:root");
    }

    @Test
    void unknownLookupOrWrongOwnerCannotConfirmDeletion() {
        connectGateway();
        when(gateway.findWorkspaceByRun("run-a"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.UNKNOWN, null, "run-a"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a")));
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());

        when(gateway.findWorkspaceByRun("run-a"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.ACTIVE, "workspace-b", "run-b"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a")));
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());
    }

    @Test
    void deletedAuditLetsRetryFinishWithoutDeletingDiskAgain() {
        connectGateway();
        when(gateway.findWorkspaceByRun("child"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.DELETED, "workspace-child", "child"));
        when(gateway.findWorkspaceByRun("root"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.NEVER_CREATED, null, "root"));

        coordinator.sealFindAndDelete(List.of("child", "root"));

        verify(gateway).sealRun("child");
        verify(gateway).sealRun("root");
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());
    }

    @Test
    void malformedEmptyOrDeletedEvidenceFailsClosed() {
        connectGateway();
        when(gateway.findWorkspaceByRun("run-a"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.NEVER_CREATED,
                        "unexpected-workspace", "run-a"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a")));

        when(gateway.findWorkspaceByRun("run-a"))
                .thenReturn(lookup(RunWorkspaceResourceGateway.WorkspaceState.DELETED, null, "run-a"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a")));
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());
    }

    @Test
    void failedSealNeverQueriesOrDeletesWorkspace() {
        connectGateway();
        doThrow(new IllegalStateException("sandbox unavailable")).when(gateway).sealRun("run-b");

        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a", "run-b", "run-c")));
        verify(gateway).sealRun("run-a");
        verify(gateway).sealRun("run-b");
        verify(gateway, never()).sealRun("run-c");
        verify(gateway, never()).findWorkspaceByRun(anyString());
        verify(gateway, never()).deleteWorkspace(anyString(), anyString(), anyString());
    }

    @Test
    void missingGatewayFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> coordinator.sealFindAndDelete(List.of("run-a")));
    }

    private static RunWorkspaceResourceGateway.WorkspaceLookup lookup(
            RunWorkspaceResourceGateway.WorkspaceState state, String workspaceId, String runId) {
        return new RunWorkspaceResourceGateway.WorkspaceLookup(state, workspaceId, runId);
    }
}
