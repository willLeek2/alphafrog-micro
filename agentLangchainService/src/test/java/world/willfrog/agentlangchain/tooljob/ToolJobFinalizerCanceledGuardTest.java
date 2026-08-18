package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the CANCELED branch isStepDone guard added to prevent the 5-second
 * hot-loop when an already-finalized CANCELED run is re-entered.
 *
 * <p>260818: the terminal CAS now goes through {@code cancelFromStatuses},
 * which accepts both WAITING_TOOL_JOB (cancel during background wait) and
 * EXECUTING (cancel landing after markHandoffAccepted resumed execution —
 * batch 20260818-182948's permanent-EXECUTING + dual retry loop root cause).
 * The status-pair semantics live in the SQL and are pinned by
 * {@code ToolJobAnchorMapperIntegrationTest}; this class pins the wiring.
 */
class ToolJobFinalizerCanceledGuardTest {

    @Test
    void firstCanceledEntryPerformsCasAndClearsRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);

        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-1"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-1"), any())).thenReturn(true);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);
        AgentRun canceledRun = new AgentRun();
        canceledRun.setId("run-1");
        canceledRun.setUserId("7");
        canceledRun.setStatus(AgentRunStatus.CANCELED);
        when(runMapper.findById("run-1")).thenReturn(canceledRun);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class),
                runMapper, finalizationService);
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        // finalizerStep is null → isStepDone returns false → first entry
        anchor.setFinalizerStep(null);

        finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", null, false);

        // Must have performed the cancel CAS (WAITING_TOOL_JOB or EXECUTING accepted)
        verify(anchorService).cancelFromStatuses(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED));
        verify(finalizationService).publishFinalizedEvent("run-1", "7", "CANCELED");
        // Must have cleared Redis after successful CAS
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    @Test
    void reentryWhenAlreadyCanceledSkipsCasAndStillClearsRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        // cancelFromStatuses should NOT be called in reentry — but mock it anyway
        when(anchorService.cancelFromStatuses(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);

        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-2"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-2"), any())).thenReturn(true);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-2:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        // finalizerStep is already CANCELED → isStepDone returns true → reentry
        anchor.setFinalizerStep("CANCELED");

        finalizer.handleTerminal("run-2", anchor, "SUCCEEDED", null, false);

        // Must NOT call the cancel CAS on reentry
        verify(anchorService, never()).cancelFromStatuses(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED));
        verifyNoInteractions(runMapper, finalizationService);
        // Must still clear Redis
        verify(redisCache).removeDue("run-2");
        verify(redisCache).deletePendingCache("run-2");
    }

    @Test
    void publisherFailureAfterCanceledCasDoesNotBlockRedisCleanup() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-3"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-3"), any())).thenReturn(true);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRun canceledRun = new AgentRun();
        canceledRun.setId("run-3");
        canceledRun.setUserId("7");
        when(runMapper.findById("run-3")).thenReturn(canceledRun);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);
        doThrow(new RuntimeException("listener unavailable"))
                .when(finalizationService).publishFinalizedEvent("run-3", "7", "CANCELED");

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-3:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);

        finalizer.handleTerminal("run-3", anchor, "SUCCEEDED", null, false);

        verify(anchorService).cancelFromStatuses(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED));
        verify(redisCache).removeDue("run-3");
        verify(redisCache).deletePendingCache("run-3");
    }

    @Test
    void canceledCasFailureDoesNotPublishOrClearRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(false);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-4"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-4"), any())).thenReturn(true);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-4:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);

        finalizer.handleTerminal("run-4", anchor, "SUCCEEDED", null, false);

        verifyNoInteractions(runMapper, finalizationService);
        // 260819：残留清理 CAS 未胜出（mock 默认 false）时维持既有重试语义，不清 Redis。
        // 步骤门控为真：finalizerStep=null 的锚点在本轮补跑完 RELEASE/USAGE/EVENT 并逐条
        // 持久化成功（生产中每步都是真实 CAS）后，允许尝试残留清理是安全语义。
        verify(redisCache, never()).removeDue("run-4");
        verify(redisCache, never()).deletePendingCache("run-4");
    }

    // ---- 260819: 终态 Run 残留取消锚点的兜底收口（e572 告警循环） ----

    @Test
    void residualCanceledAnchorClosedWhenRunAlreadyTerminal() throws Exception {
        // e572 签名：ENVELOPE..RESUME_READY 全部完成、正常取消 CAS 因 status=FAILED 永远 0 行。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.cancelFromStatuses(eq("run-5"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(false);
        when(anchorService.closeResidualCanceledAnchor("run-5", "run-5:call-1:1"))
                .thenReturn(true);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-5:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep("RESUME_READY");

        finalizer.handleTerminal("run-5", anchor, "SUCCEEDED", null, false);

        verify(anchorService).closeResidualCanceledAnchor("run-5", "run-5:call-1:1");
        // 已终态 Run 不重发 workspace 终态事件、不记调度指标，只清 Redis 索引。
        verifyNoInteractions(finalizationService);
        verify(redisCache).removeDue("run-5");
        verify(redisCache).deletePendingCache("run-5");
    }

    @Test
    void residualCloseFailureKeepsBoundedRetry() throws Exception {
        // 残留清理 CAS 输掉（如 operationId 已漂移）时维持既有重试语义，不清 Redis。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.cancelFromStatuses(eq("run-6"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(false);
        when(anchorService.closeResidualCanceledAnchor("run-6", "run-6:call-1:1"))
                .thenReturn(false);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-6:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep("RESUME_READY");

        finalizer.handleTerminal("run-6", anchor, "SUCCEEDED", null, false);

        verify(anchorService).closeResidualCanceledAnchor("run-6", "run-6:call-1:1");
        verify(redisCache, never()).removeDue("run-6");
        verify(redisCache, never()).deletePendingCache("run-6");
    }

    @Test
    void residualCloseNotAttemptedWhenCancelCasSucceeds() throws Exception {
        // 正常路径（WAITING_TOOL_JOB/EXECUTING）不受兜底影响：CAS 成功即完整收口。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.cancelFromStatuses(eq("run-7"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRun canceledRun = new AgentRun();
        canceledRun.setId("run-7");
        canceledRun.setUserId("7");
        canceledRun.setStatus(AgentRunStatus.CANCELED);
        when(runMapper.findById("run-7")).thenReturn(canceledRun);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper, finalizationService);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-7:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep("RESUME_READY");

        finalizer.handleTerminal("run-7", anchor, "SUCCEEDED", null, false);

        verify(anchorService, never()).closeResidualCanceledAnchor(any(), any());
        verify(finalizationService).publishFinalizedEvent("run-7", "7", "CANCELED");
        verify(redisCache).removeDue("run-7");
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
