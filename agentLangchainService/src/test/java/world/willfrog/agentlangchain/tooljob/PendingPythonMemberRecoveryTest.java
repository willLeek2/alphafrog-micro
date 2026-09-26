package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PendingPythonMemberRecoveryTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final DataAnalysisOperationIdentity OPERATION =
            new DataAnalysisOperationIdentity("run-1", DurableToolCallIds.forTool(
                    "executePython", "call-1", new NodeWorkItemIdentity("run-1", 1, "node-1", 1, 1)), 1);
    private static final String OPERATION_ID = OPERATION.operationId();
    private final WaitGroupStore groups = mock(WaitGroupStore.class);
    private final NodeWorkItemStore workItems = mock(NodeWorkItemStore.class);
    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private WaitMember member;
    private NodeWorkItem item;

    @BeforeEach
    void setUp() throws Exception {
        member = new WaitMember();
        member.setId(41L);
        member.setGroupId(7L);
        member.setRunId("run-1");
        member.setToolName("executePython");
        member.setToolCallId("call-1");
        member.setState("PENDING");
        member.setExternalOperationId(OPERATION_ID);
        DataAnalysisReservation preparing = new DataAnalysisReservation(
                OPERATION.reservationId(), OPERATION, DataAnalysisResourceClass.STANDARD,
                1, DataAnalysisReservationState.PREPARING, null, Instant.now());
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                1L, 1024L, 1, 1.0d, 0, List.of(), DataAnalysisResourceClass.STANDARD, 1);
        member.setDispatchProofJson(json.writeValueAsString(new WaitMemberDispatchProof(
                1, OPERATION_ID, null, FINGERPRINT, "{}",
                json.writeValueAsString(estimate), json.writeValueAsString(preparing),
                "2026-01-01T00:00:00Z")));
        WaitGroup group = new WaitGroup();
        group.setId(7L);
        group.setRunId("run-1");
        group.setPlanGeneration(1);
        group.setNodeId("node-1");
        group.setNodeAttempt(1);
        group.setSegmentSequence(1);
        group.setModelTurn(1);
        item = new NodeWorkItem();
        item.setId(12L);
        item.setRunId("run-1");
        item.setPlanGeneration(1);
        item.setNodeId("node-1");
        item.setNodeAttempt(1);
        item.setSegmentSequence(1);
        item.setClaimEpoch(2);
        item.setClaimedBy("old-process");
        when(ownership.requireIdentity()).thenReturn(
                new DeploymentIdentity("beta-test", "gen-" + "1".repeat(64)));
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setLaneTag("lane-test");
        when(ownership.findOwnedRun("run-1")).thenReturn(run);
        when(groups.scanPendingPythonMembersWithProof("beta-test", "gen-" + "1".repeat(64), 0, 100))
                .thenReturn(List.of(member));
        when(groups.findGroup(7L)).thenReturn(Optional.of(group));
        when(workItems.findByIdentity(group.identity().segment())).thenReturn(Optional.of(item));
    }

    @Test
    void neverSendsCancelForUnknownOrForeignClaimant() {
        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> false).reconcile();
        verify(sandbox, never()).cancelTask(any());
    }

    @Test
    void neverSendsCancelUntilOriginalActiveNodeBudgetIsReleased() {
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(false);
        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();
        verify(sandbox, never()).cancelTask(any());
    }

    @Test
    void uncertainCancelKeepsMemberPendingForStableRetry() {
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED).build());
        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();
        verify(groups, never()).recoverPendingPythonMember(anyLong(), anyLong(), anyInt(),
                any(), any(), any());
    }

    @Test
    void confirmedTombstoneOpensReceiverUsingMemberDerivedCancelRequestId() {
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId("tombstone-1")
                .setStatus("CANCELED").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(GetTaskByOperationIdResponse.newBuilder()
                .setFound(true).setTaskId("tombstone-1")
                .setRequestFingerprint(FINGERPRINT).setStatus("CANCELED").build());
        when(groups.recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);

        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();

        verify(sandbox).cancelTask(org.mockito.ArgumentMatchers.argThat(request ->
                request.getCancelRequestId().equals("wait-member-41")
                        && request.hasByOperation()
                        && request.getByOperation().getOperationId().equals(OPERATION_ID)
                        && request.getByOperation().getRequestFingerprint().equals(FINGERPRINT)));
        verify(groups).recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT);
    }
}
