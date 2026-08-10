package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C-slice: durable Harness retryable classification into terminal envelope.
 * Verifies presence-aware nullable Boolean terminalRetryable flows from
 * TaskResultResponse through ENVELOPE step into buildEnvelope, with
 * fail-closed on missing classification.
 */
class ToolJobFinalizerRetryableTest {

    // ===== presence-aware: retryable from result → anchor → envelope =====

    @Test
    void oomResultHasRetryableTrue_passesGateAndBuildsEnvelope() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-1"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-1"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, mock(ToolJobResumeService.class), mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        // OOM result with retryable=true
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setError("oom_killed")
                .setStderr("Traceback before OOM")
                .setRetryable(true) // proto3 optional presence
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("OOM_KILLED").build())
                .build();

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-1");
        anchor.setPythonRequestFingerprint("sha256:failed-python");
        anchor.setAutoResume(true);
        anchor.setEstimateJson("{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        // Reservation in RELEASED state so RELEASE step is trivial
        anchor.setReservationJson(buildReleasedJson("run-1", "tc-1", 1, "task-1"));

        finalizer.handleTerminal("run-1", anchor, "FAILED", resp, true);

        // Gate passed: terminalRetryable was set from result
        assertThat(anchor.getTerminalRetryable()).isTrue();
        assertThat(anchor.getTerminalStderrPreview()).isEqualTo("Traceback before OOM");
        assertThat(anchor.getTerminalExitReason()).isEqualTo("OOM_KILLED");
        assertThat(anchor.getPythonFailedRequestFingerprints())
                .containsExactly("sha256:failed-python");
        // Pipeline completed
        verify(anchorService).updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Envelope consumed from anchor, not from status formula (FAILED but OOM → retryable=true)
        verify(usageHook).upsertUsage(eq("run-1"), any(ToolJobAnchor.class));
    }

    @Test
    void successResultHasRetryableFalse_passesGateAndBuildsEnvelope() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-2"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-2"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, mock(ToolJobResumeService.class), mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("SUCCEEDED")
                .setStdout("ok")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("ok").build())
                .build();

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-2:tc-2:1");
        anchor.setToolCallId("tc-2");
        anchor.setAttempt(1);
        anchor.setTaskId("task-2");
        anchor.setAutoResume(true);
        anchor.setEstimateJson("{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(buildReleasedJson("run-2", "tc-2", 1, "task-2"));

        finalizer.handleTerminal("run-2", anchor, "SUCCEEDED", resp, true);

        assertThat(anchor.getTerminalRetryable()).isFalse();
        verify(anchorService).updateAnchorAndStatus(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
    }

    @Test
    void canceledResultHasRetryableFalse_passesGate() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-c"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-c"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-c"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-c"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, mock(ToolJobResumeService.class), mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("CANCELED")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("CANCELED").build())
                .build();

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-c:tc-c:1");
        anchor.setToolCallId("tc-c");
        anchor.setAttempt(1);
        anchor.setTaskId("task-c");
        anchor.setAutoResume(true);
        anchor.setEstimateJson("{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(buildReleasedJson("run-c", "tc-c", 1, "task-c"));

        finalizer.handleTerminal("run-c", anchor, "CANCELED", resp, true);

        // CANCELED → retryable=false, NOT incorrectly true (MF2 fix)
        assertThat(anchor.getTerminalRetryable()).isFalse();
        verify(anchorService).updateAnchorAndStatus(eq("run-c"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
    }

    // ===== fail-closed: missing classification =====

    @Test
    void missingRetryableInResult_failClosedWithDiagnostic() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-fc"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, mock(ToolJobResumeService.class), mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        // Result WITHOUT retryable (no setRetryable call on builder)
        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setError("some_error")
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("EXECUTION_ERROR").build())
                .build();

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-fc:tc-fc:1");
        anchor.setToolCallId("tc-fc");
        anchor.setAttempt(1);
        anchor.setTaskId("task-fc");
        anchor.setAutoResume(true);

        finalizer.handleTerminal("run-fc", anchor, "FAILED", resp, true);

        // terminalRetryable stays null — not silently defaulted to false
        assertThat(anchor.getTerminalRetryable()).isNull();
        // Fail-closed: diagnostic written
        assertThat(anchor.getFinalizerError()).isEqualTo("terminal_retryability_missing");
        // ENVELOPE persisted + fail-closed diagnostic write (2 calls total)
        verify(anchorService, times(2)).updateAnchor(eq("run-fc"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Did NOT proceed to RELEASE
        verify(anchorService, never()).updateAnchorAndStatus(eq("run-fc"), any(ToolJobAnchor.class),
                any(), any());
        // Capacity never touched — no release
        verifyNoInteractions(capacityService);
        // Usage/event hooks never called
        verify(usageHook, never()).upsertUsage(any(), any());
        verify(eventHook, never()).emitTerminalEvent(any(), any());
    }

    // ===== recovery: backfill on refetch after missing =====

    @Test
    void missingThenRefetchWithRetryable_backfillCompletesPipeline() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);

        // Call 1: ENVELOPE succeeds → fail-closed diagnostic write
        // Call 2: backfill write → RELEASE → USAGE → EVENT + CAS/RESUME
        when(anchorService.updateAnchor(eq("run-bf"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true)   // ENVELOPE
                .thenReturn(true)   // fail-closed diagnostic
                .thenReturn(true)   // backfill
                .thenReturn(true)   // RELEASE
                .thenReturn(true)   // USAGE
                .thenReturn(true);  // EVENT
        when(anchorService.updateAnchor(eq("run-bf"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-bf"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-bf"), eq("run-bf:tc-bf:1"), eq("tc-bf"), eq(1), eq("task-bf"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-bf"))
                .thenReturn(buildPersistedReadyAnchor("run-bf", "tc-bf", 1, "task-bf"));
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(usageHook.upsertUsage(eq("run-bf"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-bf"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, redisCache,
                capacityService, resumeService, mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        String reservationJson = buildReleasedJson("run-bf", "tc-bf", 1, "task-bf");
        String estimateJson = "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}";

        // === Call 1: legacy result without retryable → fail-closed ===
        TaskResultResponse legacyResp = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setError("execution_error")
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("EXECUTION_ERROR").build())
                .build(); // no setRetryable

        ToolJobAnchor anchor1 = new ToolJobAnchor();
        anchor1.setOperationId("run-bf:tc-bf:1");
        anchor1.setToolCallId("tc-bf");
        anchor1.setAttempt(1);
        anchor1.setTaskId("task-bf");
        anchor1.setAutoResume(true);

        finalizer.handleTerminal("run-bf", anchor1, "FAILED", legacyResp, true);

        assertThat(anchor1.getTerminalRetryable()).isNull();
        assertThat(anchor1.getFinalizerError()).isEqualTo("terminal_retryability_missing");
        verifyNoInteractions(capacityService);

        // === Call 2: refetch with retryable → backfill → full pipeline ===
        TaskResultResponse refetchedResp = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setError("execution_error")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("EXECUTION_ERROR").build())
                .build();

        // Simulate DB reload: ENVELOPE step is done, terminalRetryable still null
        ToolJobAnchor anchor2 = new ToolJobAnchor();
        anchor2.setOperationId("run-bf:tc-bf:1");
        anchor2.setToolCallId("tc-bf");
        anchor2.setAttempt(1);
        anchor2.setTaskId("task-bf");
        anchor2.setAutoResume(true);
        anchor2.setFinalizerStep("ENVELOPE");
        anchor2.setFinalizerError("terminal_retryability_missing");
        anchor2.setTerminalStatus("FAILED");
        anchor2.setEstimateJson(estimateJson);
        anchor2.setReservationJson(reservationJson);

        finalizer.handleTerminal("run-bf", anchor2, "FAILED", refetchedResp, true);

        // Backfill succeeded: terminalRetryable set, diagnostic cleared
        assertThat(anchor2.getTerminalRetryable()).isFalse();
        assertThat(anchor2.getFinalizerError()).isNull();
        // Pipeline completed through RESUME_READY
        verify(anchorService, atLeast(4)).updateAnchor(eq("run-bf"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(anchorService).updateAnchorAndStatus(eq("run-bf"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(resumeService).tryResume("run-bf");
    }

    // ===== RESULT_LOST synthetic classification =====

    @Test
    void resultLost_setsTerminalRetryableFalse() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-rl"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-rl"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-rl"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-rl"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, mock(ToolJobResumeService.class), mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-rl:tc-rl:1");
        anchor.setToolCallId("tc-rl");
        anchor.setAttempt(1);
        anchor.setTaskId("task-rl");
        anchor.setAutoResume(true);
        anchor.setEstimateJson("{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        // No reservation → RELEASE is no-op
        anchor.setReservationJson(null);

        // RESULT_LOST: resultResp is null, terminalStatus is RESULT_LOST
        finalizer.handleTerminal("run-rl", anchor, "RESULT_LOST", null, true);

        // Synthetic explicit false — not Harness source, not missing
        assertThat(anchor.getTerminalRetryable()).isFalse();
        // Pipeline completed through RESUME_READY
        verify(anchorService).updateAnchorAndStatus(eq("run-rl"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
    }

    // ===== backfill branches =====

    @Test
    void envelopeDoneThenResultLostBackfill_falseContinuesPipeline() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(usageHook.upsertUsage(eq("run-rlb"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-rlb"), any())).thenReturn(true);

        // ENVELOPE already done → backfill writes false → RELEASE/USAGE/EVENT/CAS/READY
        when(anchorService.updateAnchor(eq("run-rlb"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true)   // backfill
                .thenReturn(true)   // RELEASE
                .thenReturn(true)   // USAGE
                .thenReturn(true);  // EVENT
        when(anchorService.updateAnchor(eq("run-rlb"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-rlb"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-rlb"), eq("run-rlb:tc-rlb:1"), eq("tc-rlb"), eq(1), eq("task-rlb"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-rlb"))
                .thenReturn(buildPersistedReadyAnchor("run-rlb", "tc-rlb", 1, "task-rlb"));

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, resumeService, mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-rlb:tc-rlb:1");
        anchor.setToolCallId("tc-rlb");
        anchor.setAttempt(1);
        anchor.setTaskId("task-rlb");
        anchor.setAutoResume(true);
        anchor.setFinalizerStep("ENVELOPE"); // ENVELOPE already persisted
        anchor.setTerminalRetryable(null);   // missing
        anchor.setEstimateJson("{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(buildReleasedJson("run-rlb", "tc-rlb", 1, "task-rlb"));

        // RESULT_LOST: resultResp=null, terminalStatus=RESULT_LOST
        finalizer.handleTerminal("run-rlb", anchor, "RESULT_LOST", null, true);

        // Backfill wrote explicit false
        assertThat(anchor.getTerminalRetryable()).isFalse();
        // Pipeline completed
        verify(anchorService).updateAnchorAndStatus(eq("run-rlb"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(resumeService).tryResume("run-rlb");
    }

    @Test
    void backfillUpdateAnchorFails_doesNotReleaseCapacity() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);

        // backfill updateAnchor FAILS
        when(anchorService.updateAnchor(eq("run-bff"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false); // backfill write fails

        ToolJobFinalizer finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                capacityService, resumeService, mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-bff:tc-bff:1");
        anchor.setToolCallId("tc-bff");
        anchor.setAttempt(1);
        anchor.setTaskId("task-bff");
        anchor.setAutoResume(true);
        anchor.setFinalizerStep("ENVELOPE");
        anchor.setTerminalRetryable(null);

        TaskResultResponse resp = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("OOM_KILLED").build())
                .build();

        finalizer.handleTerminal("run-bff", anchor, "FAILED", resp, true);

        // Backfill attempted but CAS failed → returned without modifying in-memory state
        verify(anchorService, times(1)).updateAnchor(eq("run-bff"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Never proceeded to RELEASE
        verifyNoInteractions(capacityService);
        verify(usageHook, never()).upsertUsage(any(), any());
        verify(eventHook, never()).emitTerminalEvent(any(), any());
        verify(resumeService, never()).tryResume(any());
        verify(anchorService, never()).updateAnchorAndStatus(any(), any(), any(), any());
    }

    @Test
    void knownLegacyEstimateMismatchIsNormalizedAndCapacityReleased() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-legacy"), any(ToolJobAnchor.class),
                any(AgentRunStatus.class))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-legacy"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-legacy"), eq("run-legacy:tc-legacy:1"), eq("tc-legacy"), eq(1),
                eq("task-legacy"), eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-legacy"))
                .thenReturn(buildPersistedReadyAnchor("run-legacy", "tc-legacy", 1, "task-legacy"));
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-legacy"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-legacy"), any())).thenReturn(true);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, mock(ToolJobRedisCache.class),
                capacityService, resumeService, new ToolJobConfig(), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-legacy:tc-legacy:1");
        anchor.setToolCallId("tc-legacy");
        anchor.setAttempt(1);
        anchor.setTaskId("task-legacy");
        anchor.setAutoResume(true);
        anchor.setEstimateJson("{\"estimatedRows\":6000,\"estimatedBytes\":500000,\"fileCount\":2,"
                + "\"selectedColumnRatio\":1.0,\"manifestMemberCount\":0,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"HEAVY\",\"capacityUnits\":3}");
        anchor.setReservationJson(buildReservationJson(
                "run-legacy", "tc-legacy", 1, "task-legacy",
                DataAnalysisReservationState.PENDING_TRANSFERRED));

        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setTaskId("task-legacy")
                .setStatus("SUCCEEDED")
                .setStdout("ok")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setResourceClass("STANDARD")
                        .setCpuMillis(1)
                        .setMemoryPeakBytes(1)
                        .setLogicalBytesScanned(1)
                        .setQueueWaitMillis(1)
                        .setPrepareMillis(1)
                        .setExecutionWallMillis(1)
                        .setCleanupMillis(1)
                        .setDatasetOpenCount(1)
                        .setExitReason("SUCCEEDED")
                        .setAttributionComplete(true)
                        .build())
                .build();

        finalizer.handleTerminal("run-legacy", anchor, "SUCCEEDED", response, true);

        DataAnalysisEstimate corrected = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .readValue(anchor.getEstimateJson(), DataAnalysisEstimate.class);
        assertThat(corrected.resourceClass()).isEqualTo(DataAnalysisResourceClass.STANDARD);
        assertThat(corrected.capacityUnits()).isEqualTo(1);
        verify(capacityService).releaseReservation(argThat(request ->
                request.reservation().resourceClass() == DataAnalysisResourceClass.STANDARD
                        && request.reservation().capacityUnits() == 1));
        verify(resumeService).tryResume("run-legacy");
    }

    // ===== helpers =====

    private static ToolJobAnchor buildPersistedReadyAnchor(String runId, String toolCallId,
                                                            int attempt, String taskId) {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        a.setToolCallId(toolCallId);
        a.setAttempt(attempt);
        a.setTaskId(taskId);
        a.setFinalizerStep("RESUME_READY");
        a.setResumeState("READY");
        a.setResumeToken("token-" + runId);
        a.setResumeLeaseVersion(1);
        a.setResumeClaimedAt(java.time.Instant.now());
        return a;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String buildReleasedJson(String runId, String toolCallId, int attempt,
                                             String taskId) throws Exception {
        return buildReservationJson(runId, toolCallId, attempt, taskId,
                DataAnalysisReservationState.RELEASED);
    }

    private static String buildReservationJson(
            String runId, String toolCallId, int attempt, String taskId,
            DataAnalysisReservationState state) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        var reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                state, taskId, java.time.Instant.now());
        return mapper.writeValueAsString(reservation);
    }
}
