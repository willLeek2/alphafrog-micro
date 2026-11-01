package world.willfrog.agent.tools.python;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.*;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonSandboxToolsDataIntenseTest {

    /**
     * 成员行上的外部作业身份。
     *
     * <p>它的形态就是「runId + 持久调用身份 + 轮次」，与工具层按成员上下文拼出来的值一致；
     * 不一致时工具层什么都不做（有一条用例专门证明这件事）。</p>
     */
    private static final String EXPECTED_MEMBER_OPERATION = "run-test:call-1--wi-0000:1";

    @TempDir Path tempDir;

    private PythonSandboxTools tools;
    private PythonSandboxService sandbox;
    private DataAnalysisCapacityService capacity;
    private WaitGroupStore waitGroupStore;
    private PythonSandboxDispatchStore dispatchStore;
    private DataAnalysisTerminalRecorder recorder;
    private AgentRunDatasetRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        tools = configure(new PythonSandboxTools(objectMapper));
        world.willfrog.agent.platform.service.PythonRiskReviewService review =
                mock(world.willfrog.agent.platform.service.PythonRiskReviewService.class);
        inject("pythonRiskReviewService", review);
        when(review.evaluate(any(), any(), any())).thenReturn(
                new world.willfrog.agent.platform.service.PythonRiskReviewService.Evaluation(true, null, false));
        sandbox = mock(PythonSandboxService.class);
        capacity = mock(DataAnalysisCapacityService.class);
        waitGroupStore = mock(WaitGroupStore.class);
        dispatchStore = mock(PythonSandboxDispatchStore.class);
        recorder = mock(DataAnalysisTerminalRecorder.class);
        registry = mock(AgentRunDatasetRegistry.class);
        inject("pythonSandboxService", sandbox);
        inject("agentRunDatasetRegistry", registry);
        inject("dataAnalysisCapacityService", capacity);
        inject("waitGroupStore", waitGroupStore);
        inject("dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        inject("pythonSandboxDispatchStore", dispatchStore);
        inject("dataAnalysisTerminalRecorder", recorder);
        inject("fastPathMs", 5L);
        when(dispatchStore.renewDagBlockingLease(eq("run-test"), any(), any()))
                .thenReturn(true);
        when(waitGroupStore.recordMemberPreparing(anyLong(), anyString(), anyString(), anyString()))
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

    private FinanceRecordChannelConfigLoader.Snapshot financeSnapshot() {
        FinanceEnvironmentFact target = new FinanceEnvironmentFact(
                "sha256:target",
                "sha256:image-target",
                "sha256:library-target",
                List.of(new FinanceEnvironmentFact.PackageApi(
                        "alphafrog_finance", "1.0.3", "1.0")),
                true);
        return new FinanceRecordChannelConfigLoader.Snapshot(
                new FinanceRecordChannelLimits(
                        true, 128, 16_384, 262_144, 1_048_576, 262_144,
                        "sha256:target"),
                target,
                "sha256:config",
                false);
    }

    private TaskResultResponse financeTerminalResult() {
        return TaskResultResponse.newBuilder()
                .setTaskId("task-finance")
                .setStatus("SUCCEEDED")
                .setExitCode(0)
                .setStdout("rows=5\n__AF_FINANCE_RESULT_v1__{\"value\":0.12468265}")
                .setDatasetDir("/sandbox/input")
                .setRetryable(false)
                .setResourceUsage(completeUsage())
                .setFinanceRecordChannel(
                        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata
                                .newBuilder()
                                .setEmittedRecordCount(1)
                                .setEmittedRecordBytes(401)
                                .setRecordSetComplete(true)
                                .setRecordDigest("sha256:batch")
                                .build())
                .setExecutionEnvironment(SandboxEnvironmentIdentity.newBuilder()
                        .setEnvironmentId("sha256:actual")
                        .setImageDigest("sha256:image-actual")
                        .setLibrarySetDigest("sha256:library-actual")
                        .addPackageApis(SandboxPackageApi.newBuilder()
                                .setName("alphafrog_finance")
                                .setVersion("1.0.3")
                                .setApiVersion("1.0")
                                .build())
                        .setInventoryComplete(true)
                        .build())
                .build();
    }

    private TaskResultResponse financeFailedTerminalResult() {
        return financeTerminalResult().toBuilder()
                .setTaskId("task-finance-failed")
                .setStatus("FAILED")
                .setExitCode(1)
                .setError("boom")
                .setStderr("boom")
                .setStdout("rows-before-failure\n__AF_FINANCE_RESULT_v1__{\"value\":0.1}")
                .setRetryable(true)
                .build();
    }

    private FinanceRecordExtractionResult financeExtractionResult() {
        FinanceRecordBatch batch = FinanceRecordBatch.builder()
                .runId("run-test")
                .todoId("todo-1")
                .executePythonToolCallId("call-1")
                .renderable(true)
                .build();
        FinanceMetricRecord record = FinanceMetricRecord.builder()
                .methodId("finance.growth.cagr")
                .valueJson("0.12468265")
                .unit("ratio")
                .renderable(true)
                .build();
        return new FinanceRecordExtractionResult(
                batch, List.of(record), "rows=5", "", List.of(), true);
    }

    // ==================== 等待成员：用预计算身份建任务，不写 Run 级进度记录 ====================










    /** 在成员上下文里调用工具：与派发器在真实链路里装上下文的方式一致。 */
    private String invokeAsWaitGroupMember(String code, String datasetIds) {
        return invokeAsWaitGroupMember(code, datasetIds, EXPECTED_MEMBER_OPERATION);
    }



    private String invokeAsWaitGroupMember(String code, String datasetIds, String expectedOperationId) {
        WaitGroupMemberExecutionContext.Snapshot member = new WaitGroupMemberExecutionContext.Snapshot(
                "run-test", 77L, "member-1", 0, "call-1--wi-0000", expectedOperationId, "segment");
        try (WaitGroupMemberExecutionContext.Scope ignored =
                     WaitGroupMemberExecutionContext.install(member)) {
            return tools.executePython(code, datasetIds, null, null, 30);
        }
    }

    private DataAnalysisReservation preparingReservation(String operationId) {
        // 预计算身份的形态就是 runId:持久调用身份:轮次，这里按同样形态造一条。
        String[] parts = operationId.split(":");
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                parts[0], parts[1], Integer.parseInt(parts[2]));
        return new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
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
        return preparingReservation("run-test:call-1:1");
    }

    private SandboxResourceUsage completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(1).setMemoryPeakBytes(2).setLogicalBytesScanned(3)
                .setQueueWaitMillis(4).setPrepareMillis(5).setExecutionWallMillis(6)
                .setCleanupMillis(7).setDatasetOpenCount(1).setExitReason("SUCCEEDED")
                .setAttributionComplete(true).build();
    }

    private SandboxResourceUsage preCreateCanceledUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("UNKNOWN")
                .setExitReason("CANCELED")
                .setAttributionComplete(false)
                .addAllMissingFields(List.of(
                        "cpuMillis", "memoryPeakBytes", "memoryByteMillis",
                        "logicalBytesScanned", "artifactBytesWritten",
                        "temporaryBytesWritten", "queueWaitMillis", "prepareMillis",
                        "executionWallMillis", "cleanupMillis", "datasetOpenCount",
                        "samplingIntervalMillis"))
                .build();
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
