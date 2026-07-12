package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobFinalizerCheckpointFailureTest {

    @Test
    void checkpointFailureReleasesAndEndsFailedInsteadOfResuming() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.FAILED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(usageHook.upsertUsage(eq("run-1"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-1"), any())).thenReturn(true);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, mock(ToolJobRedisCache.class),
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CHECKPOINT_FAILED");

        finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", null, false);

        verify(anchorService).updateAnchorAndStatus("run-1", anchor,
                AgentRunStatus.FAILED, AgentRunStatus.WAITING_TOOL_JOB);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
