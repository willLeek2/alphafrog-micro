package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
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
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
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

    @Test
    void replayableMemberIsResentOnlyAfterAnAuthoritativeMiss() throws Exception {
        ExecuteRequest original = installReplayableProof();
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.getTaskByOperationId(any()))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setTaskId("replayed-task").setRequestFingerprint(FINGERPRINT).build());
        when(groups.recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);

        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();

        verify(sandbox).createTask(original);
        verify(sandbox, never()).cancelTask(any());
        verify(groups).recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT);
    }

    @Test
    void acceptedBeforeCrashIsFoundWithoutSendingASecondCreate() throws Exception {
        installReplayableProof();
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.getTaskByOperationId(any())).thenReturn(GetTaskByOperationIdResponse.newBuilder()
                .setFound(true).setTaskId("already-accepted")
                .setRequestFingerprint(FINGERPRINT).build());

        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();

        verify(sandbox, never()).createTask(any());
        verify(sandbox, never()).cancelTask(any());
        verify(groups).recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT);
    }

    @Test
    void ambiguousReplayResponseKeepsTheSavedRequestPending() throws Exception {
        installReplayableProof();
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.getTaskByOperationId(any()))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance());
        when(sandbox.createTask(any())).thenThrow(new IllegalStateException("response lost"));

        new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership,
                ignored -> true).reconcile();

        verify(groups, never()).recoverPendingPythonMember(anyLong(), anyLong(), anyInt(),
                any(), any(), any());
        verify(sandbox, never()).cancelTask(any());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,1,false", "0,0,false", "0,1,true"})
    void pendingSqlReplayUsesOriginalUserGuardBeforeCreating(int otherQueries, int renewed, boolean created)
            throws Exception {
        installSqlProof();
        ExecuteRequest original = installReplayableProof();
        String originalProof = member.getDispatchProofJson();
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        AgentRun run = ownership.findOwnedRun("run-1");
        run.setUserId("user-1");
        run.setRunControlVersion(5L);
        var anchor = new world.willfrog.agent.platform.dataanalysis.ToolJobAnchor();
        anchor.setToolName("executeQuery");
        anchor.setOperationId(OPERATION_ID);
        anchor.setRequestFingerprint(FINGERPRINT);
        anchor.setAnchorState("PREPARING");
        run.setToolJobAnchorJson(anchor.toJson());
        var mapper = mock(world.willfrog.agent.platform.mapper.AgentRunMapper.class);
        when(mapper.findById("run-1")).thenReturn(run);
        when(mapper.countInFlightExecuteQueryByUser("user-1", "run-1", "executeQuery", 600))
                .thenReturn(otherQueries);
        var proof = WaitMemberDispatchProof.fromJson(json, originalProof).orElseThrow();
        when(mapper.renewExecuteQueryReplayClaim("run-1", 7L, "member-1", OPERATION_ID,
                FINGERPRINT, proof.createRequestJson(), 1, 5L)).thenReturn(renewed);
        var recovery = new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership, ignored -> true);
        org.springframework.test.util.ReflectionTestUtils.setField(recovery, "queryAdmission", new ToolJobAnchorService(mapper));
        when(sandbox.getTaskByOperationId(any())).thenAnswer(call -> {
            if (renewed == 0) run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.CANCELED);
            return GetTaskByOperationIdResponse.getDefaultInstance();
        });
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setTaskId("replayed-query").setRequestFingerprint(FINGERPRINT).build());

        recovery.reconcile();

        verify(mapper).lockExecuteQuerySession("user-1");
        verify(sandbox, created ? times(1) : never()).createTask(original);
        verify(sandbox, never()).cancelTask(any());
        verify(groups, created ? times(1) : never()).recoverPendingPythonMember(
                41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT);
        org.assertj.core.api.Assertions.assertThat(member.getDispatchProofJson()).isEqualTo(originalProof);
    }

    @Test
    void acceptedSqlTaskIsRecoveredWithoutRenewalOrSecondCreate() throws Exception {
        installSqlProof();
        installReplayableProof();
        when(groups.safeToRecoverPendingPython(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT))
                .thenReturn(true);
        when(sandbox.getTaskByOperationId(any())).thenReturn(GetTaskByOperationIdResponse.newBuilder()
                .setFound(true).setTaskId("accepted-query").setRequestFingerprint(FINGERPRINT).build());
        var admission = mock(ToolJobAnchorService.class);
        var recovery = new PendingPythonMemberRecovery(groups, workItems, sandbox, json, ownership, ignored -> true);
        org.springframework.test.util.ReflectionTestUtils.setField(recovery, "queryAdmission", admission);

        recovery.reconcile();

        verifyNoInteractions(admission);
        verify(sandbox, never()).createTask(any());
        verify(groups).recoverPendingPythonMember(41L, 12L, 2, "old-process", OPERATION_ID, FINGERPRINT);
    }

    private void installSqlProof() throws Exception {
        member.setToolName("executeQuery");
        member.setMemberIdentity("member-1");
        var proof = WaitMemberDispatchProof.fromJson(json, member.getDispatchProofJson()).orElseThrow();
        DataAnalysisOperationIdentity sqlIdentity = new DataAnalysisOperationIdentity("run-1", DurableToolCallIds.forTool(
                "executeQuery", "call-1", new NodeWorkItemIdentity("run-1", 1, "node-1", 1, 1)), 1);
        var reservation = new DataAnalysisReservation(sqlIdentity.reservationId(), sqlIdentity,
                DataAnalysisResourceClass.STANDARD, 1, DataAnalysisReservationState.PREPARING, null, Instant.now());
        member.setExternalOperationId(sqlIdentity.operationId());
        member.setDispatchProofJson(new WaitMemberDispatchProof(1, sqlIdentity.operationId(), null, FINGERPRINT,
                proof.canonicalCreateSpecJson(), proof.estimateJson(), json.writeValueAsString(reservation),
                proof.submittedAt()).toJson(json));
    }

    private ExecuteRequest installReplayableProof() throws Exception {
        WaitMemberDispatchProof old = WaitMemberDispatchProof.fromJson(
                json, member.getDispatchProofJson()).orElseThrow();
        ExecuteRequest original = ExecuteRequest.newBuilder()
                .setOperationId(OPERATION_ID).setRequestFingerprint(FINGERPRINT)
                .setCode("print('saved before dispatch')").build();
        member.setDispatchProofJson(new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, old.operationId(), null,
                old.requestFingerprint(), old.canonicalCreateSpecJson(), old.estimateJson(),
                old.reservationJson(), old.submittedAt(),
                JsonFormat.printer().omittingInsignificantWhitespace().print(original))
                .toJson(json));
        return original;
    }
}
