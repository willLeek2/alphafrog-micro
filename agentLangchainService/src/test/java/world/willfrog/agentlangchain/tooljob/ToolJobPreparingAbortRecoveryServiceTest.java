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
import java.util.concurrent.atomic.AtomicReference;

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
    private final ToolJobRedisCache redisCache =
            mock(ToolJobRedisCache.class);
    private final ToolJobPreparingAbortRecoveryService service =
            new ToolJobPreparingAbortRecoveryService();

    @Test
    void releasedOutcomeReconstructsPreparingProofAndClearsDurableAbort()
            throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-a"),
                eq(LEASE))).thenReturn(true);
        stubRedisCleanup();
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                eq(RUN_ID),
                eq(AgentRunStatus.EXECUTING),
                eq(OPERATION_ID),
                contains("/abort-cleanup/"),
                any(Instant.class))).thenReturn(true);

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService, redisCache))
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
        verify(redisCache).claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), eq(anchor), any(ToolJobAnchor.class));
        verify(redisCache).removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class));
    }

    @Test
    void alreadyReleasedAndNotFoundAreAcceptedOnlyWithDurableIntent()
            throws Exception {
        for (DataAnalysisReleaseOutcome outcome : new DataAnalysisReleaseOutcome[] {
                DataAnalysisReleaseOutcome.ALREADY_RELEASED,
                DataAnalysisReleaseOutcome.NOT_FOUND}) {
            reset(capacityService, anchorService, redisCache);
            ToolJobAnchor anchor = abortingAnchor();
            when(capacityService.releaseReservation(any())).thenReturn(outcome);
            when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                    eq(RUN_ID),
                    any(ToolJobAnchor.class),
                    eq(OPERATION_ID),
                    eq("worker-a"),
                    eq(LEASE))).thenReturn(true);
            stubRedisCleanup();
            when(anchorService.completeLiveDagBlockingPreparingAbort(
                    eq(RUN_ID),
                    eq(AgentRunStatus.EXECUTING),
                    eq(OPERATION_ID),
                    contains("/abort-cleanup/"),
                    any(Instant.class))).thenReturn(true);

            assertThat(service.recover(
                    RUN_ID, anchor, capacityService, anchorService, redisCache))
                    .isEqualTo(
                            ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED);
        }
    }

    @Test
    void conflictRetainsAbortAnchorWithoutClear() throws Exception {
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.CONFLICT);

        assertThat(service.recover(
                RUN_ID,
                abortingAnchor(),
                capacityService,
                anchorService,
                redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.CONFLICT);

        verify(anchorService, never())
                .claimLiveDagBlockingPreparingAbortCleanup(
                        any(), any(), any(), any(), any());
        verifyNoInteractions(redisCache);
    }

    @Test
    void acceptedReleaseWithLostClearSchedulesRetryWhileAnchorStillMatches()
            throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        AtomicReference<ToolJobAnchor> claimed = new AtomicReference<>();
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.NOT_FOUND);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-a"),
                eq(LEASE))).thenAnswer(invocation -> {
                    claimed.set(invocation.getArgument(1));
                    return true;
                });
        stubRedisCleanup();
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                eq(RUN_ID),
                eq(AgentRunStatus.EXECUTING),
                eq(OPERATION_ID),
                contains("/abort-cleanup/"),
                any(Instant.class))).thenReturn(false);
        when(anchorService.loadAnchor(RUN_ID))
                .thenAnswer(ignored -> claimed.get());

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.CLEAR_PENDING);
        verify(redisCache).claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), eq(anchor), any(ToolJobAnchor.class));
        verify(redisCache).removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class));
    }

    @Test
    void cleanupClaimLoserNeverDeletesNewOperationIndexes() throws Exception {
        ToolJobAnchor stale = abortingAnchor();
        ToolJobAnchor winner = new ToolJobAnchor();
        winner.setOperationId("run-1:call-2:1");
        winner.setAnchorState("ATTACHED");
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-a"),
                eq(LEASE))).thenReturn(false);
        when(anchorService.loadAnchor(RUN_ID)).thenReturn(winner);

        assertThat(service.recover(
                RUN_ID, stale, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.OWNERSHIP_LOST);
        verifyNoInteractions(redisCache);
        verify(anchorService, never()).completeLiveDagBlockingPreparingAbort(
                any(), any(), any(), any(), any());
    }

    @Test
    void secondRecoveryCannotDeleteWhileFirstOwnsClearingLease()
            throws Exception {
        ToolJobAnchor stale = abortingAnchor();
        ToolJobAnchor firstWinner = abortingAnchor();
        firstWinner.setAnchorState("CLEARING");
        firstWinner.setBlockingOwnerId("worker-b/abort-cleanup/token");
        firstWinner.setBlockingLeaseUntil(
                Instant.parse("2026-07-30T08:30:00Z"));
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-a"),
                eq(LEASE))).thenReturn(false);
        when(anchorService.loadAnchor(RUN_ID)).thenReturn(firstWinner);

        assertThat(service.recover(
                RUN_ID, stale, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.OWNERSHIP_LOST);

        verifyNoInteractions(redisCache);
    }

    @Test
    void expiredCleanupOwnerStopsWhenRedisFenceHasMovedToWinner()
            throws Exception {
        ToolJobAnchor stale = abortingAnchor();
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-a"),
                eq(LEASE))).thenReturn(true);
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), eq(stale), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.MISMATCHED);

        assertThat(service.recover(
                RUN_ID, stale, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.CLEAR_PENDING);

        verify(anchorService, never()).completeLiveDagBlockingPreparingAbort(
                any(), any(), any(), any(), any());
    }

    @Test
    void expiredClearingLeaseCanBeTakenOverAndCompleted()
            throws Exception {
        ToolJobAnchor expired = abortingAnchor();
        expired.setAnchorState("CLEARING");
        expired.setBlockingOwnerId("worker-old/abort-cleanup/token-a");
        expired.setBlockingLeaseUntil(
                Instant.parse("2026-07-30T06:00:00Z"));
        expired.setCleanupSourceOwnerId("worker-a");
        expired.setCleanupSourceLeaseUntil(LEASE);
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.NOT_FOUND);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq(RUN_ID),
                any(ToolJobAnchor.class),
                eq(OPERATION_ID),
                eq("worker-old/abort-cleanup/token-a"),
                eq(expired.getBlockingLeaseUntil()))).thenReturn(true);
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), eq(expired), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                eq(RUN_ID),
                eq(AgentRunStatus.EXECUTING),
                eq(OPERATION_ID),
                contains("/abort-cleanup/"),
                any(Instant.class))).thenReturn(true);

        assertThat(service.recover(
                RUN_ID, expired, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED);

        var cleanupCaptor =
                org.mockito.ArgumentCaptor.forClass(ToolJobAnchor.class);
        verify(redisCache).claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), eq(expired), cleanupCaptor.capture());
        assertThat(cleanupCaptor.getValue().getCleanupSourceOwnerId())
                .isEqualTo("worker-a");
        assertThat(cleanupCaptor.getValue().getCleanupSourceLeaseUntil())
                .isEqualTo(LEASE);
    }

    @Test
    void malformedReleasedIntentNeverTouchesCapacity() throws Exception {
        ToolJobAnchor anchor = abortingAnchor();
        anchor.setAnchorState("PREPARING");

        assertThat(service.recover(
                RUN_ID, anchor, capacityService, anchorService, redisCache))
                .isEqualTo(
                        ToolJobPreparingAbortRecoveryService.Outcome.INVALID_EVIDENCE);

        verifyNoInteractions(capacityService, anchorService, redisCache);
    }

    private void stubRedisCleanup() {
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), any(ToolJobAnchor.class), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
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
