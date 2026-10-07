package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DubboRunWorkspaceResourceGatewayTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final DeploymentIdentityProvider identity = () ->
            new DeploymentIdentity("beta-test", "gen-" + "a".repeat(64));
    private final DubboRunWorkspaceResourceGateway gateway =
            new DubboRunWorkspaceResourceGateway(runs, identity);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(gateway, "sandbox", sandbox);
        when(runs.findById("run-a")).thenReturn(run("run-a", "beta-test", "saved-lane"));
        LaneContext.setTrafficScopeId("inbound-lane");
    }

    @AfterEach
    void clearLane() {
        LaneContext.restore(null);
    }

    @Test
    void everyOperationUsesPersistedLaneAndRestoresIncomingLane() {
        AtomicReference<String> sealLane = new AtomicReference<>();
        AtomicReference<String> queryLane = new AtomicReference<>();
        AtomicReference<String> deleteLane = new AtomicReference<>();
        when(sandbox.sealWorkspace(any())).thenAnswer(invocation -> {
            sealLane.set(LaneContext.trafficScopeId());
            return SealWorkspaceResponse.newBuilder().setSealed(true).build();
        });
        when(sandbox.queryWorkspace(any())).thenAnswer(invocation -> {
            queryLane.set(LaneContext.trafficScopeId());
            return QueryWorkspaceResponse.newBuilder()
                    .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
                    .setWorkspace(workspace("run-a", "sealed"))
                    .build();
        });
        when(sandbox.deleteWorkspace(any())).thenAnswer(invocation -> {
            deleteLane.set(LaneContext.trafficScopeId());
            return DeleteWorkspaceResponse.newBuilder().setDeleted(true)
                    .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED).build();
        });

        gateway.sealRun("run-a");
        assertEquals("inbound-lane", LaneContext.trafficScopeId());
        assertEquals(RunWorkspaceResourceGateway.WorkspaceState.ACTIVE,
                gateway.findWorkspaceByRun("run-a").state());
        assertEquals("inbound-lane", LaneContext.trafficScopeId());
        gateway.deleteWorkspace("run-a", "workspace-a", "delete-key");
        assertEquals("inbound-lane", LaneContext.trafficScopeId());
        assertEquals("saved-lane", sealLane.get());
        assertEquals("saved-lane", queryLane.get());
        assertEquals("saved-lane", deleteLane.get());
        verify(sandbox).sealWorkspace(argThat(request -> request.getRunId().equals("run-a")
                && request.getIdempotencyKey().equals("run-workspace-seal:run-a")));
        verify(sandbox).deleteWorkspace(argThat(request -> request.getWorkspaceId().equals("workspace-a")
                && request.getIdempotencyKey().equals("delete-key")
                && !request.hasExpectedLastActiveAt()));
    }

    @Test
    void durableSealWithoutWorkspaceIsNeverCreatedButMissingRecordIsUnknown() {
        when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder().setSealed(true).build());
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND).build());

        gateway.sealRun("run-a");
        var found = gateway.findWorkspaceByRun("run-a");
        assertEquals(RunWorkspaceResourceGateway.WorkspaceState.NEVER_CREATED, found.state());
        assertNull(found.workspaceId());
        assertEquals("run-a", found.ownedByRunId());

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));
    }

    @Test
    void sealRejectsAnActiveOrDirtyWorkspaceDespiteSealedFlag() {
        when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder()
                .setSealed(true).setWorkspace(workspace("run-a", "active")).build());
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));
        when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder()
                .setSealed(true).setWorkspace(workspace("run-a", "dirty")).build());
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));

        for (String status : new String[]{"sealed", "deleting", "deleted"}) {
            when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder()
                    .setSealed(true).setWorkspace(workspace("run-a", status)).build());
            assertDoesNotThrow(() -> gateway.sealRun("run-a"));
        }
    }

    @Test
    void deletedAuditRequiresOldWorkspaceIdentityAndDeletedStatus() {
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setWorkspace(workspace("run-a", "sealed")).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setWorkspace(workspace("other-run", "deleted")).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED)
                .setWorkspace(workspace("run-a", "deleted")).build());
        var found = gateway.findWorkspaceByRun("run-a");
        assertEquals(RunWorkspaceResourceGateway.WorkspaceState.DELETED, found.state());
        assertEquals("workspace-a", found.workspaceId());
        assertEquals("run-a", found.ownedByRunId());
    }

    @Test
    void foreignRunIsRejectedBeforeSandboxCall() {
        when(runs.findById("run-a")).thenReturn(run("run-a", "other-deployment", "other-lane"));
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));
        assertThrows(IllegalStateException.class,
                () -> gateway.deleteWorkspace("run-a", "workspace-a", "delete-key"));
        verifyNoInteractions(sandbox);
    }

    @Test
    void ambiguousOrContradictoryResultsFailClosed() {
        when(sandbox.sealWorkspace(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));
        when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder().build());
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));
        when(sandbox.sealWorkspace(any())).thenReturn(SealWorkspaceResponse.newBuilder()
                .setSealed(true).setErrorDetail(SandboxErrorDetail.getDefaultInstance()).build());
        assertThrows(IllegalStateException.class, () -> gateway.sealRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
                .setWorkspace(workspace("other-run", "active")).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_OUTCOME_UNSPECIFIED).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
                .setWorkspace(workspace("run-a", "active")).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));
        when(sandbox.queryWorkspace(any())).thenReturn(QueryWorkspaceResponse.newBuilder()
                .setOutcome(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
                .setErrorDetail(SandboxErrorDetail.getDefaultInstance()).build());
        assertThrows(IllegalStateException.class, () -> gateway.findWorkspaceByRun("run-a"));

        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setDeleted(true).setRetryableFailure(true).build());
        assertThrows(IllegalStateException.class,
                () -> gateway.deleteWorkspace("run-a", "workspace-a", "delete-key"));
        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setDeleted(true)
                .setOutcome(WorkspaceDeleteOutcome.WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY).build());
        assertThrows(IllegalStateException.class,
                () -> gateway.deleteWorkspace("run-a", "workspace-a", "delete-key"));
        when(sandbox.deleteWorkspace(any())).thenReturn(DeleteWorkspaceResponse.newBuilder()
                .setDeleted(true).setErrorDetail(SandboxErrorDetail.getDefaultInstance()).build());
        assertThrows(IllegalStateException.class,
                () -> gateway.deleteWorkspace("run-a", "workspace-a", "delete-key"));
    }

    private static AgentRun run(String id, String deploymentId, String lane) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setDeploymentId(deploymentId);
        run.setLaneTag(lane);
        return run;
    }

    private static WorkspaceInfo workspace(String owner, String status) {
        return WorkspaceInfo.newBuilder().setWorkspaceId("workspace-a")
                .setWorkspaceGeneration("generation-a")
                .setOwnedByRunId(owner).setStatus(status).build();
    }
}
