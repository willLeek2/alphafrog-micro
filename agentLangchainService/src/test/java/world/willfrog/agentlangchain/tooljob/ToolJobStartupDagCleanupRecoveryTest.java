package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobStartupDagCleanupRecoveryTest {

    @Test
    void startupTakesOverExpiredDagLeaseAndSchedulesCleanupWithoutWaitingTransition() throws Exception {
        Fixture fixture = fixture("RUNNING");

        fixture.recovery.onReady();

        ArgumentCaptor<ToolJobAnchor> marked = ArgumentCaptor.forClass(ToolJobAnchor.class);
        verify(fixture.anchorService).promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), marked.capture(),
                eq("run-dag:call-1:1"), eq("owner-old"));
        assertThat(marked.getValue().getRunDisposition())
                .isEqualTo(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        assertThat(marked.getValue().isAutoResume()).isFalse();
        assertThat(marked.getValue().getFinalizerError())
                .isEqualTo(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);

        verify(fixture.anchorService).updateDagCleanup(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"),
                eq("owner-old"));
        verify(fixture.redisCache).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB),
                eq(AgentRunStatus.EXECUTING), any());
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void startupPreservesFutureDagLeaseAndOnlySchedulesExpiryWakeup() throws Exception {
        Instant leaseUntil = Instant.now().plusSeconds(60);
        Fixture fixture = fixture(
                "RUNNING", ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME, leaseUntil);

        fixture.recovery.onReady();

        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateDagCleanup(any(), any(), any(), any());
        verify(fixture.redisCache).atomicWritePendingAndDue(
                eq("run-dag"),
                org.mockito.ArgumentMatchers.argThat(
                        anchor -> leaseUntil.equals(anchor.getNextPollAt())));
        verify(fixture.sandbox, never()).getTaskStatus(any());
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void startupTerminalDagUsesCleanupOnlyFinalizerWithoutResume() throws Exception {
        Fixture fixture = fixture("SUCCEEDED");
        when(fixture.sandbox.getTaskResult(any(GetTaskResultRequest.class))).thenReturn(
                TaskResultResponse.newBuilder()
                        .setTaskId("task-dag")
                        .setStatus("SUCCEEDED")
                        .setStdout("ok")
                        .setRetryable(false)
                        .build());

        fixture.recovery.onReady();

        verify(fixture.finalizer).handleTerminal(
                eq("run-dag"),
                any(ToolJobAnchor.class),
                eq("SUCCEEDED"),
                any(TaskResultResponse.class),
                eq(false));
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB),
                eq(AgentRunStatus.EXECUTING), any());
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void repeatedStartupContinuesExistingCleanupMarkerWithoutReclaimingIt() throws Exception {
        Fixture fixture = fixture(
                "RUNNING", ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);

        fixture.recovery.onReady();

        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.anchorService).updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old"));
        verify(fixture.redisCache).atomicWritePendingAndDue(
                eq("run-dag"), any(ToolJobAnchor.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void futureDagPreparingRestoresCapacityWithoutResolvingOrQuarantining() throws Exception {
        Instant leaseUntil = Instant.now().plusSeconds(60);
        Fixture fixture = fixture(
                "RUNNING",
                dagPreparingAnchor(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME, leaseUntil));

        fixture.recovery.onReady();

        ArgumentCaptor<List<DataAnalysisReservation>> recovered =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.capacity).recover(recovered.capture(), anyInt(), anyInt());
        assertThat(recovered.getValue()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state()).isEqualTo(DataAnalysisReservationState.PREPARING);
            assertThat(reservation.taskId()).isNull();
        });
        verify(fixture.sandbox, never()).getTaskByOperationId(any());
        verify(fixture.sandbox, never()).createTask(any());
        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateAnchor(any(), any(), any());
        verify(fixture.anchorService, never()).updateAnchorAndStatus(any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActive(any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateDagCleanup(any(), any(), any(), any());
        verify(fixture.redisCache, org.mockito.Mockito.atLeastOnce())
                .atomicWritePendingAndDue(
                        eq("run-dag"),
                        org.mockito.ArgumentMatchers.argThat(
                                anchor -> leaseUntil.equals(anchor.getNextPollAt())));
    }

    @Test
    void futureDagPreparingRedisFailureStillRestoresCapacityWithoutQuarantine() throws Exception {
        Fixture fixture = fixture(
                "RUNNING",
                dagPreparingAnchor(
                        ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME,
                        Instant.now().plusSeconds(60)));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(fixture.redisCache)
                .atomicWritePendingAndDue(eq("run-dag"), any(ToolJobAnchor.class));

        fixture.recovery.onReady();

        ArgumentCaptor<List<DataAnalysisReservation>> recovered =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.capacity).recover(recovered.capture(), anyInt(), anyInt());
        assertThat(recovered.getValue()).singleElement().satisfies(reservation ->
                assertThat(reservation.state()).isEqualTo(DataAnalysisReservationState.PREPARING));
        verify(fixture.sandbox, never()).getTaskByOperationId(any());
        verify(fixture.sandbox, never()).createTask(any());
        verify(fixture.anchorService, never()).promoteExpiredDagBlockingWorkerLost(
                any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActive(any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateDagCleanup(any(), any(), any(), any());
    }

    @Test
    void expiredDagPreparingTakesOverBeforeResolvingAndUsesCleanupFence() throws Exception {
        Fixture fixture = fixture(
                "RUNNING",
                dagPreparingAnchor(
                        ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME,
                        Instant.now().minusSeconds(5)));
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true)
                        .setTaskId("task-recovered")
                        .setRequestFingerprint("sha256:" + "a".repeat(64))
                        .build());

        fixture.recovery.onReady();

        InOrder takeoverOrder = inOrder(fixture.anchorService, fixture.sandbox);
        takeoverOrder.verify(fixture.anchorService).promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old"));
        takeoverOrder.verify(fixture.sandbox).getTaskByOperationId(
                org.mockito.ArgumentMatchers.argThat(
                        request -> "run-dag:call-1:1".equals(request.getOperationId())));
        takeoverOrder.verify(fixture.anchorService).updateDagCleanup(
                eq("run-dag"),
                org.mockito.ArgumentMatchers.argThat(
                        anchor -> "task-recovered".equals(anchor.getTaskId())
                                && "ATTACHED".equals(anchor.getAnchorState())
                                && ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST.equals(
                                anchor.getRunDisposition())),
                eq("run-dag:call-1:1"),
                eq("owner-old"));

        ArgumentCaptor<List<DataAnalysisReservation>> recovered =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.capacity).recover(recovered.capture(), anyInt(), anyInt());
        assertThat(recovered.getValue()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state()).isEqualTo(DataAnalysisReservationState.TASK_ATTACHED);
            assertThat(reservation.taskId()).isEqualTo("task-recovered");
        });
        verify(fixture.anchorService, never()).updateActive(any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB),
                eq(AgentRunStatus.EXECUTING), any());
    }

    @Test
    void failedDagCleanupPreparingReentersResolveUsingCleanupFence() throws Exception {
        Fixture fixture = fixture(
                "RUNNING",
                dagPreparingAnchor(
                        ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST,
                        Instant.now().minusSeconds(5)),
                AgentRunStatus.FAILED);
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true)
                        .setTaskId("task-recovered")
                        .setRequestFingerprint("sha256:" + "a".repeat(64))
                        .build());

        fixture.recovery.onReady();

        verify(fixture.sandbox).getTaskByOperationId(
                org.mockito.ArgumentMatchers.argThat(
                        request -> "run-dag:call-1:1".equals(request.getOperationId())));
        verify(fixture.anchorService, org.mockito.Mockito.atLeastOnce()).updateDagCleanup(
                eq("run-dag"),
                org.mockito.ArgumentMatchers.argThat(
                        anchor -> "task-recovered".equals(anchor.getTaskId())
                                && "ATTACHED".equals(anchor.getAnchorState())),
                eq("run-dag:call-1:1"),
                eq("owner-old"));
        verify(fixture.anchorService, never()).updateActive(any(), any(), any(), any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB),
                any(), any());
    }

    private Fixture fixture(String sandboxStatus) throws Exception {
        return fixture(
                sandboxStatus,
                ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME,
                Instant.now().minusSeconds(5));
    }

    private Fixture fixture(String sandboxStatus, String runDisposition) throws Exception {
        return fixture(sandboxStatus, runDisposition, Instant.now().minusSeconds(5));
    }

    private Fixture fixture(
            String sandboxStatus,
            String runDisposition,
            Instant leaseUntil) throws Exception {
        ToolJobAnchor anchor = dagAnchor(runDisposition);
        anchor.setBlockingLeaseUntil(leaseUntil);
        return fixture(sandboxStatus, anchor);
    }

    private Fixture fixture(
            String sandboxStatus,
            ToolJobAnchor anchor) throws Exception {
        return fixture(sandboxStatus, anchor, AgentRunStatus.EXECUTING);
    }

    private Fixture fixture(
            String sandboxStatus,
            ToolJobAnchor anchor,
            AgentRunStatus runStatus) throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        ToolJobStartupRecovery recovery = new ToolJobStartupRecovery(
                anchorService, redisCache, capacity, properties, finalizer, resumeService, config);
        inject(recovery, "sandboxService", sandbox);

        AgentRun run = new AgentRun();
        run.setId("run-dag");
        run.setStatus(runStatus);
        when(anchorService.listActive(200)).thenReturn(List.of(run));
        when(anchorService.listResumeReady(200)).thenReturn(List.of());
        when(anchorService.loadAnchor("run-dag")).thenReturn(anchor);
        when(anchorService.promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(capacity.recover(anyList(), anyInt(), anyInt()))
                .thenReturn(new DataAnalysisCapacityRecoveryReport(
                        1, 1, 0, 1, properties.getMaxUnits(), properties.getMaxHeavyActive(),
                        false, false, List.of(), DataAnalysisAdmissionState.OPEN));
        when(sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus(sandboxStatus).build());
        return new Fixture(
                recovery, anchorService, redisCache, capacity, finalizer, resumeService, sandbox);
    }

    private ToolJobAnchor dagAnchor(String runDisposition) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.TASK_ATTACHED,
                "task-dag",
                Instant.now());
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setTaskId("task-dag");
        anchor.setBlockingOwnerId("owner-old");
        anchor.setAnchorState("ATTACHED");
        anchor.setRunDisposition(runDisposition);
        anchor.setAutoResume(false);
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        anchor.setTimeoutAt(Instant.now().plusSeconds(60));
        return anchor;
    }

    private ToolJobAnchor dagPreparingAnchor(
            String runDisposition,
            Instant leaseUntil) throws Exception {
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
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setBlockingOwnerId("owner-old");
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setRequestFingerprint(request.getRequestFingerprint());
        anchor.setCreateRequestJson(com.google.protobuf.util.JsonFormat.printer().print(request));
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition(runDisposition);
        anchor.setAutoResume(false);
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        anchor.setTimeoutAt(Instant.now().plusSeconds(60));
        return anchor;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobStartupRecovery recovery,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache,
            DataAnalysisCapacityService capacity,
            ToolJobFinalizer finalizer,
            ToolJobResumeService resumeService,
            PythonSandboxService sandbox) {
    }
}
