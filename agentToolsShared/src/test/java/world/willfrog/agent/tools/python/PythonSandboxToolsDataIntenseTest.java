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
        AgentContext.setRunId("run-test");
        AgentContext.setToolCallId("call-1");
        AgentContext.setTodoContext("todo-1", 1);
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
        assertThat(observedAnchors.get(0).getTaskId()).isNull();
        assertThat(observedAnchors.get(1).getTaskId()).isEqualTo("task-1");
        verifyNoInteractions(recorder);
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
        verify(dispatchStore, never()).transferToPending(anyString(), any());
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
