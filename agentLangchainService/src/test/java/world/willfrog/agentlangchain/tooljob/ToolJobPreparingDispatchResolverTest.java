package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobPreparingDispatchResolverTest {

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
                        .setTaskId("tombstone-1").setRequestFingerprint("sha256:request")
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
                        && "sha256:request".equals(request.getByOperation().getRequestFingerprint())
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
    void conflictingTombstoneIdentityCannotBeAttached() {
        ToolJobAnchor anchor = canceledPreparing();
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId("tombstone-1")
                .setStatus("CANCELED").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(true)
                        .setTaskId("other-task").setRequestFingerprint("sha256:request")
                        .build());

        ToolJobPreparingDispatchResolver.Resolution result =
                ToolJobPreparingDispatchResolver.resolve(
                        "run-1", anchor, preparing, sandbox, anchorService);

        assertThat(result.outcome())
                .isEqualTo(ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE);
        verify(anchorService, never()).updateActive(any(), any(), any(), any());
        verify(sandbox, never()).createTask(any());
    }

    private ToolJobAnchor canceledPreparing() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setRequestFingerprint("sha256:request");
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition("CANCELED");
        anchor.setAutoResume(false);
        return anchor;
    }
}
