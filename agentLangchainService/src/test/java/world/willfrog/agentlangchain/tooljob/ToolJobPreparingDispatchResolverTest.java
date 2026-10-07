package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobPreparingDispatchResolverTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final DataAnalysisOperationIdentity identity =
            new DataAnalysisOperationIdentity("run-1", "call-1", 1);
    private final DataAnalysisReservation preparing = new DataAnalysisReservation(
            identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
            DataAnalysisReservationState.PREPARING, null, Instant.now());

    @Test
    void canceledPreparingCreatesDurableTombstoneAndAttachesItsVerifiedTask() {
        ToolJobAnchor anchor = canceledPreparing();
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId("tombstone-1")
                .setStatus("CANCELED").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(true)
                        .setTaskId("tombstone-1").setRequestFingerprint(FINGERPRINT)
                        .build());
        when(anchorService.updateActive(eq("run-1"), eq(anchor),
                eq(AgentRunStatus.EXECUTING), eq(identity.operationId()))).thenReturn(true);

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.RESOLVED);
        assertThat(result.reservation().state())
                .isEqualTo(DataAnalysisReservationState.TASK_ATTACHED);
        assertThat(result.reservation().taskId()).isEqualTo("tombstone-1");
        assertThat(anchor.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(anchor.getAnchorState()).isEqualTo("ATTACHED");
        verify(sandbox).cancelTask(org.mockito.ArgumentMatchers.argThat(request ->
                request.hasByOperation()
                        && identity.operationId().equals(request.getByOperation().getOperationId())
                        && FINGERPRINT.equals(request.getByOperation().getRequestFingerprint())
                        && ("tool-job-create-" + UUID.nameUUIDFromBytes(
                        identity.operationId().getBytes(StandardCharsets.UTF_8)))
                        .equals(request.getCancelRequestId())));
        verify(sandbox, never()).createTask(any());
    }

    @Test
    void unavailableTombstoneKeepsPreparingAndNeverReplaysCreate() {
        ToolJobAnchor anchor = canceledPreparing();
        when(sandbox.cancelTask(any())).thenThrow(new IllegalStateException("sandbox unavailable"));

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE);
        assertThat(anchor.getAnchorState()).isEqualTo("PREPARING");
        verify(sandbox, never()).createTask(any());
        verify(anchorService, never()).updateActive(any(), any(), any(), any());
    }

    @Test
    void typedLookupErrorDoesNotAuthorizeReplayOfAPreparingRequest() {
        ToolJobAnchor anchor = canceledPreparing();
        anchor.setRunDisposition(null);
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false)
                        .setErrorDetail(SandboxErrorDetail.newBuilder().build()).build());

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE);
        verify(sandbox, never()).createTask(any());
    }

    @Test
    void conflictingTombstoneIdentityCannotBeAttached() {
        ToolJobAnchor anchor = canceledPreparing();
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId("tombstone-1")
                .setStatus("CANCELED").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(true)
                        .setTaskId("other-task").setRequestFingerprint(FINGERPRINT)
                        .build());

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.INVALID_EVIDENCE);
        verify(anchorService, never()).updateActive(any(), any(), any(), any());
        verify(sandbox, never()).createTask(any());
    }

    @Test
    void lostDagWorkerCancelsByOperationAndAttachesForCleanupOnly() {
        ToolJobAnchor anchor = canceledPreparing();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setBlockingOwnerId("old-owner");
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCEL_INTENT_RECORDED)
                .setTaskId("late-dag-task").setStatus("RUNNING").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(true)
                        .setTaskId("late-dag-task").setRequestFingerprint(FINGERPRINT)
                        .build());
        when(anchorService.updateDagCleanupPreparing(eq("run-1"), eq(anchor),
                eq(identity.operationId()), eq("old-owner"), eq(FINGERPRINT)))
                .thenReturn(true);

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.RESOLVED);
        assertThat(result.reservation().taskId()).isEqualTo("late-dag-task");
        verify(sandbox).cancelTask(org.mockito.ArgumentMatchers.argThat(request ->
                request.hasByOperation()
                        && "DAG_WORKER_LOST".equals(request.getReason())));
        verify(sandbox, never()).createTask(any());
        verify(anchorService, never()).updateActive(any(), any(), any(), any());
    }

    @Test
    void replayedWorkspaceRefusalIsDurablyMarkedOnlyAfterOriginalOperationIsAbsent()
            throws Exception {
        ToolJobAnchor anchor = replayablePreparing();
        GetTaskByOperationIdResponse absent = GetTaskByOperationIdResponse.newBuilder()
                .setFound(false).build();
        when(sandbox.getTaskByOperationId(any())).thenReturn(absent, absent);
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());
        when(anchorService.recordWorkspaceRefusal(eq("run-1"), eq(anchor),
                nullable(Instant.class)))
                .thenReturn(true);

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.WORKSPACE_REFUSED);
        assertThat(anchor.getAnchorState()).isEqualTo("WORKSPACE_REFUSED");
        assertThat(anchor.getWorkspaceRefusalCode()).isEqualTo("WORKSPACE_DIRTY");
        verify(sandbox, never()).cancelTask(any());
    }

    @Test
    void replayedWorkspaceRefusalCannotOverrideAnAlreadyAcceptedTask() throws Exception {
        ToolJobAnchor anchor = replayablePreparing();
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false).build(),
                GetTaskByOperationIdResponse.newBuilder().setFound(true)
                        .setTaskId("accepted-task")
                        .setRequestFingerprint(FINGERPRINT).build());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());
        when(anchorService.updateActive(eq("run-1"), eq(anchor),
                eq(AgentRunStatus.EXECUTING), eq(identity.operationId()))).thenReturn(true);

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.RESOLVED);
        assertThat(result.reservation().taskId()).isEqualTo("accepted-task");
        verify(anchorService, never()).recordWorkspaceRefusal(any(), any(), any());
    }

    @Test
    void replayedWorkspaceRefusalWithLookupErrorRemainsPreparing() throws Exception {
        ToolJobAnchor anchor = replayablePreparing();
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false).build(),
                GetTaskByOperationIdResponse.newBuilder().setFound(false)
                        .setErrorDetail(SandboxErrorDetail.newBuilder().build()).build());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE);
        assertThat(anchor.getAnchorState()).isEqualTo("PREPARING");
        verify(anchorService, never()).recordWorkspaceRefusal(any(), any(), any());
    }

    private ToolJobAnchor replayablePreparing() throws Exception {
        ToolJobAnchor anchor = canceledPreparing();
        anchor.setRunDisposition(null);
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setOperationId(identity.operationId())
                .setRequestFingerprint(FINGERPRINT)
                .setRunId("run-1")
                .setWorkspaceId("workspace-1")
                .setWorkspaceGeneration("generation-1")
                .setCode("print(1)")
                .build();
        anchor.setCreateRequestJson(JsonFormat.printer().print(request));
        return anchor;
    }

    private ToolJobAnchor canceledPreparing() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setRequestFingerprint(FINGERPRINT);
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition("CANCELED");
        anchor.setAutoResume(false);
        return anchor;
    }
}
