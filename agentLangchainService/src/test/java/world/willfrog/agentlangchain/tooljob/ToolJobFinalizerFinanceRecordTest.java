package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies all terminal states flow through the shared formatter per MethodSpec V5.
 *
 * <p>Matrix:
 * <ul>
 *   <li>SUCCEEDED no finance → formatSuccess(raw stdout, [], [])</li>
 *   <li>SUCCEEDED + finance → processor → adapter.project → formatSuccess(ordinaryStdout, projectedResults, projectedNotices)</li>
 *   <li>FAILED/CANCELED no finance → formatFailure(raw stdout, stderr, failureDetail)</li>
 *   <li>FAILED/CANCELED + finance → snapshot guard → processor de-marker → formatFailure(clean stdout, sanitized stderr, failureDetail)</li>
 *   <li>RESULT_LOST → formatFailure("", "", PYTHON_RESULT_LOST)</li>
 *   <li>Snapshot missing with finance data → fail-closed (finance_snapshot_missing)</li>
 *   <li>Processor/adapter exception → fail-closed (finance_processing_failed)</li>
 *   <li>Stderr marker lines → sanitized before formatFailure</li>
 *   <li>ENVELOPE reentry → processor + adapter + formatter called idempotently</li>
 * </ul>
 */
class ToolJobFinalizerFinanceRecordTest {

    private static final String FROZEN_SNAPSHOT = """
            {"effectiveFinanceRecordConfig":{"enabled":true,"recordCountMax":10,\
            "recordMaxBytes":4096,"recordChannelMaxBytes":16384,"stdoutMaxBytes":65536,\
            "stderrMaxBytes":65536,"targetEnvironmentId":"env-1"},\
            "targetEnvironment":{"environmentId":"env-1","imageDigest":"sha256:abc",\
            "librarySetDigest":"sha256:def","packageApis":[{"name":"numpy","version":"1.24",\
            "apiVersion":"1"}],"inventoryComplete":true},\
            "sourceRevision":"sha256:xyz","limitsClamped":false}""";

    private static final String SUCCESS_JSON = """
            {"ok":true,"tool":"executePython","data":{"stdout":"rows=5\\nTotal return=0.1247\\n"},\
            "error":null}""";

    private static final String FAILURE_JSON = """
            {"ok":false,"tool":"executePython","data":{"stdout":"Traceback...","stderr":""},\
            "error":{"code":"PYTHON_EXECUTION_FAILED","message":"Sandbox FAILED",\
            "retryable":true,"action":"检查代码后重试"}}""";

    private static final String RESULT_LOST_JSON = """
            {"ok":false,"tool":"executePython","data":{},"error":{\
            "code":"PYTHON_RESULT_LOST","message":"沙箱结果永久丢失","retryable":false,\
            "action":"重新提交计算任务"}}""";

    /** SUCCEEDED + proto 10 + pipeline complete → processor → adapter → formatter with projected results. */
    @Test
    void succeededWithFinance_shouldUseProcessorAndFormatter() throws Exception {
        FinanceRecordChannelProcessor processor = mock(FinanceRecordChannelProcessor.class);
        FinanceRecordChannelConfigLoader configLoader = mock(FinanceRecordChannelConfigLoader.class);
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        FinanceResultModelAdapter adapter = mock(FinanceResultModelAdapter.class);
        FinanceRecordChannelConfigLoader.Snapshot snapshot = new FinanceRecordChannelConfigLoader.Snapshot(
                new FinanceRecordChannelLimits(true, 10, 4096, 16384, 65536, 65536, "env-1"),
                new FinanceEnvironmentFact("env-1", "sha256:abc", "sha256:def",
                        List.of(new FinanceEnvironmentFact.PackageApi("numpy", "1.24", "1")), true),
                "sha256:xyz", false);

        when(configLoader.parseFrozenSnapshot(FROZEN_SNAPSHOT)).thenReturn(snapshot);

        FinanceRecordExtractionResult extraction = new FinanceRecordExtractionResult(
                null, List.of(), "rows=5\nTotal return=0.1247\n", List.of(), true);
        when(processor.process(any())).thenReturn(extraction);

        var modelResult = new FinanceToolResultFormatter.FinanceModelResult(
                "total_return", 0.1247, "ratio", "computed");
        var notice = new FinanceRecordExtractionResult.ModelNotice(
                "batch_1", "1 record persisted", "ok");
        var projectedBatch = new FinanceResultModelAdapter.ProjectionBatch(
                List.<FinanceToolResultFormatter.FinanceModelResult>of(modelResult),
                List.<FinanceRecordExtractionResult.ModelNotice>of(notice));
        when(adapter.project(extraction)).thenReturn(projectedBatch);
        when(formatter.formatSuccess(eq("rows=5\nTotal return=0.1247\n"),
                eq(List.<FinanceToolResultFormatter.FinanceModelResult>of(modelResult)),
                eq(List.<FinanceRecordExtractionResult.ModelNotice>of(notice))))
                .thenReturn(SUCCESS_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-1"), any(),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);

        ToolJobFinalizer finalizer = finalizerWithFinance(
                anchorService, processor, configLoader, formatter, adapter);

        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata protoMeta =
                world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(1).setEmittedRecordBytes(100)
                        .setRecordSetComplete(true).setRecordDigest("sha256:abc123").build();
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-1")
                .setStdout("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\nNormal output\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .setFinanceRecordChannel(protoMeta).build();

        ToolJobAnchor anchor = basicAnchor("run-1", "tc-1", 1, "task-1", false);
        anchor.setRunDisposition("CANCELED");
        anchor.setFinanceRecordLimitsJson(FROZEN_SNAPSHOT);

        finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", resp, false);

        verify(processor).process(any());
        verify(adapter).project(extraction);
        verify(formatter).formatSuccess(eq("rows=5\nTotal return=0.1247\n"),
                eq(List.<FinanceToolResultFormatter.FinanceModelResult>of(modelResult)),
                eq(List.<FinanceRecordExtractionResult.ModelNotice>of(notice)));
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(SUCCESS_JSON);
        verify(anchorService).cancelFromStatuses(eq("run-1"), any(),
                eq(AgentRunStatus.CANCELED));
    }

    /** SUCCEEDED + finance + processor exception → fail-closed with finance_processing_failed. */
    @Test
    void succeededWithFinanceProcessorException_shouldWriteFinalizerErrorAndReturn() throws Exception {
        FinanceRecordChannelProcessor processor = mock(FinanceRecordChannelProcessor.class);
        FinanceRecordChannelConfigLoader configLoader = mock(FinanceRecordChannelConfigLoader.class);
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        FinanceRecordChannelConfigLoader.Snapshot snapshot = new FinanceRecordChannelConfigLoader.Snapshot(
                new FinanceRecordChannelLimits(true, 10, 4096, 16384, 65536, 65536, "env-1"),
                new FinanceEnvironmentFact("env-1", "sha256:abc", "sha256:def",
                        List.of(new FinanceEnvironmentFact.PackageApi("numpy", "1.24", "1")), true),
                "sha256:xyz", false);

        when(configLoader.parseFrozenSnapshot(FROZEN_SNAPSHOT)).thenReturn(snapshot);
        when(processor.process(any())).thenThrow(new FinanceRecordProcessingException(
                "save_failure", "Failed to persist finance record"));

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-pe"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                processor, configLoader, formatter, mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());

        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata protoMeta =
                world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(1).build();
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-pe")
                .setStdout("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\noutput\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .setFinanceRecordChannel(protoMeta).build();

        ToolJobAnchor anchor = basicAnchor("run-pe", "tc-pe", 1, "task-pe", false);
        anchor.setFinanceRecordLimitsJson(FROZEN_SNAPSHOT);

        finalizer.handleTerminal("run-pe", anchor, "SUCCEEDED", resp, false);

        // Must write stable finalizerError and persist before return
        verify(anchorService).updateAnchor(eq("run-pe"), argThat(a ->
                "finance_processing_failed".equals(a.getFinalizerError())),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Must NOT call updateAnchorAndStatus (ENVELOPE not advanced)
        verify(anchorService, never()).updateAnchorAndStatus(any(), any(), any(), any());
        // Formatter must not be called
        verify(formatter, never()).formatSuccess(any(), any(), any());
    }

    /** SUCCEEDED no finance → formatSuccess with raw stdout, no processor call. */
    @Test
    void succeededNoFinance_shouldUseFormatterDirectly() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatSuccess(eq("rows=5\n"), eq(List.of()), eq(List.of())))
                .thenReturn(SUCCESS_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-2"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-2"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());
        inject(finalizer, "formatter", formatter);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-2").setStdout("rows=5\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-2", "tc-2", 1, "task-2", false);
        anchor.setRunDisposition("CANCELED");

        finalizer.handleTerminal("run-2", anchor, "SUCCEEDED", resp, false);

        verify(formatter).formatSuccess(eq("rows=5\n"), eq(List.of()), eq(List.of()));
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(SUCCESS_JSON);
    }

    /** FAILED no finance → formatFailure with raw stdout/stderr. */
    @Test
    void failedNoFinance_shouldUseFailureFormatter() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatFailure(eq("Traceback..."), eq(""), any())).thenReturn(FAILURE_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-3"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-3"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());
        inject(finalizer, "formatter", formatter);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED").setTaskId("task-3").setStdout("Traceback...")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("PYTHON_ERROR").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-3", "tc-3", 1, "task-3", false);
        anchor.setRunDisposition("CANCELED");

        finalizer.handleTerminal("run-3", anchor, "FAILED", resp, false);

        verify(formatter).formatFailure(eq("Traceback..."), eq(""), argThat(f ->
                "PYTHON_EXECUTION_FAILED".equals(f.code()) && f.retryable()));
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(FAILURE_JSON);
    }

    /** CANCELED no finance → formatFailure with CANCELED code. */
    @Test
    void canceledNoFinance_shouldUseCanceledFailureCode() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatFailure(eq(""), eq(""), any())).thenReturn(FAILURE_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-c"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-c"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());
        inject(finalizer, "formatter", formatter);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("CANCELED").setTaskId("task-c").setStdout("")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("CANCELED").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-c", "tc-c", 1, "task-c", false);
        anchor.setRunDisposition("CANCELED");

        finalizer.handleTerminal("run-c", anchor, "CANCELED", resp, false);

        verify(formatter).formatFailure(eq(""), eq(""), argThat(f ->
                "PYTHON_EXECUTION_CANCELED".equals(f.code()) && !f.retryable()));
    }

    /** RESULT_LOST → formatFailure with PYTHON_RESULT_LOST code. */
    @Test
    void resultLost_shouldUseLostFailureFormatter() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatFailure(eq(""), eq(""), argThat(f ->
                "PYTHON_RESULT_LOST".equals(f.code())))).thenReturn(RESULT_LOST_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-rl"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "formatter", formatter);

        ToolJobAnchor anchor = basicAnchor("run-rl", "tc-rl", 1, "task-rl", false);

        finalizer.handleTerminal("run-rl", anchor, "RESULT_LOST", null, false);

        verify(formatter).formatFailure(eq(""), eq(""), argThat(f ->
                "PYTHON_RESULT_LOST".equals(f.code()) && !f.retryable()
                && "重新提交计算任务".equals(f.action())));
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(RESULT_LOST_JSON);
    }

    /** FAILED/CANCELED with finance markers → processor de-markers, formatter uses de-markered stdout. */
    @Test
    void failedWithFinanceMarkers_shouldDemarkerThenFormatFailure() throws Exception {
        FinanceRecordChannelProcessor processor = mock(FinanceRecordChannelProcessor.class);
        FinanceRecordChannelConfigLoader configLoader = mock(FinanceRecordChannelConfigLoader.class);
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        FinanceRecordChannelConfigLoader.Snapshot snapshot = new FinanceRecordChannelConfigLoader.Snapshot(
                new FinanceRecordChannelLimits(true, 10, 4096, 16384, 65536, 65536, ""),
                null, "sha256:xyz", false);

        when(configLoader.parseFrozenSnapshot(FROZEN_SNAPSHOT)).thenReturn(snapshot);
        FinanceRecordExtractionResult extraction = new FinanceRecordExtractionResult(
                null, List.of(), "clean stdout", List.of(), false);
        when(processor.process(any())).thenReturn(extraction);
        when(formatter.formatFailure(eq("clean stdout"), eq(""), any())).thenReturn(FAILURE_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-fm"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-fm"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = finalizerWithFinance(anchorService, processor, configLoader, formatter,
                mock(FinanceResultModelAdapter.class));

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED").setTaskId("task-fm")
                .setStdout("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\nclean stdout\n")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("PYTHON_ERROR").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-fm", "tc-fm", 1, "task-fm", false);
        anchor.setRunDisposition("CANCELED");
        anchor.setFinanceRecordLimitsJson(FROZEN_SNAPSHOT);

        finalizer.handleTerminal("run-fm", anchor, "FAILED", resp, false);

        verify(processor).process(any());
        verify(formatter).formatFailure(eq("clean stdout"), eq(""), any());
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(FAILURE_JSON);
    }

    /** Finance data present but snapshot missing → fail-closed with specific error. */
    @Test
    void financeDataPresentButSnapshotMissing_shouldFailClosed() throws Exception {
        FinanceRecordChannelProcessor processor = mock(FinanceRecordChannelProcessor.class);
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-fc"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                processor, mock(FinanceRecordChannelConfigLoader.class),
                formatter, mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());

        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata protoMeta =
                world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(1).build();
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-fc").setStdout("output\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .setFinanceRecordChannel(protoMeta).build();

        ToolJobAnchor anchor = basicAnchor("run-fc", "tc-fc", 1, "task-fc", false);
        // snapshot deliberately missing — financeRecordLimitsJson is null

        finalizer.handleTerminal("run-fc", anchor, "SUCCEEDED", resp, false);

        verify(anchorService).updateAnchor(eq("run-fc"), argThat(a ->
                "finance_snapshot_missing".equals(a.getFinalizerError())),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(processor, never()).process(any());
        verify(formatter, never()).formatSuccess(any(), any(), any());
    }

    /** ENVELOPE reentry → processor + formatter called twice (idempotent). */
    @Test
    void envelopeReentry_shouldCallProcessorAndFormatterTwice() throws Exception {
        FinanceRecordChannelProcessor processor = mock(FinanceRecordChannelProcessor.class);
        FinanceRecordChannelConfigLoader configLoader = mock(FinanceRecordChannelConfigLoader.class);
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        FinanceRecordChannelConfigLoader.Snapshot snapshot = new FinanceRecordChannelConfigLoader.Snapshot(
                new FinanceRecordChannelLimits(true, 10, 4096, 16384, 65536, 65536, ""),
                null, "sha256:xyz", false);

        when(configLoader.parseFrozenSnapshot(FROZEN_SNAPSHOT)).thenReturn(snapshot);
        FinanceRecordExtractionResult extraction = new FinanceRecordExtractionResult(
                null, List.of(), "de-markered", List.of(), true);
        when(processor.process(any())).thenReturn(extraction);
        when(formatter.formatSuccess(any(), any(), any())).thenReturn(SUCCESS_JSON);

        FinanceResultModelAdapter reAdapter = mock(FinanceResultModelAdapter.class);
        when(reAdapter.project(any())).thenReturn(
                new FinanceResultModelAdapter.ProjectionBatch(List.of(), List.of()));

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-re"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false).thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-re"), any(),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);

        ToolJobFinalizer finalizer = finalizerWithFinance(anchorService, processor, configLoader, formatter,
                reAdapter);

        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata protoMeta =
                world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(1).setEmittedRecordBytes(100)
                        .setRecordSetComplete(true).setRecordDigest("sha256:abc123").build();
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-re")
                .setStdout("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\noutput\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .setFinanceRecordChannel(protoMeta).build();

        ToolJobAnchor anchor = basicAnchor("run-re", "tc-re", 1, "task-re", false);
        anchor.setRunDisposition("CANCELED");
        anchor.setFinanceRecordLimitsJson(FROZEN_SNAPSHOT);

        // First: CAS fails
        finalizer.handleTerminal("run-re", anchor, "SUCCEEDED", resp, false);
        verify(processor, times(1)).process(any());
        verify(formatter, times(1)).formatSuccess(any(), any(), any());

        // Second: fresh anchor reload → succeeds
        anchor.setFinalizerStep(null);
        finalizer.handleTerminal("run-re", anchor, "SUCCEEDED", resp, false);
        verify(processor, times(2)).process(any());
        verify(formatter, times(2)).formatSuccess(any(), any(), any());
        verify(anchorService).cancelFromStatuses(eq("run-re"), any(),
                eq(AgentRunStatus.CANCELED));
    }

    /** SUCCEEDED no finance → formatter.formatSuccess called with raw stdout, no processor. */
    @Test
    void succeededNoFinance_shouldCallFormatSuccessWithRawStdout() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatSuccess(eq("plain output\n"), eq(List.of()), eq(List.of())))
                .thenReturn(SUCCESS_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-nf"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-nf"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                formatter, mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED").setTaskId("task-nf").setStdout("plain output\n")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("SUCCEEDED").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-nf", "tc-nf", 1, "task-nf", false);
        anchor.setRunDisposition("CANCELED");

        finalizer.handleTerminal("run-nf", anchor, "SUCCEEDED", resp, false);

        verify(formatter).formatSuccess(eq("plain output\n"), eq(List.of()), eq(List.of()));
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(SUCCESS_JSON);
    }

    /** FAILED + hasFinanceData but snapshot missing → fail-closed, no raw fallback. */
    @Test
    void failedWithFinanceDataButSnapshotMissing_shouldFailClosed() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-fs"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                formatter, mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());

        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata protoMeta =
                world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(1).build();
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED").setTaskId("task-fs")
                .setStdout("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\nTraceback...\n")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("PYTHON_ERROR").build())
                .setFinanceRecordChannel(protoMeta).build();

        ToolJobAnchor anchor = basicAnchor("run-fs", "tc-fs", 1, "task-fs", false);
        anchor.setRunDisposition("CANCELED");
        // snapshot deliberately missing

        finalizer.handleTerminal("run-fs", anchor, "FAILED", resp, false);

        verify(anchorService).updateAnchor(eq("run-fs"), argThat(a ->
                "finance_snapshot_missing".equals(a.getFinalizerError())),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(formatter, never()).formatFailure(any(), any(), any());
    }

    /** FAILED with stderr containing markers → markers stripped before formatFailure. */
    @Test
    void failedWithMarkersInStderr_shouldStripMarkers() throws Exception {
        FinanceToolResultFormatter formatter = mock(FinanceToolResultFormatter.class);
        when(formatter.formatFailure(eq("Traceback..."), eq("clean stderr"), any()))
                .thenReturn(FAILURE_JSON);

        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-sm"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-sm"), any(),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class),
                formatter, mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook());
        inject(finalizer, "eventHook", eventHook());

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED").setTaskId("task-sm")
                .setStdout("Traceback...")
                .setStderr("__AF_FINANCE_RESULT_v1__{\"value\":0.12}\nclean stderr\n")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("PYTHON_ERROR").build())
                .build();

        ToolJobAnchor anchor = basicAnchor("run-sm", "tc-sm", 1, "task-sm", false);
        anchor.setRunDisposition("CANCELED");

        finalizer.handleTerminal("run-sm", anchor, "FAILED", resp, false);

        // formatFailure must receive stderr WITHOUT the marker line
        verify(formatter).formatFailure(eq("Traceback..."), eq("clean stderr"), any());
        assertThat(anchor.getTerminalResultPreview()).isEqualTo(FAILURE_JSON);
    }

    // ---- helpers ----

    private static ToolJobFinalizer finalizerWithFinance(
            ToolJobAnchorService anchorService,
            FinanceRecordChannelProcessor processor,
            FinanceRecordChannelConfigLoader configLoader,
            FinanceToolResultFormatter formatter,
            FinanceResultModelAdapter adapter) throws Exception {
        ToolJobFinalizer f = new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                processor, configLoader, formatter, adapter);
        inject(f, "usageHook", usageHook());
        inject(f, "eventHook", eventHook());
        return f;
    }

    private static ToolJobUsageHook usageHook() {
        ToolJobUsageHook hook = mock(ToolJobUsageHook.class);
        when(hook.upsertUsage(any(), any())).thenReturn(true);
        return hook;
    }

    private static ToolJobEventHook eventHook() {
        ToolJobEventHook hook = mock(ToolJobEventHook.class);
        when(hook.emitTerminalEvent(any(), any())).thenReturn(true);
        return hook;
    }

    private static ToolJobAnchor basicAnchor(String runId, String toolCallId,
                                              int attempt, String taskId,
                                              boolean autoResume) {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        a.setToolCallId(toolCallId);
        a.setAttempt(attempt);
        a.setTaskId(taskId);
        a.setAutoResume(autoResume);
        return a;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
