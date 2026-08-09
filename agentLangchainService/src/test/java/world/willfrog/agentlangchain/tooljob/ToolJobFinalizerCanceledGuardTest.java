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
 */
class ToolJobFinalizerCanceledGuardTest {

    @Test
    void firstCanceledEntryPerformsCasAndClearsRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

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
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        // finalizerStep is null → isStepDone returns false → first entry
        anchor.setFinalizerStep(null);

        finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", null, false);

        // Must have performed the CAS to CANCELED
        verify(anchorService).updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB));
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
        // updateAnchorAndStatus should NOT be called in reentry — but mock it anyway
        when(anchorService.updateAnchorAndStatus(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-2"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-2"), any())).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);
        // finalizerStep is already CANCELED → isStepDone returns true → reentry
        anchor.setFinalizerStep("CANCELED");

        finalizer.handleTerminal("run-2", anchor, "SUCCEEDED", null, false);

        // Must NOT call updateAnchorAndStatus on reentry
        verify(anchorService, never()).updateAnchorAndStatus(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Must still clear Redis
        verify(redisCache).removeDue("run-2");
        verify(redisCache).deletePendingCache("run-2");
    }

    @Test
    void publisherFailureAfterCanceledCasDoesNotBlockRedisCleanup() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
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
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);

        finalizer.handleTerminal("run-3", anchor, "SUCCEEDED", null, false);

        verify(anchorService).updateAnchorAndStatus(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(redisCache).removeDue("run-3");
        verify(redisCache).deletePendingCache("run-3");
    }

    @Test
    void canceledCasFailureDoesNotPublishOrClearRedis() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.CANCELED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(false);
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
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setTerminalRetryable(false);

        finalizer.handleTerminal("run-4", anchor, "SUCCEEDED", null, false);

        verifyNoInteractions(runMapper, finalizationService);
        verify(redisCache, never()).removeDue("run-4");
        verify(redisCache, never()).deletePendingCache("run-4");
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
