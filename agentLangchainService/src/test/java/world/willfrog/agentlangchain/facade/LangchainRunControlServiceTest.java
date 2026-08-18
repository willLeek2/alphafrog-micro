package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LangchainRunControlServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentEventService eventService = mock(AgentEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
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
