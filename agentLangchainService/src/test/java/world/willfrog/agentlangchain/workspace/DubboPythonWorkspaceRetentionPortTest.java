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

    @Test
    void candidatesAreOrderedAndCarryCurrentActivityTime() {
        when(sandbox.listWorkspaceExpiryCandidates(any())).thenReturn(
                ListWorkspaceExpiryCandidatesResponse.newBuilder()
                        .addCandidates(candidate("workspace-b"))
                        .setNextPageToken("workspace-b").build());

        var candidates = port.listCandidates("workspace-a", 100);
        assertEquals(1, candidates.size());
        assertEquals("run-a", candidates.get(0).runId());
        assertEquals(OffsetDateTime.parse("2026-09-27T00:00:00Z"), candidates.get(0).lastActiveAt());
        verify(sandbox).listWorkspaceExpiryCandidates(argThat(request ->
                request.getPageSize() == 100 && request.getPageToken().equals("workspace-a")));
    }

    @Test
    void malformedCandidatePageFailsClosed() {
        when(sandbox.listWorkspaceExpiryCandidates(any())).thenReturn(
                ListWorkspaceExpiryCandidatesResponse.newBuilder()
                        .addCandidates(candidate("workspace-a")).build());
        assertThrows(IllegalStateException.class, () -> port.listCandidates("workspace-a", 100));
        when(sandbox.listWorkspaceExpiryCandidates(any())).thenReturn(
                ListWorkspaceExpiryCandidatesResponse.newBuilder()
                        .addCandidates(candidate("workspace-b"))
                        .setNextPageToken("unrelated").build());
        assertThrows(IllegalStateException.class, () -> port.listCandidates("workspace-a", 100));
    }

    @Test
    void queryDistinguishesNoRecordSealAndDeletedAudit() {
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND).build());
        assertEquals(PythonWorkspaceRetentionPort.LookupState.NOT_FOUND,
                port.findByRun("run-a").state());

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
                .setLastActiveAt("2026-09-27T00:00:00Z").build());
        var sealedWithoutDisk = port.findByRun("run-a");
        assertEquals(PythonWorkspaceRetentionPort.LookupState.FOUND, sealedWithoutDisk.state());
        assertNull(sealedWithoutDisk.workspace());

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setWorkspace(info("run-a", "deleted"))
                .setLastActiveAt("2026-09-27T00:00:00Z")
                .setDeletedAt("2026-09-28T00:00:00Z").build());
        assertEquals(PythonWorkspaceRetentionPort.LookupState.DELETED,
                port.findByRun("run-a").state());
    }

    @Test
    void foreignOrIncompleteQueryCannotConfirmDeletion() {
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setLastActiveAt("2026-09-27T00:00:00Z")
                .setDeletedAt("2026-09-28T00:00:00Z").build());
        assertThrows(IllegalStateException.class, () -> port.findByRun("run-a"));
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setWorkspace(info("other-run", "deleted"))
                .setLastActiveAt("2026-09-27T00:00:00Z")
                .setDeletedAt("2026-09-28T00:00:00Z").build());
        assertThrows(IllegalStateException.class, () -> port.findByRun("run-a"));
    }

    @Test
    void conditionalDeleteSendsObservedTimeAndMapsThreeOutcomes() {
        var workspace = new PythonWorkspaceRetentionPort.Workspace("run-a", "workspace-a", "generation-a",
                OffsetDateTime.parse("2026-09-27T00:00:00Z"));
        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED).setDeleted(true).build());
        assertEquals(PythonWorkspaceRetentionPort.DeleteResult.DELETED, port.deleteIfUnchanged(workspace));
        verify(sandbox).deleteWorkspace(argThat(request ->
                request.getWorkspaceId().equals("workspace-a")
                        && request.hasExpectedLastActiveAt()
                        && request.getExpectedLastActiveAt().equals("2026-09-27T00:00:00Z")));

        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY).build());
        assertEquals(PythonWorkspaceRetentionPort.DeleteResult.ACTIVITY_CHANGED,
                port.deleteIfUnchanged(workspace));
        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE)
                .setRetryableFailure(true).build());
        assertEquals(PythonWorkspaceRetentionPort.DeleteResult.RETRYABLE_FAILURE,
                port.deleteIfUnchanged(workspace));
    }

    @Test
    void contradictoryDeleteOutcomeCannotBeTreatedAsSuccess() {
        var workspace = new PythonWorkspaceRetentionPort.Workspace("run-a", "workspace-a", "generation-a",
                OffsetDateTime.parse("2026-09-27T00:00:00Z"));
        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED)
                .setRetryableFailure(true).build());
        assertThrows(IllegalStateException.class, () -> port.deleteIfUnchanged(workspace));
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
