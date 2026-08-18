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
    void firstCanceledEntryPerformsCasClearsAnchorAndRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.cancelFromStatuses(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);
        when(anchorService.closeResidualCanceledAnchor("run-1", "run-1:call-1:1"))
                .thenReturn(true);

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
        // 260819 must-fix-2：CAS 成功后锚点本身也清成 '{}'
        verify(anchorService).closeResidualCanceledAnchor("run-1", "run-1:call-1:1");
        verify(finalizationService).publishFinalizedEvent("run-1", "7", "CANCELED");
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    @Test
    void reentryWhenAlreadyCanceledClearsAnchorAndRedisWithoutCas() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.closeResidualCanceledAnchor("run-2", "run-2:call-1:1"))
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
        // 260819 must-fix-2：重入路径也要清掉终态锚点，而不是只删 Redis
        verify(anchorService).closeResidualCanceledAnchor("run-2", "run-2:call-1:1");
        verifyNoInteractions(runMapper, finalizationService);
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
        when(anchorService.closeResidualCanceledAnchor("run-3", "run-3:call-1:1"))
                .thenReturn(true);
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
        // 260819：残留清理 CAS 未胜出（mock 默认 false，SQL 栅栏拒绝）时维持既有重试语义，
        // 不清 Redis——唯一重试入口（due）保留。
        verify(anchorService).closeResidualCanceledAnchor("run-4", "run-4:call-1:1");
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
    void postCasAnchorClearFailureKeepsDueForRetry() throws Exception {
        // 260819 must-fix-2：CAS 已把 Run 收口成 CANCELED，但锚点清理输掉（如瞬时 DB 故障）
        // 时保留 due——重入路径（finalizerStep=CANCELED）会重试清理。事件在清理前已发布
        // 且重入路径不重发，不会重复。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.cancelFromStatuses(eq("run-7"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED))).thenReturn(true);
        when(anchorService.closeResidualCanceledAnchor("run-7", "run-7:call-1:1"))
                .thenReturn(false);
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

        verify(anchorService).closeResidualCanceledAnchor("run-7", "run-7:call-1:1");
        verify(finalizationService).publishFinalizedEvent("run-7", "7", "CANCELED");
        verify(redisCache, never()).removeDue("run-7");
        verify(redisCache, never()).deletePendingCache("run-7");
    }

    @Test
    void reentryAnchorClearFailureKeepsDueForRetry() throws Exception {
        // 重入路径清理输掉：保留 due 供下一轮重试，不提前删除唯一重试入口。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.closeResidualCanceledAnchor("run-8", "run-8:call-1:1"))
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
        anchor.setOperationId("run-8:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep("CANCELED");

        finalizer.handleTerminal("run-8", anchor, "SUCCEEDED", null, false);

        verify(anchorService).closeResidualCanceledAnchor("run-8", "run-8:call-1:1");
        verify(anchorService, never()).cancelFromStatuses(any(), any(), any());
        verify(redisCache, never()).removeDue("run-8");
        verify(redisCache, never()).deletePendingCache("run-8");
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
