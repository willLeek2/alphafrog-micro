package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 暂停处置（runDisposition=PAUSED）的收尾行为：长工具终态到达后，清理链
 * （envelope/release/usage/event）照常走完，但不把 Run CAS 回 RECEIVED、不生成恢复租约、
 * 不触发续接——Run 停在 WAITING 等用户手动恢复。
 *
 * <p>「WAITING + PAUSED 锚点等价于等待中」的 SQL 匹配语义由
 * {@code ToolJobAnchorMapperIntegrationTest} 在测试环境验证；本类验证的是接线：
 * finalizer 以 WAITING_TOOL_JOB 为条件发起条件更新，以及 PAUSED 分支不拉起恢复。
 */
class ToolJobFinalizerPausedGuardTest {

    @Test
    void pausedRunCompletesCleanupChainWithoutResume() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);

        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq("run-1"), any())).thenReturn(true);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(eq("run-1"), any())).thenReturn(true);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache,
                mock(DataAnalysisCapacityService.class), resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), runMapper,
                mock(AgentRunFinalizationService.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition(ToolJobRunDisposition.PAUSED);
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep(null);

        ToolJobFinalizer.FinalizerOutcome outcome =
                finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", null, false);

        // 清理链走完 = 做完；步骤推进仍以 WAITING_TOOL_JOB 为条件（SQL 负责同时认 WAITING+PAUSED）
        assertTrue(outcome.done());
        verify(anchorService, atLeastOnce()).updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        // 不把 Run 拉回执行：不推进 RECEIVED、不生成恢复租约、不触发续接
        verify(anchorService, never()).updateAnchorAndStatus(eq("run-1"), any(),
                eq(AgentRunStatus.RECEIVED), any());
        verify(resumeService, never()).tryResume(any());
        assertTrue(anchor.isTerminalEventEmitted());
    }

    @Test
    void pausedRunWithFailedCasReportsIncomplete() throws Exception {
        // 条件更新没抢到所有权（并发处置已推进）：显式返回没做完，调用方保留下轮重试。
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(false);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, mock(ToolJobRedisCache.class),
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class), mock(AgentRunMapper.class),
                mock(AgentRunFinalizationService.class));

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAutoResume(false);
        anchor.setRunDisposition(ToolJobRunDisposition.PAUSED);
        anchor.setTerminalRetryable(false);
        anchor.setFinalizerStep(null);

        ToolJobFinalizer.FinalizerOutcome outcome =
                finalizer.handleTerminal("run-1", anchor, "SUCCEEDED", null, false);

        assertFalse(outcome.done());
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
