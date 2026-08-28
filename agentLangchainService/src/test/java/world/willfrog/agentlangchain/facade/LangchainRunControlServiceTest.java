package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class LangchainRunControlServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);
    private final LangchainRunControlService service = new LangchainRunControlService(
            readService, runMapper, eventService, stateStore, observabilityService, pipeline,
            creditSettlementService, anchorService, finalizationService);

    @Test
    void cancelFlushesObservabilityAndMarksCanceled() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        AgentRun canceled = run(AgentRunStatus.CANCELED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(canceled);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));
        when(runMapper.updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED),
                anyString(), eq(false), isNull())).thenReturn(1);
        when(runMapper.updateStatusWithTtl(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), any()))
                .thenReturn(1);

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("CANCELED", response.getStatus());
        verify(observabilityService).forceFlush("r1");
        verify(runMapper).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), anyString(), eq(false), isNull());
        verify(eventService).append(eq("r1"), eq("u1"), eq("CANCELED"), anyMap());
        verify(finalizationService).publishFinalizedEvent("r1", "u1", "CANCELED");
    }

    @Test
    void cancelPublisherFailureDoesNotRollbackCommittedTerminalState() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        AgentRun canceled = run(AgentRunStatus.CANCELED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(canceled);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));
        when(runMapper.updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED),
                anyString(), eq(false), isNull())).thenReturn(1);
        when(runMapper.updateStatusWithTtl(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), any()))
                .thenReturn(1);
        doThrow(new RuntimeException("listener unavailable"))
                .when(finalizationService).publishFinalizedEvent("r1", "u1", "CANCELED");

        var response = service.cancelRun(
                CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("CANCELED", response.getStatus());
        verify(runMapper).updateStatusWithTtl(
                eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), any());
        verify(finalizationService).publishFinalizedEvent("r1", "u1", "CANCELED");
    }

    @Test
    void cancelPersistenceMismatchDoesNotPublish() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        AgentRun refreshed = run(AgentRunStatus.EXECUTING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(refreshed);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));
        when(runMapper.updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED),
                anyString(), eq(false), isNull())).thenReturn(0);
        when(runMapper.updateStatusWithTtl(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), any()))
                .thenReturn(1);

        service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        verify(finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void cancelTerminalReentryDoesNotPublishAgain() {
        AgentRun canceled = run(AgentRunStatus.CANCELED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(canceled);

        var response = service.cancelRun(
                CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("CANCELED", response.getStatus());
        verifyNoInteractions(finalizationService);
    }

    @Test
    void resumeRelaunchesPipelineForWaitingRun() {
        AgentRun waiting = run(AgentRunStatus.WAITING);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(received);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));

        var response = service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("RECEIVED", response.getStatus());
        verify(runMapper).resetForResume(eq("r1"), eq("u1"), any());
        verify(pipeline).launchAsync(received);
    }

    private ToolJobAnchor pausedAnchor(String operationId, String terminalStatus, String finalizerStep) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setRunDisposition(world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition.PAUSED);
        anchor.setAutoResume(false);
        anchor.setTerminalStatus(terminalStatus);
        anchor.setFinalizerStep(finalizerStep);
        return anchor;
    }

    @Test
    void resumeRejectsWhilePausedToolJobStillInFlight() {
        // 暂停时长工具还在跑（锚点没有终态字段）：拒绝恢复，不重置状态、不重新派发。
        AgentRun waiting = run(AgentRunStatus.WAITING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(anchorService.loadAnchor("r1")).thenReturn(pausedAnchor("op-1", null, null));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        assertTrue(ex.getMessage().contains("still in flight"));
        verify(runMapper, never()).resetForResume(anyString(), anyString(), any());
        verify(pipeline, never()).launchAsync(any());
    }

    @Test
    void resumeRejectsWhilePausedCleanupIncomplete() {
        // 工具终态已确认但清理链没走完（finalizerStep 未到 EVENT）：拒绝并提示稍后重试。
        AgentRun waiting = run(AgentRunStatus.WAITING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(anchorService.loadAnchor("r1")).thenReturn(pausedAnchor("op-1", "SUCCEEDED", "ENVELOPE"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        assertTrue(ex.getMessage().contains("cleanup is still in progress"));
        verify(runMapper, never()).resetForResume(anyString(), anyString(), any());
        verify(pipeline, never()).launchAsync(any());
    }

    @Test
    void resumeClearsFinalizedPausedAnchorThenRelaunches() {
        // 清理链走完（finalizerStep=EVENT）：先按栅栏清锚点，再走正常恢复流程。
        AgentRun waiting = run(AgentRunStatus.WAITING);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(received);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));
        when(anchorService.loadAnchor("r1")).thenReturn(pausedAnchor("op-1", "SUCCEEDED", "EVENT"));
        when(anchorService.clearPausedAnchor("r1", "op-1")).thenReturn(true);

        var response = service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("RECEIVED", response.getStatus());
        var inOrder = inOrder(anchorService, runMapper, pipeline);
        inOrder.verify(anchorService).clearPausedAnchor("r1", "op-1");
        inOrder.verify(runMapper).resetForResume(eq("r1"), eq("u1"), any());
        inOrder.verify(pipeline).launchAsync(received);
    }

    @Test
    void resumeAbortsWhenPausedAnchorClearLostRace() {
        // 清锚点栅栏没抢到（并发处置已改变状态）：整个恢复失败关闭。
        AgentRun waiting = run(AgentRunStatus.WAITING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(anchorService.loadAnchor("r1")).thenReturn(pausedAnchor("op-1", "SUCCEEDED", "EVENT"));
        when(anchorService.clearPausedAnchor("r1", "op-1")).thenReturn(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        assertTrue(ex.getMessage().contains("resume_anchor_clear_failed"));
        verify(runMapper, never()).resetForResume(anyString(), anyString(), any());
        verify(pipeline, never()).launchAsync(any());
    }

    @Test
    void pauseOnWaitingToolJobPersistsDispositionThenPauses() {
        // 等待长工具的 run 调暂停：先往锚点写暂停处置（autoResume=false + PAUSED），
        // 处置落库后再把状态写成 WAITING——收尾器终态到达时凭 PAUSED 照常走清理链。
        AgentRun waitingToolJob = run(AgentRunStatus.WAITING_TOOL_JOB);
        AgentRun paused = run(AgentRunStatus.WAITING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waitingToolJob);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(paused);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.WAITING))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("op-1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.persistPauseDisposition("r1", "op-1", AgentRunStatus.WAITING_TOOL_JOB))
                .thenReturn(true);

        service.pauseRun(PauseAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        var inOrder = inOrder(anchorService, runMapper);
        inOrder.verify(anchorService).persistPauseDisposition("r1", "op-1", AgentRunStatus.WAITING_TOOL_JOB);
        inOrder.verify(runMapper).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.WAITING), anyString(), eq(false), any());
        verify(runMapper).updateStatusWithTtl(eq("r1"), eq("u1"), eq(AgentRunStatus.WAITING), any());
        verify(stateStore).markRunStatus("r1", AgentRunStatus.WAITING.name());
    }

    @Test
    void pauseOnWaitingToolJobAbortsWhenDispositionRejected() {
        // 处置写不进去（任务已被替换，或取消/检查点失败已先落处置）：
        // 整个暂停失败关闭，状态/事件/Redis 一律不动。
        AgentRun waitingToolJob = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waitingToolJob);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("op-1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.persistPauseDisposition(anyString(), anyString(), any()))
                .thenReturn(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.pauseRun(PauseAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        assertTrue(ex.getMessage().contains("pause_anchor_disposition_failed"));
        verify(runMapper, never()).updateSnapshot(anyString(), anyString(), any(), anyString(), anyBoolean(), any());
        verify(runMapper, never()).updateStatusWithTtl(anyString(), anyString(), any(), any());
        verify(stateStore, never()).markRunStatus(anyString(), anyString());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void cancelWithActiveAnchorReturnsCanceledStatusEvenThoughDbStaysWaiting() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        AgentRun refreshed = run(AgentRunStatus.WAITING_TOOL_JOB); // DB still WAITING
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(refreshed);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));

        // Anchor exists and the narrow cancel write succeeds
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        // Oracle: API response shows CANCELED even though DB is WAITING_TOOL_JOB
        assertEquals("CANCELED", response.getStatus());
        // Oracle: cancel disposition persisted via the narrow jsonb merge (260818 grace round-4)
        verify(anchorService).persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(anchorService, never()).updateAnchor(anyString(), any(), any());
        // Oracle: snapshot updated with current status (not CANCELED)
        verify(runMapper).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.WAITING_TOOL_JOB),
                anyString(), eq(false), isNull());
        verify(finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void cancelThrowsWhenAnchorCasFails() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        // Anchor exists but the narrow cancel CAS returns false; the re-read shows the
        // SAME operationId (status raced), so there is no second attempt and we fail closed.
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false);

        // Oracle: throws to prevent capacity leak (fail-closed)
        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        // Oracle: no DB/Redis mutations, no event, no settlement
        verify(stateStore, never()).markRunStatus(eq("r1"), anyString());
        verify(runMapper, never()).updateSnapshot(anyString(), anyString(), any(), anyString(), anyBoolean(), any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
        verify(creditSettlementService, never()).settleAsync(anyString(), anyString());
    }

    @Test
    void cancelThrowsWhenLoadAnchorThrows() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(anchorService.loadAnchor("r1")).thenThrow(new RuntimeException("DB connection lost"));

        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        // Oracle: no side effects on failure
        verify(stateStore, never()).markRunStatus(eq("r1"), anyString());
        verify(runMapper, never()).updateSnapshot(anyString(), anyString(), any(), anyString(), anyBoolean(), any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
        verify(creditSettlementService, never()).settleAsync(anyString(), anyString());
    }

    @Test
    void cancelThrowsWhenUpdateAnchorThrows() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenThrow(new RuntimeException("PG write failure"));

        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        // Oracle: no side effects on failure
        verify(stateStore, never()).markRunStatus(eq("r1"), anyString());
        verify(runMapper, never()).updateSnapshot(anyString(), anyString(), any(), anyString(), anyBoolean(), any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
        verify(creditSettlementService, never()).settleAsync(anyString(), anyString());
    }

    @Test
    void successfulRetryAfterCasFailureStillAchievesDispositionAndRelease() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);

        // First call: narrow CAS fails
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false);
        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        // Second call (client retry): narrow CAS succeeds
        AgentRun refreshed = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(refreshed);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        // Oracle: retry succeeds, disposition persisted, API shows CANCELED
        assertEquals("CANCELED", response.getStatus());
        verify(anchorService, times(2)).persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB));
    }

    // ---- 260818 (grace round-4): 取消写入与并发第二工具/取消失败的对称交错 ----

    @Test
    void cancelReReadsCurrentOperationAndRetriesWhenAnchorWasReplaced() {
        // 取消线程读到第一个 operationId，窄写输给并发开始的第二个长工具（operationId 已变），
        // 重读当前锚点后用新 operationId 重试成功——取消意图跟随当前任务而不是旧快照。
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        AgentRun refreshed = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(refreshed);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));

        ToolJobAnchor first = new ToolJobAnchor();
        first.setOperationId("r1:tc-1:1");
        ToolJobAnchor second = new ToolJobAnchor();
        second.setOperationId("r1:tc-2:1");
        when(anchorService.loadAnchor("r1")).thenReturn(first, second);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-1:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false);
        when(anchorService.persistCancelDisposition(
                eq("r1"), eq("r1:tc-2:1"), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("CANCELED", response.getStatus());
        verify(anchorService).persistCancelDisposition(
                eq("r1"), eq("r1:tc-2:1"), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(anchorService, never()).updateAnchor(anyString(), any(), any());
    }

    @Test
    void cancelFailsClosedWhenReplacedOperationAlsoLoses() {
        // 重读后的新 operationId 二次窄写仍输（任务又被替换或状态再变）：
        // 按既有语义失败关闭，不写 Redis CANCELING/CANCELED、不发事件、不结算。
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        ToolJobAnchor first = new ToolJobAnchor();
        first.setOperationId("r1:tc-1:1");
        ToolJobAnchor second = new ToolJobAnchor();
        second.setOperationId("r1:tc-2:1");
        when(anchorService.loadAnchor("r1")).thenReturn(first, second);
        when(anchorService.persistCancelDisposition(
                eq("r1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));

        verify(stateStore, never()).markRunStatus(eq("r1"), anyString());
        verify(runMapper, never()).updateSnapshot(anyString(), anyString(), any(), anyString(), anyBoolean(), any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
        verify(creditSettlementService, never()).settleAsync(anyString(), anyString());
        // 恰好两次窄写尝试（旧 operationId 一次 + 重读后的新 operationId 一次），没有整份写回
        verify(anchorService, times(2)).persistCancelDisposition(
                eq("r1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(anchorService, never()).updateAnchor(anyString(), any(), any());
    }

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(status);
        run.setSnapshotJson("{}");
        return run;
    }
}
