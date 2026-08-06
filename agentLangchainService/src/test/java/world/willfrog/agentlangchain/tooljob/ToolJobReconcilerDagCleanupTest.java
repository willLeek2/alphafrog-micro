package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolJobReconcilerDagCleanupTest {

    @Test
    void acceptedLinearHandoffDoesNotReenterSandboxFinalizer() throws Exception {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setAutoResume(true);
        anchor.setResumeState("LAUNCHING");
        anchor.setResultConsumed(true);
        Fixture fixture = fixture(anchor);

        fixture.reconciler.reconcileFromDue();

        verify(fixture.redisCache).removeDue("run-dag");
        verify(fixture.redisCache).deletePendingCache("run-dag");
        verify(fixture.resumeService).tryResume("run-dag");
        verifyNoInteractions(fixture.sandbox);
        verify(fixture.finalizer, never()).handleTerminal(
                any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void onlineReconcilerPreservesFutureDagLeaseAndSchedulesExpiry() throws Exception {
        Fixture fixture = fixture(liveAnchor());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.redisCache).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.sandbox, never()).getTaskStatus(any());
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void anchorRebuildSchedulesLiveDagWorkerAtLeaseExpiryWithoutPollingSandbox() throws Exception {
        Fixture fixture = fixture(liveAnchor());
        AgentRun run = new AgentRun();
        run.setId("run-dag");
        run.setStatus(AgentRunStatus.EXECUTING);
        when(fixture.anchorService.listActive(100)).thenReturn(List.of(run));
        when(fixture.anchorService.listResumeReady(50)).thenReturn(List.of());

        fixture.reconciler.rebuildFromAnchors();

        verify(fixture.redisCache).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.sandbox, never()).getTaskStatus(any());
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void expiredDagLeaseIsPromotedBeforeSandboxCleanupPolling() throws Exception {
        Fixture fixture = fixture(expiredLiveAnchor());
        when(fixture.anchorService.promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(fixture.anchorService.updateDagCleanup(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.anchorService).promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old"));
        verify(fixture.anchorService).updateDagCleanup(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old"));
        verify(fixture.sandbox).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void workerLostDagKeepsExecutingAndReschedulesUntilSandboxTerminal() throws Exception {
        Fixture fixture = fixture(workerLostAnchor());
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());
        when(fixture.anchorService.updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);

        fixture.reconciler.reconcileFromDue();

        verify(fixture.anchorService).updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old"));
        verify(fixture.redisCache).upsertDue(eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void workerLostDagTerminalUsesCleanupOnlyFinalizer() throws Exception {
        Fixture fixture = fixture(workerLostAnchor());
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(fixture.sandbox.getTaskResult(any(GetTaskResultRequest.class))).thenReturn(
                TaskResultResponse.newBuilder()
                        .setTaskId("task-dag")
                        .setStatus("SUCCEEDED")
                        .setStdout("ok")
                        .setRetryable(false)
                        .build());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.finalizer).handleTerminal(
                eq("run-dag"), any(ToolJobAnchor.class), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(false));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void workerLostDagMissingTerminalBodyUsesBoundedResultLostPath() throws Exception {
        Fixture fixture = fixture(workerLostAnchor());
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(fixture.sandbox.getTaskResult(any(GetTaskResultRequest.class))).thenThrow(
                new IllegalStateException("result unavailable"));

        fixture.reconciler.reconcileFromDue();

        verify(fixture.finalizer).handleNotFound(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void repeatedPreparingLookupFailuresStayCleanupOnlyAndRemainScheduled() throws Exception {
        Fixture fixture = fixture(workerLostPreparingAnchor());
        when(fixture.anchorService.updateDagCleanupPreparing(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old"),
                eq("sha256:" + "a".repeat(64))))
                .thenReturn(true);
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("sandbox unavailable")
                        .build());

        fixture.reconciler.reconcileFromDue();
        fixture.reconciler.reconcileFromDue();

        verify(fixture.sandbox, times(2)).getTaskByOperationId(
                any(GetTaskByOperationIdRequest.class));
        verify(fixture.anchorService, times(2)).updateDagCleanupPreparing(
                eq("run-dag"),
                org.mockito.ArgumentMatchers.argThat(
                        anchor -> "PREPARING".equals(anchor.getAnchorState())
                                && anchor.getTaskId() == null),
                eq("run-dag:call-1:1"),
                eq("owner-old"),
                eq("sha256:" + "a".repeat(64)));
        verify(fixture.redisCache, times(2)).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.sandbox, never()).createTask(any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB), any(), any());
        verify(fixture.anchorService, never()).updateAnchor(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void stalePreparingRetryCasLoserCannotOverwriteTerminalWinner()
            throws Exception {
        Fixture fixture = fixture(workerLostPreparingAnchor());
        when(fixture.anchorService.updateDagCleanupPreparing(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old"),
                eq("sha256:" + "a".repeat(64))))
                .thenReturn(false);
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("sandbox unavailable")
                        .build());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.anchorService).updateDagCleanupPreparing(
                eq("run-dag"),
                org.mockito.ArgumentMatchers.argThat(
                        anchor -> "PREPARING".equals(anchor.getAnchorState())
                                && anchor.getTaskId() == null
                                && anchor.getNextPollAt() != null),
                eq("run-dag:call-1:1"),
                eq("owner-old"),
                eq("sha256:" + "a".repeat(64)));
        verify(fixture.anchorService, never()).updateDagCleanup(
                any(), any(), any(), any());
        verify(fixture.redisCache).upsertDue(eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.redisCache, never()).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB), any(), any());
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void contradictoryReplayFingerprintRemovesDueWithoutAttachingOrResuming()
            throws Exception {
        Fixture fixture = fixture(workerLostPreparingAnchor());
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .build());
        when(fixture.sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder()
                        .setTaskId("task-replayed")
                        .setRequestFingerprint("sha256:" + "b".repeat(64))
                        .build());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.sandbox).createTask(
                org.mockito.ArgumentMatchers.argThat(
                        request -> "run-dag:call-1:1".equals(request.getOperationId())
                                && ("sha256:" + "a".repeat(64)).equals(
                                request.getRequestFingerprint())));
        verify(fixture.redisCache).removeDue("run-dag");
        verify(fixture.anchorService, never()).updateDagCleanupPreparing(
                any(), any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateDagCleanup(
                any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB), any(), any());
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void preparingAbortReleasesFromDurableIntentWithoutSandboxOrResume()
            throws Exception {
        Fixture fixture = fixture(preparingAbortAnchor());
        when(fixture.capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        when(fixture.anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"),
                eq("owner-old"),
                any(Instant.class))).thenReturn(true);
        when(fixture.redisCache.claimPreparingAbortCleanupIndexes(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(fixture.redisCache.removePendingAndDueIfMatches(
                eq("run-dag"),
                eq("run-dag:call-1:1"),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
        when(fixture.anchorService.completeLiveDagBlockingPreparingAbort(
                eq("run-dag"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-dag:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class))).thenReturn(true);

        fixture.reconciler.reconcileFromDue();

        var request = org.mockito.ArgumentCaptor.forClass(
                DataAnalysisReleaseRequest.class);
        verify(fixture.capacityService).releaseReservation(request.capture());
        assertThat(request.getValue().reservation().state())
                .isEqualTo(DataAnalysisReservationState.PREPARING);
        verify(fixture.anchorService).completeLiveDagBlockingPreparingAbort(
                eq("run-dag"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-dag:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class));
        verify(fixture.redisCache).claimPreparingAbortCleanupIndexes(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                any(ToolJobAnchor.class));
        verify(fixture.redisCache).removePendingAndDueIfMatches(
                eq("run-dag"),
                eq("run-dag:call-1:1"),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class));
        verifyNoInteractions(fixture.sandbox);
        verify(fixture.finalizer, never()).handleTerminal(
                any(), any(), any(), any(), any(Boolean.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void staleAbortClearLoserLeavesNewOperationIndexesUntouched()
            throws Exception {
        ToolJobAnchor staleAbort = preparingAbortAnchor();
        ToolJobAnchor winner = baseAnchor();
        winner.setOperationId("run-dag:call-2:1");
        winner.setToolCallId("call-2");
        winner.setAnchorState("ATTACHED");
        Fixture fixture = fixture(staleAbort);
        when(fixture.anchorService.loadAnchor("run-dag"))
                .thenReturn(staleAbort, winner);
        when(fixture.capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(fixture.anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"),
                eq("owner-old"),
                any(Instant.class))).thenReturn(false);

        fixture.reconciler.reconcileFromDue();

        verify(fixture.redisCache, never()).removeDue("run-dag");
        verify(fixture.redisCache, never()).deletePendingCache("run-dag");
        verify(fixture.redisCache, never()).upsertDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.redisCache, never()).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verifyNoInteractions(fixture.sandbox);
        verify(fixture.resumeService, never()).tryResume(any());
    }

    private Fixture fixture(ToolJobAnchor anchor) throws Exception {
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityService capacityService =
                mock(DataAnalysisCapacityService.class);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(redisCache.fetchDue(20)).thenReturn(Set.of("run-dag"));
        when(anchorService.loadAnchor("run-dag")).thenReturn(anchor);

        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache,
                anchorService,
                finalizer,
                resumeService,
                config,
                capacityService);
        inject(reconciler, "sandboxService", sandbox);
        return new Fixture(
                reconciler,
                redisCache,
                anchorService,
                finalizer,
                resumeService,
                capacityService,
                sandbox);
    }

    private ToolJobAnchor liveAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
        anchor.setBlockingLeaseUntil(Instant.now().plusSeconds(60));
        return anchor;
    }

    private ToolJobAnchor expiredLiveAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
        anchor.setBlockingLeaseUntil(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor workerLostAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        return anchor;
    }

    private ToolJobAnchor workerLostPreparingAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.PREPARING,
                null,
                Instant.now());
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setDatasetId("ds-dag")
                .setCode("print(1)")
                .setOperationId(identity.operationId())
                .setRequestFingerprint("sha256:" + "a".repeat(64))
                .build();
        ToolJobAnchor anchor = baseAnchor();
        anchor.setTaskId(null);
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setRequestFingerprint(request.getRequestFingerprint());
        anchor.setCreateRequestJson(JsonFormat.printer().print(request));
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor preparingAbortAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.now());
        ToolJobAnchor anchor = baseAnchor();
        anchor.setTaskId(null);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setBlockingLeaseUntil(Instant.now().minusSeconds(1));
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor baseAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-dag:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-dag");
        anchor.setBlockingOwnerId("owner-old");
        anchor.setAutoResume(false);
        return anchor;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobReconciler reconciler,
            ToolJobRedisCache redisCache,
            ToolJobAnchorService anchorService,
            ToolJobFinalizer finalizer,
            ToolJobResumeService resumeService,
            DataAnalysisCapacityService capacityService,
            PythonSandboxService sandbox) {
    }
}
