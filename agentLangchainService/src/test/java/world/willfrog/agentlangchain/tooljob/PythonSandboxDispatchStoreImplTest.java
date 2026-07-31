package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PythonSandboxDispatchStoreImplTest {

    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
    private final PythonSandboxDispatchStoreImpl store =
            new PythonSandboxDispatchStoreImpl(anchorService, redisCache);

    @Test
    void preparingClaimCannotOverwriteAnExistingAnchor() {
        ToolJobAnchor preparing = anchor("PREPARING");
        when(anchorService.claimPreparing("run-1", preparing, AgentRunStatus.EXECUTING))
                .thenReturn(false);

        assertThat(store.persistPreparing("run-1", preparing)).isFalse();
        verify(anchorService).claimPreparing("run-1", preparing, AgentRunStatus.EXECUTING);
    }

    @Test
    void resumedPreparingReplacesOnlyExactAcceptedHandoff() {
        ToolJobAnchor preparing = anchor("PREPARING");
        when(anchorService.claimPreparingFromResume(
                "run-1", preparing, "resume-token", 7L)).thenReturn(true);

        assertThat(store.persistPreparingFromResume(
                "run-1", preparing, "resume-token", 7L)).isTrue();

        verify(anchorService).claimPreparingFromResume(
                "run-1", preparing, "resume-token", 7L);
        verify(anchorService, never()).claimPreparing(any(), any(), any());
    }

    @Test
    void pendingTransferCommitsPostgresBeforeBestEffortRedis() {
        ToolJobAnchor anchor = anchor("PENDING");
        when(anchorService.updateActiveAndStatus(
                "run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-1:call-1:1"))
                .thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisCache).atomicWritePendingAndDue("run-1", anchor);

        assertThat(store.transferToPending("run-1", anchor)).isTrue();
        var order = inOrder(anchorService, redisCache);
        order.verify(anchorService).updateActiveAndStatus(
                "run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-1:call-1:1");
        order.verify(redisCache).atomicWritePendingAndDue("run-1", anchor);
    }

    @Test
    void pendingTransferDoesNotPublishRedisWhenDurableCasFails() {
        ToolJobAnchor anchor = anchor("PENDING");
        when(anchorService.updateActiveAndStatus(any(), any(), any(), any(), any())).thenReturn(false);

        assertThat(store.transferToPending("run-1", anchor)).isFalse();
        verifyNoInteractions(redisCache);
    }

    @Test
    void attachedAndTerminalSnapshotsRemainWritableWhileRunExecutes() {
        when(anchorService.updateActive(
                eq("run-1"), any(), eq(AgentRunStatus.EXECUTING), eq("run-1:call-1:1")))
                .thenReturn(true);

        assertThat(store.persistAttached("run-1", anchor("ATTACHED"))).isTrue();
        assertThat(store.persistAttached("run-1", anchor("TERMINAL"))).isTrue();
        assertThat(store.persistAttached("run-1", anchor("PENDING"))).isFalse();
        verify(anchorService, times(2)).updateActive(
                eq("run-1"), any(), eq(AgentRunStatus.EXECUTING), eq("run-1:call-1:1"));
    }

    @Test
    void liveDagSnapshotsUseOwnerAndLeaseFence() {
        ToolJobAnchor anchor = liveDagAnchor("ATTACHED");
        Instant expectedLease = anchor.getBlockingLeaseUntil();
        when(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease))
                .thenReturn(true);

        assertThat(store.persistAttached("run-1", anchor)).isTrue();

        verify(anchorService).updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease);
        verify(anchorService, never()).updateActive(any(), any(), any(), any());
    }

    @Test
    void dagLeaseRenewalBindsExactPreviousLease() {
        ToolJobAnchor anchor = liveDagAnchor("ATTACHED");
        Instant previousLease = anchor.getBlockingLeaseUntil();
        anchor.setBlockingLeaseUntil(previousLease.plusSeconds(30));
        when(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", previousLease))
                .thenReturn(true);

        assertThat(store.renewDagBlockingLease(
                "run-1", anchor, previousLease)).isTrue();

        verify(anchorService).updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", previousLease);
        verifyNoInteractions(redisCache);
    }

    @Test
    void dagWorkerLostPromotionCommitsPostgresBeforeBestEffortRedis() {
        ToolJobAnchor anchor = liveDagAnchor("ATTACHED");
        Instant expectedLease = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition("DAG_BLOCKING_WORKER_LOST");
        when(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease))
                .thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisCache).atomicWritePendingAndDue("run-1", anchor);

        assertThat(store.promoteDagBlockingWorkerLost(
                "run-1", anchor, expectedLease)).isTrue();

        var order = inOrder(anchorService, redisCache);
        order.verify(anchorService).updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease);
        order.verify(redisCache).atomicWritePendingAndDue("run-1", anchor);
    }

    @Test
    void dagWorkerLostPromotionDoesNotPublishRedisWhenFenceFails() {
        ToolJobAnchor anchor = liveDagAnchor("ATTACHED");
        Instant expectedLease = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition("DAG_BLOCKING_WORKER_LOST");
        when(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease))
                .thenReturn(false);

        assertThat(store.promoteDagBlockingWorkerLost(
                "run-1", anchor, expectedLease)).isFalse();

        verifyNoInteractions(redisCache);
    }

    @Test
    void dagPreparingAbortBeginBindsOwnerAndExactLeaseFence() {
        ToolJobAnchor anchor = liveDagAnchor("ABORTING");
        Instant expectedLease = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition("DAG_BLOCKING_PREPARING_ABORT");
        when(anchorService.beginLiveDagBlockingPreparingAbort(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease))
                .thenReturn(true);

        assertThat(store.beginDagBlockingPreparingAbort(
                "run-1", anchor, expectedLease)).isTrue();

        verify(anchorService).beginLiveDagBlockingPreparingAbort(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:call-1:1", "worker-a", expectedLease);
    }

    @Test
    void dagPreparingAbortCompletionUsesDurableCleanupAndRedisFences() {
        ToolJobAnchor anchor = liveDagAnchor("ABORTING");
        Instant expectedLease = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition("DAG_BLOCKING_PREPARING_ABORT");
        anchor.setReservationJson("""
                {"reservationId":"run-1:call-1:1",
                 "identity":{"runId":"run-1","toolCallId":"call-1","attempt":1},
                 "resourceClass":"STANDARD","capacityUnits":1,"state":"RELEASED",
                 "taskId":null,"acquiredAt":"2026-07-30T07:00:00Z"}
                """);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-1"),
                any(ToolJobAnchor.class),
                eq("run-1:call-1:1"),
                eq("worker-a"),
                eq(expectedLease))).thenReturn(true);
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq("run-1"), eq(anchor), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq("run-1"),
                eq("run-1:call-1:1"),
                eq("DAG_BLOCKING_PREPARING_ABORT"),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                eq("run-1"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-1:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(true);

        assertThat(store.completeDagBlockingPreparingAbort(
                "run-1", anchor, expectedLease)).isTrue();

        verify(anchorService).completeLiveDagBlockingPreparingAbort(
                eq("run-1"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-1:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class));
    }

    @Test
    void dagPreparingAbortRejectsAttachedAnchorWithoutCallingDatabase() {
        ToolJobAnchor anchor = liveDagAnchor("ATTACHED");

        assertThat(store.beginDagBlockingPreparingAbort(
                "run-1", anchor, anchor.getBlockingLeaseUntil())).isFalse();
        assertThat(store.completeDagBlockingPreparingAbort(
                "run-1", anchor, anchor.getBlockingLeaseUntil())).isFalse();

        verify(anchorService, never()).beginLiveDagBlockingPreparingAbort(
                any(), any(), any(), any(), any(), any());
        verify(anchorService, never()).completeLiveDagBlockingPreparingAbort(
                any(), any(), any(), any(), any());
    }

    @Test
    void synchronousCompletionUsesProofGatedExecutingCas() {
        ToolJobAnchor linear = anchor("TERMINAL");
        when(anchorService.loadAnchor("run-1")).thenReturn(linear);
        when(anchorService.clearSynchronouslyCompleted(
                "run-1", AgentRunStatus.EXECUTING, "run-1:call-1:1"))
                .thenReturn(true);

        assertThat(store.clearSynchronouslyCompleted(
                "run-1", "run-1:call-1:1")).isTrue();

        verify(anchorService).clearSynchronouslyCompleted(
                "run-1", AgentRunStatus.EXECUTING, "run-1:call-1:1");
        verify(anchorService, never()).clearActive(any(), any(), any());
    }

    @Test
    void liveDagSynchronousCompletionBindsProcessOwnerAndExactLease() {
        ToolJobAnchor live = liveDagAnchor("TERMINAL");
        live.setBlockingOwnerId(DagBlockingWorkerLease.processOwnerId());
        when(anchorService.loadAnchor("run-1")).thenReturn(live);
        when(anchorService.clearLiveDagBlockingSynchronouslyCompleted(
                "run-1",
                "run-1:call-1:1",
                DagBlockingWorkerLease.processOwnerId(),
                live.getBlockingLeaseUntil()))
                .thenReturn(true);

        assertThat(store.clearSynchronouslyCompleted(
                "run-1", "run-1:call-1:1")).isTrue();

        verify(anchorService).clearLiveDagBlockingSynchronouslyCompleted(
                "run-1",
                "run-1:call-1:1",
                DagBlockingWorkerLease.processOwnerId(),
                live.getBlockingLeaseUntil());
        verify(anchorService, never()).clearSynchronouslyCompleted(
                any(), any(), any());
    }

    @Test
    void staleOrAbortDagSynchronousCompletionCannotClear() {
        ToolJobAnchor staleOwner = liveDagAnchor("TERMINAL");
        when(anchorService.loadAnchor("run-1")).thenReturn(staleOwner);
        assertThat(store.clearSynchronouslyCompleted(
                "run-1", "run-1:call-1:1")).isFalse();

        ToolJobAnchor aborting = liveDagAnchor("ABORTING");
        aborting.setRunDisposition("DAG_BLOCKING_PREPARING_ABORT");
        aborting.setBlockingOwnerId(DagBlockingWorkerLease.processOwnerId());
        when(anchorService.loadAnchor("run-1")).thenReturn(aborting);
        assertThat(store.clearSynchronouslyCompleted(
                "run-1", "run-1:call-1:1")).isFalse();

        verify(anchorService, never()).clearLiveDagBlockingSynchronouslyCompleted(
                any(), any(), any(), any());
        verify(anchorService, never()).clearSynchronouslyCompleted(
                any(), any(), any());
    }

    private ToolJobAnchor anchor(String state) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAnchorState(state);
        return anchor;
    }

    private ToolJobAnchor liveDagAnchor(String state) {
        ToolJobAnchor anchor = anchor(state);
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-a");
        anchor.setBlockingLeaseUntil(Instant.parse("2099-01-01T00:00:00Z"));
        return anchor;
    }
}
