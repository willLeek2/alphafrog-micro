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









    private static RunWorkspaceResourceGateway.WorkspaceLookup lookup(
            RunWorkspaceResourceGateway.WorkspaceState state, String workspaceId, String runId) {
        return new RunWorkspaceResourceGateway.WorkspaceLookup(state, workspaceId, runId);
    }
}
