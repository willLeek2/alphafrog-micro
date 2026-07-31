package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonSandboxToolsDataIntenseTest {

    @TempDir Path tempDir;

    private PythonSandboxTools tools;
    private PythonSandboxService sandbox;
    private DataAnalysisCapacityService capacity;
    private PythonSandboxDispatchStore dispatchStore;
    private DataAnalysisTerminalRecorder recorder;
    private AgentRunDatasetRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        tools = new PythonSandboxTools(new ObjectMapper().findAndRegisterModules());
        sandbox = mock(PythonSandboxService.class);
        capacity = mock(DataAnalysisCapacityService.class);
        dispatchStore = mock(PythonSandboxDispatchStore.class);
        recorder = mock(DataAnalysisTerminalRecorder.class);
        registry = mock(AgentRunDatasetRegistry.class);
        inject("pythonSandboxService", sandbox);
        inject("agentRunDatasetRegistry", registry);
        inject("dataAnalysisCapacityService", capacity);
        inject("dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        inject("pythonSandboxDispatchStore", dispatchStore);
        inject("dataAnalysisTerminalRecorder", recorder);
        inject("fastPathMs", 5L);
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), any()))
                .thenReturn(true);
        when(dispatchStore.promoteDagBlockingWorkerLost(eq("run-test"), any(), any()))
                .thenReturn(true);
        when(dispatchStore.beginDagBlockingPreparingAbort(eq("run-test"), any(), any()))
                .thenReturn(true);
        when(dispatchStore.completeDagBlockingPreparingAbort(eq("run-test"), any(), any()))
                .thenReturn(true);
        AgentContext.setRunId("run-test");
        AgentContext.setToolCallId("call-1");
        AgentContext.setTodoContext("todo-1", 1);
        AgentContext.setWorkflow("linear");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void slowTaskPersistsPreparingAttachedPendingAndThrowsTypedSignal() throws Exception {
        fixtureDataset();
        List<ToolJobAnchor> observedAnchors = new ArrayList<>();
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenAnswer(invocation -> {
            observedAnchors.add(snapshot(invocation.getArgument(1)));
            return true;
        });
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenAnswer(invocation -> {
            observedAnchors.add(snapshot(invocation.getArgument(1)));
            return true;
        });
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenAnswer(invocation -> {
            observedAnchors.add(snapshot(invocation.getArgument(1)));
            return true;
        });
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        ExternalToolJobPendingException pending = assertThrows(
                ExternalToolJobPendingException.class,
                () -> tools.executePython("print(1)", "1", null, null, 30));

        assertThat(pending.getRunId()).isEqualTo("run-test");
        assertThat(pending.getToolCallId()).isEqualTo("call-1");
        ArgumentCaptor<ExecuteRequest> request = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(sandbox).createTask(request.capture());
        assertThat(request.getValue().getOperationId()).isEqualTo("run-test:call-1:1");
        assertThat(request.getValue().getRequestFingerprint()).startsWith("sha256:");
        assertThat(request.getValue().getImmutableDatasetSnapshotDigest()).startsWith("sha256:");
        assertThat(request.getValue().getEstimatedRows()).isEqualTo(2L);
        assertThat(request.getValue().getEstimatedBytes()).isPositive();
        assertThat(observedAnchors).extracting(ToolJobAnchor::getAnchorState)
                .containsExactly("PREPARING", "ATTACHED", "PENDING");
        assertThat(observedAnchors).extracting(ToolJobAnchor::getSchemaVersion)
                .containsOnly(2);
        assertThat(observedAnchors.get(0).getTaskId()).isNull();
        assertThat(observedAnchors.get(1).getTaskId()).isEqualTo("task-1");
        verifyNoInteractions(recorder);
    }

    @Test
    void resumedSlowTaskConsumesExactOldHandoffBeforeSandboxCreate() throws Exception {
        fixtureDataset();
        AgentContext.setToolJobResumeHandoff("resume-token", 9L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparingFromResume(
                eq("run-test"), any(), eq("resume-token"), eq(9L))).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-resumed")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("print(2)", "1", null, null, 30));

        verify(dispatchStore).persistPreparingFromResume(
                eq("run-test"), any(), eq("resume-token"), eq(9L));
        verify(dispatchStore, never()).persistPreparing(eq("run-test"), any());
        assertThat(AgentContext.getToolJobResumeToken()).isNull();
        assertThat(AgentContext.getToolJobResumeLeaseVersion()).isNull();
    }

    @Test
    void commonLibrariesDoNotBecomeHeavyHintsAndEstimateStaysStandard() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("import numpy, pandas", "1",
                        null, "numpy,pandas", 30));

        ArgumentCaptor<DataAnalysisEstimate> estimate =
                ArgumentCaptor.forClass(DataAnalysisEstimate.class);
        verify(capacity).reserve(any(), estimate.capture());
        assertThat(estimate.getValue().heavyOperationHints()).isEmpty();
        assertThat(estimate.getValue().resourceClass())
                .isEqualTo(DataAnalysisResourceClass.STANDARD);
        assertThat(estimate.getValue().capacityUnits()).isEqualTo(1);
        verify(sandbox).createTask(argThat(request ->
                "STANDARD".equals(request.getResourceClass())
                        && request.getCapacityUnits() == 1
                        && request.getLibrariesList().equals(List.of("numpy", "pandas"))));
    }

    @Test
    void fastSuccessRecordsAndReleasesBeforeExecutorAcknowledgement() throws Exception {
        fixtureDataset();
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("ok").setDatasetDir("/sandbox/input")
                .setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output).contains("\"ok\":true").contains("\"stdout\":\"ok\"");
        verify(capacity).releaseReservation(argThat(request ->
                request.proof() instanceof DataAnalysisReleaseProof.Terminal));
        verify(recorder).upsert(argThat(envelope ->
                !envelope.background() && !envelope.retryable()
                        && "SUCCEEDED".equals(envelope.terminalStatus())));
        verify(dispatchStore, never()).clearActive(anyString(), anyString());
        verify(dispatchStore, never()).clearSynchronouslyCompleted(anyString(), anyString());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
    }

    @Test
    void unknownWorkflowFailsClosedBeforeCapacitySandboxOrDispatch() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("experimental");

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"WORKFLOW_MODE_UNAVAILABLE\"")
                .contains("\"workflow\":\"experimental\"");
        verifyNoInteractions(capacity, sandbox, dispatchStore, recorder);
    }

    @Test
    void dagSlowTaskBlocksToSuccessWithoutPendingTransfer() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        List<ToolJobAnchor> observedAnchors = new ArrayList<>();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenAnswer(invocation -> {
            observedAnchors.add(snapshot(invocation.getArgument(1)));
            return true;
        });
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenAnswer(invocation -> {
            observedAnchors.add(snapshot(invocation.getArgument(1)));
            return true;
        });
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build(),
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-dag").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("dag-ok").setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output).contains("\"ok\":true").contains("\"stdout\":\"dag-ok\"");
        assertThat(observedAnchors.get(0).getRunDisposition())
                .isEqualTo("DAG_BLOCKING_NO_RESUME");
        assertThat(observedAnchors.get(0).isAutoResume()).isFalse();
        assertThat(observedAnchors.get(0).getBlockingOwnerId()).isNotBlank();
        assertThat(observedAnchors.get(0).getBlockingLeaseUntil()).isAfter(Instant.now());
        assertThat(observedAnchors.get(observedAnchors.size() - 1).getAnchorState())
                .isEqualTo("TERMINAL");
        assertThat(observedAnchors.get(observedAnchors.size() - 1).isUsagePersisted()).isTrue();
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity).releaseReservation(any());
        verify(recorder).upsert(argThat(envelope ->
                "SUCCEEDED".equals(envelope.terminalStatus()) && !envelope.background()));
    }

    @Test
    void dagFailedTaskReturnsFailureWithoutPendingTransfer() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-failed")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build(),
                TaskStatusResponse.newBuilder().setStatus("FAILED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-dag-failed").setStatus("FAILED").setExitCode(1)
                .setError("boom").setStderr("boom").setRetryable(true)
                .setResourceUsage(completeUsage()).build());

        String output = tools.executePython(
                "raise RuntimeError()", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"TASK_FAILED\"")
                .contains("\"status\":\"FAILED\"");
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity).releaseReservation(any());
        verify(recorder).upsert(argThat(envelope ->
                "FAILED".equals(envelope.terminalStatus()) && envelope.retryable()));
    }

    @Test
    void dagTerminalWithoutCompleteProofReturnsNormallyAndRetainsAnchor() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-incomplete")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build(),
                TaskStatusResponse.newBuilder().setStatus("FAILED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-dag-incomplete").setStatus("FAILED")
                .setError("retryability missing")
                .setResourceUsage(completeUsage()).build());

        String output = tools.executePython(
                "raise RuntimeError()", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_TERMINAL_INCOMPLETE\"");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && !anchor.isAutoResume()
                        && "DAG_BLOCKING_TERMINAL_INCOMPLETE".equals(anchor.getFinalizerError())
                        && anchor.getBlockingOwnerId() != null
                        && anchor.getBlockingLeaseUntil() != null),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagBlockingTimeoutUsesFrozenDeadlineWithoutPendingTransfer() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-timeout")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        long startedAt = System.currentTimeMillis();
        String output = tools.executePython("print(1)", "1", null, null, 1);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_TIMEOUT\"")
                .contains("timeout_at");
        assertThat(System.currentTimeMillis() - startedAt).isBetween(850L, 2500L);
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_TIMEOUT".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagBlockingInterruptionPreservesInterruptAndRetainsAnchor() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-interrupted")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        Thread.currentThread().interrupt();
        try {
            String output = tools.executePython("print(1)", "1", null, null, 30);
            assertThat(output)
                    .contains("\"ok\":false")
                    .contains("\"code\":\"DAG_BLOCKING_INTERRUPTED\"");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_INTERRUPTED".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagFastPathStatusRpcFailureTransfersCleanupOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-fast-rpc")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any()))
                .thenThrow(new IllegalStateException("status transport unavailable"));

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_POLL_FAILED\"")
                .contains("status transport unavailable");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_POLL_FAILED".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagFastPathStatusErrorResponseTransfersCleanupOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-status-error")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder()
                        .setError("status gateway unavailable")
                        .build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_POLL_FAILED\"")
                .contains("status gateway unavailable");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_POLL_FAILED".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagCleanupOwnershipTransferExceptionFailsClosedAsLeaseLost() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.promoteDagBlockingWorkerLost(eq("run-test"), any(), any()))
                .thenThrow(new IllegalStateException("postgres unavailable"));
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-promote-error")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any()))
                .thenThrow(new IllegalStateException("status transport unavailable"));

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"), any(), any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagBlockingStatusRpcFailureTransfersCleanupOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        inject("fastPathMs", 1L);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-blocking-rpc")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any()))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("RUNNING").build())
                .thenThrow(new IllegalStateException("blocking status transport unavailable"));

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_POLL_FAILED\"")
                .contains("blocking status transport unavailable");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_POLL_FAILED".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagTerminalResultRpcFailureTransfersCleanupOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-result-rpc")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any()))
                .thenThrow(new IllegalStateException("result transport unavailable"));

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_RESULT_FETCH_FAILED\"")
                .contains("result transport unavailable");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && "DAG_BLOCKING_RESULT_FETCH_FAILED".equals(anchor.getFinalizerError())),
                any(Instant.class));
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagLeaseRenewalFailureStopsBeforeStatusAndDoesNotPromote() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        Instant nearExpiry = Instant.now().plusSeconds(1);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenAnswer(invocation -> {
            ToolJobAnchor anchor = invocation.getArgument(1);
            anchor.setBlockingLeaseUntil(nearExpiry);
            return true;
        });
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), eq(nearExpiry)))
                .thenReturn(false);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-lease-lost")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(dispatchStore).renewDagBlockingLease(
                eq("run-test"),
                argThat(anchor -> nearExpiry.equals(anchor.getBlockingLeaseUntil())),
                eq(nearExpiry));
        verify(sandbox, never()).getTaskStatus(any());
        verify(dispatchStore, never()).promoteDagBlockingWorkerLost(anyString(), any(), any());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagLeaseRenewalExceptionStopsBeforeStatusAndDoesNotPromote() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        Instant nearExpiry = Instant.now().plusSeconds(1);
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenAnswer(invocation -> {
            ToolJobAnchor anchor = invocation.getArgument(1);
            anchor.setBlockingLeaseUntil(nearExpiry);
            return true;
        });
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), eq(nearExpiry)))
                .thenThrow(new IllegalStateException("postgres unavailable"));
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-renew-error")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(sandbox, never()).getTaskStatus(any());
        verify(dispatchStore, never()).promoteDagBlockingWorkerLost(anyString(), any(), any());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagNearExpiryLeaseRenewsWithSameOwnerFence() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        Instant nearExpiry = Instant.now().plusSeconds(1);
        AtomicReference<String> owner = new AtomicReference<>();
        AtomicReference<String> renewedOwner = new AtomicReference<>();
        AtomicReference<Instant> firstExpectedLease = new AtomicReference<>();
        AtomicReference<Instant> firstRenewedLease = new AtomicReference<>();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenAnswer(invocation -> {
            ToolJobAnchor anchor = invocation.getArgument(1);
            owner.set(anchor.getBlockingOwnerId());
            anchor.setBlockingLeaseUntil(nearExpiry);
            return true;
        });
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), any()))
                .thenAnswer(invocation -> {
                    ToolJobAnchor anchor = invocation.getArgument(1);
                    if (firstExpectedLease.get() == null) {
                        renewedOwner.set(anchor.getBlockingOwnerId());
                        firstExpectedLease.set(invocation.getArgument(2));
                        firstRenewedLease.set(anchor.getBlockingLeaseUntil());
                    }
                    return true;
                });
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-dag-renew")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-dag-renew").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("renewed").setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output).contains("\"ok\":true").contains("\"stdout\":\"renewed\"");
        assertThat(owner.get()).isNotBlank();
        assertThat(renewedOwner.get()).isEqualTo(owner.get());
        assertThat(firstExpectedLease.get()).isEqualTo(nearExpiry);
        assertThat(firstRenewedLease.get()).isAfter(nearExpiry);
        verify(dispatchStore, atLeastOnce()).renewDagBlockingLease(
                eq("run-test"), any(), any(Instant.class));
        verify(dispatchStore, never()).promoteDagBlockingWorkerLost(anyString(), any(), any());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
    }

    @Test
    void dagLateAuthoritativeNotFoundCannotClearAfterLeaseTakeover() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), any()))
                .thenReturn(false);
        when(sandbox.createTask(any()))
                .thenThrow(new IllegalStateException("create response lost"));
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(dispatchStore).renewDagBlockingLease(eq("run-test"), any(), any(Instant.class));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).beginDagBlockingPreparingAbort(anyString(), any(), any());
        verify(dispatchStore, never()).completeDagBlockingPreparingAbort(anyString(), any(), any());
        verify(dispatchStore, never()).clearActive(anyString(), anyString());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagInvalidCreateResponseUsesDurableTwoPhasePreparingAbort() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder().setError("create rejected").build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"CREATE_TASK_FAILED\"");
        verify(dispatchStore).renewDagBlockingLease(eq("run-test"), any(), any(Instant.class));
        verify(dispatchStore).beginDagBlockingPreparingAbort(
                eq("run-test"),
                argThat(anchor -> "ABORTING".equals(anchor.getAnchorState())
                        && "DAG_BLOCKING_PREPARING_ABORT".equals(anchor.getRunDisposition())
                        && anchor.getBlockingOwnerId() != null
                        && anchor.getReservationJson().contains("\"state\":\"RELEASED\"")),
                any(Instant.class));
        verify(dispatchStore).completeDagBlockingPreparingAbort(
                eq("run-test"),
                argThat(anchor -> "ABORTING".equals(anchor.getAnchorState())
                        && "DAG_BLOCKING_PREPARING_ABORT".equals(anchor.getRunDisposition())),
                any(Instant.class));
        var order = inOrder(dispatchStore, capacity);
        order.verify(dispatchStore).beginDagBlockingPreparingAbort(
                eq("run-test"), any(), any(Instant.class));
        order.verify(capacity).releaseReservation(argThat(request ->
                request.reason() == DataAnalysisReleaseReason.PREPARING_ABORTED));
        order.verify(dispatchStore).completeDagBlockingPreparingAbort(
                eq("run-test"), any(), any(Instant.class));
        verify(dispatchStore, never()).clearActive(anyString(), anyString());
        verifyNoInteractions(recorder);
    }

    @Test
    void dagPreparingAbortFenceFailureDoesNotReleaseCapacity() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.beginDagBlockingPreparingAbort(eq("run-test"), any(), any()))
                .thenReturn(false);
        when(sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder().setError("create rejected").build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(dispatchStore).beginDagBlockingPreparingAbort(
                eq("run-test"), any(), any(Instant.class));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).completeDagBlockingPreparingAbort(
                anyString(), any(), any());
    }

    @Test
    void dagPreparingAbortCompletionFailureLeavesReleasedIntentForReentry() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.completeDagBlockingPreparingAbort(eq("run-test"), any(), any()))
                .thenReturn(false);
        when(sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder().setError("create rejected").build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LEASE_LOST\"");
        verify(dispatchStore).beginDagBlockingPreparingAbort(
                eq("run-test"),
                argThat(anchor -> "ABORTING".equals(anchor.getAnchorState())
                        && anchor.getReservationJson().contains("\"state\":\"RELEASED\"")),
                any(Instant.class));
        verify(capacity).releaseReservation(argThat(request ->
                request.reason() == DataAnalysisReleaseReason.PREPARING_ABORTED));
        verify(dispatchStore).completeDagBlockingPreparingAbort(
                eq("run-test"), any(), any(Instant.class));
    }

    @Test
    void dagPreparingAbortBeginExceptionFallsBackToWorkerLostWithoutRelease() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.beginDagBlockingPreparingAbort(eq("run-test"), any(), any()))
                .thenThrow(new IllegalStateException("abort CAS outcome unknown"));
        when(sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder().setError("create rejected").build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LIFECYCLE_FAILED\"");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "PREPARING".equals(anchor.getAnchorState())
                        && "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())
                        && anchor.getReservationJson().contains("\"state\":\"PREPARING\"")),
                any(Instant.class));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).completeDagBlockingPreparingAbort(
                anyString(), any(), any());
    }

    @Test
    void terminalResultWithoutRetryabilityFailsClosedIntoPending() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("FAILED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("FAILED").setError("boom")
                .setResourceUsage(completeUsage()).build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("raise RuntimeError()", "1", null, null, 30));

        verify(dispatchStore).transferToPending(eq("run-test"), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void invalidTerminalUsagePersistsProofAndDefersReleaseToPendingFinalizer() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("SUCCEEDED").setStdout("ok")
                .setRetryable(false)
                .setResourceUsage(completeUsage().toBuilder().setResourceClass("HEAVY").build())
                .build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("print(1)", "1", null, null, 30));

        verify(dispatchStore, atLeastOnce()).persistAttached(eq("run-test"), argThat(anchor ->
                anchor.getTerminalAt() != null && "ENVELOPE".equals(anchor.getFinalizerStep())));
        verify(dispatchStore).transferToPending(eq("run-test"), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void ambiguousCreateRpcRecoversExistingOperationWithoutSecondTaskOrRelease() throws Exception {
        fixtureDataset();
        AtomicReference<String> fingerprint = new AtomicReference<>();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            fingerprint.set(request.getRequestFingerprint());
            throw new IllegalStateException("rpc response lost");
        });
        when(sandbox.getTaskByOperationId(any())).thenAnswer(invocation ->
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true).setTaskId("task-existing")
                        .setRequestFingerprint(fingerprint.get()).build());
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("print(1)", "1", null, null, 30));

        verify(sandbox, times(1)).createTask(any());
        verify(sandbox).getTaskByOperationId(argThat(request ->
                "run-test:call-1:1".equals(request.getOperationId())));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore).transferToPending(eq("run-test"), argThat(anchor ->
                "task-existing".equals(anchor.getTaskId())));
    }

    @Test
    void dagPersistAttachedExceptionTransfersOuterFallbackOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any()))
                .thenThrow(new IllegalStateException("postgres write outcome unknown"));
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder()
                    .setTaskId("task-dag-attach-unknown")
                    .setRequestFingerprint(request.getRequestFingerprint())
                    .build();
        });

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LIFECYCLE_FAILED\"")
                .contains("postgres write outcome unknown");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "ATTACHED".equals(anchor.getAnchorState())
                        && "task-dag-attach-unknown".equals(anchor.getTaskId())
                        && "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())),
                any(Instant.class));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
    }

    @Test
    void dagCapacityRestoreConflictTransfersAttachedProofToOuterFallback() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.CONFLICT);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder()
                    .setTaskId("task-dag-capacity-conflict")
                    .setRequestFingerprint(request.getRequestFingerprint())
                    .build();
        });

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LIFECYCLE_FAILED\"");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "ATTACHED".equals(anchor.getAnchorState())
                        && "task-dag-capacity-conflict".equals(anchor.getTaskId())
                        && anchor.getReservationJson().contains("\"state\":\"TASK_ATTACHED\"")
                        && "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())),
                any(Instant.class));
        verify(dispatchStore, never()).persistAttached(anyString(), any());
        verify(capacity, never()).releaseReservation(any());
    }

    @Test
    void dagAmbiguousCreateExceptionTransfersOuterFallbackOwnership() throws Exception {
        fixtureDataset();
        AgentContext.setWorkflow("dag");
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any()))
                .thenThrow(new IllegalStateException("create response lost"));
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("lookup unavailable")
                        .build());

        String output = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("\"code\":\"DAG_BLOCKING_LIFECYCLE_FAILED\"")
                .contains("create response lost");
        verify(dispatchStore).promoteDagBlockingWorkerLost(
                eq("run-test"),
                argThat(anchor -> "PREPARING".equals(anchor.getAnchorState())
                        && anchor.getTaskId() == null
                        && "DAG_BLOCKING_WORKER_LOST".equals(anchor.getRunDisposition())),
                any(Instant.class));
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).beginDagBlockingPreparingAbort(
                anyString(), any(), any());
        verify(dispatchStore, never()).transferToPending(anyString(), any());
    }

    @Test
    void lookupTransportErrorKeepsPreparingReservationAndDoesNotClearAnchor() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenThrow(new IllegalStateException("rpc response lost"));
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("sandbox temporarily unavailable")
                        .build());

        String result = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(result).contains("\"ok\":false", "\"code\":\"TOOL_ERROR\"");
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).clearActive(any(), any());
        verify(dispatchStore, never()).persistAttached(any(), any());
        verify(dispatchStore, never()).transferToPending(any(), any());
    }

    @Test
    void foundOperationWithoutFingerprintKeepsPreparingReservation() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenThrow(new IllegalStateException("rpc response lost"));
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true)
                        .setTaskId("task-existing")
                        .build());

        String result = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(result).contains("\"ok\":false", "\"code\":\"TOOL_ERROR\"");
        verify(capacity, never()).releaseReservation(any());
        verify(dispatchStore, never()).clearActive(any(), any());
        verify(dispatchStore, never()).persistAttached(any(), any());
    }

    @Test
    void createResponseWithoutFingerprintKeepsPreparingWithoutRelease() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenReturn(
                ExecuteResponse.newBuilder().setTaskId("task-no-fingerprint").build());
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false).build());

        String result = tools.executePython("print(1)", "1", null, null, 30);

        assertThat(result).contains("\"ok\":false", "\"code\":\"TOOL_ERROR\"");
        verify(dispatchStore, never()).persistAttached(any(), any());
        verify(dispatchStore, never()).transferToPending(any(), any());
        verify(capacity, never()).releaseReservation(any());
    }

    @Test
    void createResponseWithoutFingerprintMayAttachAfterExactOperationLookup() throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        AtomicReference<String> fingerprint = new AtomicReference<>();
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            fingerprint.set(request.getRequestFingerprint());
            return ExecuteResponse.newBuilder().setTaskId("task-confirmed").build();
        });
        when(sandbox.getTaskByOperationId(any())).thenAnswer(invocation ->
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true)
                        .setTaskId("task-confirmed")
                        .setRequestFingerprint(fingerprint.get())
                        .build());

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("print(1)", "1", null, null, 30));

        verify(dispatchStore).persistAttached(eq("run-test"), argThat(anchor ->
                "task-confirmed".equals(anchor.getTaskId())));
        verify(dispatchStore).transferToPending(eq("run-test"), any());
        verify(capacity, never()).releaseReservation(any());
    }

    @Test
    void terminalTaskIdMismatchDefersToPendingWithoutRelease() throws Exception {
        assertInvalidTerminalResultDefersToPending(
                "SUCCEEDED",
                TaskResultResponse.newBuilder()
                        .setTaskId("task-other").setStatus("SUCCEEDED")
                        .setStdout("ok").setRetryable(false)
                        .setResourceUsage(completeUsage()).build());
    }

    @Test
    void terminalStatusMismatchDefersToPendingWithoutRelease() throws Exception {
        assertInvalidTerminalResultDefersToPending(
                "SUCCEEDED",
                TaskResultResponse.newBuilder()
                        .setTaskId("task-1").setStatus("FAILED").setError("boom")
                        .setRetryable(true).setResourceUsage(completeUsage()).build());
    }

    @Test
    void successfulTerminalWithoutPayloadDefersToPendingWithoutRelease() throws Exception {
        assertInvalidTerminalResultDefersToPending(
                "SUCCEEDED",
                TaskResultResponse.newBuilder()
                        .setTaskId("task-1").setStatus("SUCCEEDED")
                        .setRetryable(false).setResourceUsage(completeUsage()).build());
    }

    private void assertInvalidTerminalResultDefersToPending(
            String polledStatus,
            TaskResultResponse terminalResult) throws Exception {
        fixtureDataset();
        when(capacity.reserve(any(), any())).thenReturn(preparingReservation());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(dispatchStore.persistPreparing(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.persistAttached(eq("run-test"), any())).thenReturn(true);
        when(dispatchStore.transferToPending(eq("run-test"), any())).thenReturn(true);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-1")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus(polledStatus).build());
        when(sandbox.getTaskResult(any())).thenReturn(terminalResult);

        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("print(1)", "1", null, null, 30));

        verify(dispatchStore).transferToPending(eq("run-test"), any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    private void fixtureDataset() throws Exception {
        Path csv = tempDir.resolve("prices.csv");
        Files.writeString(csv, "ts_code,close\n600000.SH,10\n600001.SH,11\n");
        Path meta = tempDir.resolve("prices.meta.json");
        Files.writeString(meta, "{\"rowCount\":2,\"bytes\":" + Files.size(csv)
                + ",\"columns\":[\"ts_code\",\"close\"]}");
        AgentRunDatasetEntry dataset = AgentRunDatasetEntry.forDataset(
                1, "ds-1", csv.toString(), "600000.SH", "prices.csv");
        when(registry.snapshot("run-test")).thenReturn(
                new AgentRunDatasetSnapshot(List.of(dataset), List.of()));
        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of());
        when(registry.findDatasetByNumber("run-test", 1)).thenReturn(java.util.Optional.of(dataset));
    }

    private DataAnalysisReservation preparingReservation() {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-test", "call-1", 1);
        return new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
    }

    private SandboxResourceUsage completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(1).setMemoryPeakBytes(2).setLogicalBytesScanned(3)
                .setQueueWaitMillis(4).setPrepareMillis(5).setExecutionWallMillis(6)
                .setCleanupMillis(7).setDatasetOpenCount(1).setExitReason("SUCCEEDED")
                .setAttributionComplete(true).build();
    }

    private ToolJobAnchor snapshot(ToolJobAnchor anchor) {
        return ToolJobAnchor.fromJson(anchor.toJson());
    }

    private void inject(String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = PythonSandboxTools.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(tools, value);
    }
}
