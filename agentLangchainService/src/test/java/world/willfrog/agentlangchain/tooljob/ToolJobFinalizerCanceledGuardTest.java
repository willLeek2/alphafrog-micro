package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

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

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class));
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
                mock(ToolJobConfig.class));
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

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
