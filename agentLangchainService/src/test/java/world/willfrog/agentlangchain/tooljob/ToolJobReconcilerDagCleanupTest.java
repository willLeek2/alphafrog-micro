package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobReconcilerDagCleanupTest {

    @Test
    void onlineReconcilerNeverStealsLiveDagBlockingWorker() throws Exception {
        Fixture fixture = fixture(liveAnchor());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.redisCache).removeDue("run-dag");
        verify(fixture.sandbox, never()).getTaskStatus(any());
        verify(fixture.finalizer, never()).handleTerminal(any(), any(), any(), any(), any(Boolean.class));
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void anchorRebuildDoesNotPublishLiveDagWorkerIntoBackgroundDueSet() throws Exception {
        Fixture fixture = fixture(liveAnchor());
        AgentRun run = new AgentRun();
        run.setId("run-dag");
        run.setStatus(AgentRunStatus.EXECUTING);
        when(fixture.anchorService.listActive(100)).thenReturn(List.of(run));
        when(fixture.anchorService.listResumeReady(50)).thenReturn(List.of());

        fixture.reconciler.rebuildFromAnchors();

        verify(fixture.redisCache).removeDue("run-dag");
        verify(fixture.redisCache, never()).atomicWritePendingAndDue(any(), any());
        verify(fixture.sandbox, never()).getTaskStatus(any());
        verify(fixture.resumeService, never()).tryResume(any());
    }

    @Test
    void workerLostDagKeepsExecutingAndReschedulesUntilSandboxTerminal() throws Exception {
        Fixture fixture = fixture(workerLostAnchor());
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());
        when(fixture.anchorService.updateAnchor(
                eq("run-dag"), any(ToolJobAnchor.class), eq(AgentRunStatus.EXECUTING)))
                .thenReturn(true);

        fixture.reconciler.reconcileFromDue();

        verify(fixture.anchorService).updateAnchor(
                eq("run-dag"), any(ToolJobAnchor.class), eq(AgentRunStatus.EXECUTING));
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

    private Fixture fixture(ToolJobAnchor anchor) throws Exception {
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(redisCache.fetchDue(20)).thenReturn(Set.of("run-dag"));
        when(anchorService.loadAnchor("run-dag")).thenReturn(anchor);

        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
        inject(reconciler, "sandboxService", sandbox);
        return new Fixture(
                reconciler, redisCache, anchorService, finalizer, resumeService, sandbox);
    }

    private ToolJobAnchor liveAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
        return anchor;
    }

    private ToolJobAnchor workerLostAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        return anchor;
    }

    private ToolJobAnchor baseAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-dag:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-dag");
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
            PythonSandboxService sandbox) {
    }
}
