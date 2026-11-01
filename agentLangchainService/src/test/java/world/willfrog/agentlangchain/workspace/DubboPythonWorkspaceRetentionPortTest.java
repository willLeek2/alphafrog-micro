package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ListWorkspaceExpiryCandidatesResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceExpiryCandidate;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DubboPythonWorkspaceRetentionPortTest {
    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final DubboPythonWorkspaceRetentionPort port = new DubboPythonWorkspaceRetentionPort();

    DubboPythonWorkspaceRetentionPortTest() {
        ReflectionTestUtils.setField(port, "sandbox", sandbox);
    }







    private static WorkspaceExpiryCandidate candidate(String id) {
        return WorkspaceExpiryCandidate.newBuilder().setRunId("run-a")
                .setWorkspaceId(id).setWorkspaceGeneration("generation-a")
                .setStatus("active").setLastActiveAt("2026-09-27T00:00:00Z").build();
    }

    private static WorkspaceInfo info(String owner, String status) {
        return WorkspaceInfo.newBuilder().setWorkspaceId("workspace-a")
                .setWorkspaceGeneration("generation-a")
                .setOwnedByRunId(owner).setStatus(status).build();
    }
}
