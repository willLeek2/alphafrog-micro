package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
        run.setStatus(AgentRunStatus.EXECUTING);
        ToolJobAnchor anchor = dagAnchor(runDisposition);
        anchor.setBlockingLeaseUntil(leaseUntil);
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
                recovery, anchorService, redisCache, finalizer, resumeService, sandbox);
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

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobStartupRecovery recovery,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache,
            ToolJobFinalizer finalizer,
            ToolJobResumeService resumeService,
            PythonSandboxService sandbox) {
    }
}
