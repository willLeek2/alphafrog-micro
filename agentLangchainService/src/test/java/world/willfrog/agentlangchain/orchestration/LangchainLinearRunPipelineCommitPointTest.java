package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * 提交点语义（T1-6）：终态快照成功写入数据库（带状态栅栏的终态写入返回 1）即本次 Run 的提交点，
 * 之后的收尾动作（事件、assistant 消息、结算、终态广播）降级为尽力而为，失败只告警，
 * 不再向外传播——外层失败出口不允许把已提交的 COMPLETED/PARTIAL 改写成 WORKFLOW_FAILED。
 *
 * <p>覆盖：COMPLETED/PARTIAL 两条正常轨的副作用失败不改写终态、末位副作用失败被吞、
 * 快乐路径副作用序列不变、数据库未接受终态写入时不广播任何终态。</p>
 */
class LangchainLinearRunPipelineCommitPointTest {

    @Test
    void completedSideEffectFailureDoesNotRewriteCommittedOutcome() {
        Fixture fx = fixture("run-cp-1", true, 1);
        doThrow(new RuntimeException("event append boom"))
                .when(fx.eventService).append(eq("run-cp-1"), eq("user-1"), eq("WORKFLOW_COMPLETED"), any());

        fx.pipeline.executeRun(fx.run);

        verify(fx.runMapper).updateTerminalSnapshot(eq("run-cp-1"), eq("user-1"),
                eq(AgentRunStatus.COMPLETED), anyString(), eq(true), isNull());
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
        verify(fx.messageService, never()).createAssistantMessage(anyString(), anyString(), anyString());
        verify(fx.creditSettlementService, never()).settleAsync(anyString(), anyString());
        verify(fx.finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void partialSideEffectFailureDoesNotRewriteCommittedOutcome() {
        Fixture fx = partialFixture("run-cp-2", 1);
        doThrow(new RuntimeException("event append boom"))
                .when(fx.eventService).append(eq("run-cp-2"), eq("user-1"), eq("WORKFLOW_PARTIAL_COMPLETED"), any());

        fx.pipeline.executeRun(fx.run);

        verify(fx.runMapper).updateTerminalSnapshot(eq("run-cp-2"), eq("user-1"),
                eq(AgentRunStatus.PARTIAL), anyString(), eq(true), any());
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
        verify(fx.messageService, never()).createAssistantMessage(anyString(), anyString(), anyString());
        verify(fx.creditSettlementService, never()).settleAsync(anyString(), anyString());
        verify(fx.finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void finalizationFailureAfterCommitIsSwallowed() {
        Fixture fx = fixture("run-cp-3", true, 1);
        doThrow(new RuntimeException("finalization boom"))
                .when(fx.finalizationService).publishFinalizedEvent(eq("run-cp-3"), eq("user-1"), eq("COMPLETED"));

        fx.pipeline.executeRun(fx.run);

        verify(fx.eventService).append(eq("run-cp-3"), eq("user-1"), eq("WORKFLOW_COMPLETED"), any());
        verify(fx.messageService).createAssistantMessage(eq("run-cp-3"), eq("done-answer"), anyString());
        verify(fx.creditSettlementService).settleAsync(eq("run-cp-3"), eq("user-1"));
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
    }

    @Test
    void completedHappyPathSideEffectSequenceUnchanged() {
        Fixture fx = fixture("run-cp-4", true, 1);

        fx.pipeline.executeRun(fx.run);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fx.eventService).append(eq("run-cp-4"), eq("user-1"),
                eq("WORKFLOW_COMPLETED"), payloadCaptor.capture());
        Map<String, Object> captured = payloadCaptor.getValue();
        assertThat(captured).containsEntry("answer", "done-answer");
        assertThat(captured).containsEntry("toolCallsUsed", 2);
        assertThat(captured).containsEntry("engine", "agentLangchainService");
        assertThat(captured).doesNotContainKey("resumed");
        verify(fx.messageService).createAssistantMessage(eq("run-cp-4"), eq("done-answer"), anyString());
        verify(fx.creditSettlementService).settleAsync(eq("run-cp-4"), eq("user-1"));
        verify(fx.finalizationService).publishFinalizedEvent(eq("run-cp-4"), eq("user-1"), eq("COMPLETED"));
    }

    @Test
    void unacceptedSnapshotWriteSkipsAllTerminalBroadcast() {
        Fixture fx = fixture("run-cp-5", true, 0);

        fx.pipeline.executeRun(fx.run);

        verify(fx.runMapper).updateTerminalSnapshot(eq("run-cp-5"), eq("user-1"),
                eq(AgentRunStatus.COMPLETED), anyString(), eq(true), isNull());
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_COMPLETED"), any());
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
        verify(fx.eventService, never()).append(anyString(), anyString(), eq("MESSAGE_COMPLETED"), any());
        verify(fx.messageService, never()).createAssistantMessage(anyString(), anyString(), anyString());
        verify(fx.creditSettlementService, never()).settleAsync(anyString(), anyString());
        verify(fx.finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    // ========== 构造辅助 ==========

    private static final class Fixture {
        final AgentRun run;
        final LangchainLinearRunPipelineImpl pipeline;
        final AgentRunMapper runMapper;
        final AgentRunEventService eventService;
        final AgentMessageService messageService;
        final AgentRunCreditSettlementService creditSettlementService;
        final AgentRunFinalizationService finalizationService;

        Fixture(AgentRun run, LangchainLinearRunPipelineImpl pipeline, AgentRunMapper runMapper,
                AgentRunEventService eventService, AgentMessageService messageService,
                AgentRunCreditSettlementService creditSettlementService,
                AgentRunFinalizationService finalizationService) {
            this.run = run;
            this.pipeline = pipeline;
            this.runMapper = runMapper;
            this.eventService = eventService;
            this.messageService = messageService;
            this.creditSettlementService = creditSettlementService;
            this.finalizationService = finalizationService;
        }
    }

    private static Fixture fixture(String runId, boolean success, int snapshotRows) {
        LangchainLinearWorkflowResult result = LangchainLinearWorkflowResult.builder()
                .success(success)
                .finalAnswer("done-answer")
                .toolCallsUsed(2)
                .plan(LangchainTodoPlan.builder()
                        .executionMode(PlanExecutionMode.LINEAR)
                        .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                        .build())
                .build();
        return buildFixture(runId, result, snapshotRows);
    }

    private static Fixture partialFixture(String runId, int snapshotRows) {
        LangchainLinearWorkflowResult result = LangchainLinearWorkflowResult.builder()
                .partial(true)
                .finalAnswer("partial-answer")
                .failureReason("some_skip_reason")
                .toolCallsUsed(1)
                .plan(LangchainTodoPlan.builder()
                        .executionMode(PlanExecutionMode.LINEAR)
                        .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                        .build())
                .build();
        return buildFixture(runId, result, snapshotRows);
    }

    private static Fixture buildFixture(String runId, LangchainLinearWorkflowResult result, int snapshotRows) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById(runId)).thenReturn(run);
        when(runMapper.updateTerminalSnapshot(eq(runId), eq("user-1"), any(), anyString(), eq(true), any()))
                .thenReturn(snapshotRows);

        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable(runId, "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());

        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                .build());

        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        when(linear.executePlanned(any(), any())).thenReturn(result);

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        AgentMessageService messageService = mock(AgentMessageService.class);
        lenient().when(messageService.buildMetaJson(any(), any(), any(), any())).thenReturn("{}");
        AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linear,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                followUpContextSupport,
                messageService,
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                creditService,
                creditSettlementService,
                finalizationService,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );
        return new Fixture(run, pipeline, runMapper, eventService, messageService,
                creditSettlementService, finalizationService);
    }
}
