package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolJobPreparingAbortRecoveryServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String OPERATION_ID = "run-1:call-1:1";
    private static final Instant LEASE =
            Instant.parse("2026-07-30T08:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    private final DataAnalysisCapacityService capacityService =
            mock(DataAnalysisCapacityService.class);
    private final ToolJobAnchorService anchorService =
            mock(ToolJobAnchorService.class);
    private final ToolJobPreparingAbortRecoveryService service =
            new ToolJobPreparingAbortRecoveryService();

    @Test
    void releasedOutcomeReconstructsPreparingProofAndClearsDurableAbort()
            throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                RUN_ID,
                AgentRunStatus.EXECUTING,
                OPERATION_ID,
                "worker-a",
                LEASE)).thenReturn(true);

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED);

        var requestCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        DataAnalysisReleaseRequest.class);
        verify(capacityService).releaseReservation(requestCaptor.capture());
        DataAnalysisReleaseRequest request = requestCaptor.getValue();
        assertThat(request.reservation().state())
                .isEqualTo(DataAnalysisReservationState.PREPARING);
        assertThat(request.reservation().taskId()).isNull();
        assertThat(request.reason())
                .isEqualTo(DataAnalysisReleaseReason.PREPARING_ABORTED);
        assertThat(request.proof())
                .isInstanceOf(DataAnalysisReleaseProof.PreDispatchAbort.class);
        assertThat(((DataAnalysisReleaseProof.PreDispatchAbort) request.proof())
                .identity()).isEqualTo(request.reservation().identity());
    }

    @Test
    void alreadyReleasedAndNotFoundAreAcceptedOnlyWithDurableIntent()
            throws Exception {
        for (DataAnalysisReleaseOutcome outcome : new DataAnalysisReleaseOutcome[] {
                DataAnalysisReleaseOutcome.ALREADY_RELEASED,
                DataAnalysisReleaseOutcome.NOT_FOUND}) {
            reset(capacityService, anchorService);
            ToolJobAnchor anchor = abortingAnchor();
            when(capacityService.releaseReservation(any())).thenReturn(outcome);
            when(anchorService.completeLiveDagBlockingPreparingAbort(
                    RUN_ID,
                    AgentRunStatus.EXECUTING,
                    OPERATION_ID,
                    "worker-a",
                    LEASE)).thenReturn(true);

            assertThat(service.recover(
                    RUN_ID, anchor, capacityService, anchorService))
                    .isEqualTo(
                            ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED);
        }
    }

    @Test
    void conflictRetainsAbortAnchorWithoutClear() throws Exception {
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.CONFLICT);

        assertThat(service.recover(
                RUN_ID, abortingAnchor(), capacityService, anchorService))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.CONFLICT);

        verify(anchorService, never()).completeLiveDagBlockingPreparingAbort(
                any(), any(), any(), any(), any());
    }

    @Test
    void acceptedReleaseWithLostClearSchedulesRetryWhileAnchorStillMatches()
            throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.NOT_FOUND);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                RUN_ID,
                AgentRunStatus.EXECUTING,
                OPERATION_ID,
                "worker-a",
                LEASE)).thenReturn(false);
        when(anchorService.loadAnchor(RUN_ID)).thenReturn(anchor);

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.CLEAR_PENDING);
    }

    @Test
    void clearLoserDoesNotTreatNewOperationAsCompleted() throws Exception {
        ToolJobAnchor stale = abortingAnchor();
        ToolJobAnchor winner = new ToolJobAnchor();
        winner.setOperationId("run-1:call-2:1");
        winner.setAnchorState("ATTACHED");
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                RUN_ID,
                AgentRunStatus.EXECUTING,
                OPERATION_ID,
                "worker-a",
                LEASE)).thenReturn(false);
        when(anchorService.loadAnchor(RUN_ID)).thenReturn(winner);

        assertThat(service.recover(
                RUN_ID, stale, capacityService, anchorService))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.OWNERSHIP_LOST);
    }

    @Test
    void malformedReleasedIntentNeverTouchesCapacity() throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        anchor.setAnchorState("PREPARING");

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.INVALID_EVIDENCE);

        verifyNoInteractions(capacityService, anchorService);
    }

    private static ToolJobAnchor abortingAnchor() throws Exception {
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "call-1", 1);
        DataAnalysisReservation released = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.parse("2026-07-30T07:00:00Z"));
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(OPERATION_ID);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-a");
        anchor.setBlockingLeaseUntil(LEASE);
        anchor.setReservationJson(
                OBJECT_MAPPER.writeValueAsString(released));
        return anchor;
    }
}
