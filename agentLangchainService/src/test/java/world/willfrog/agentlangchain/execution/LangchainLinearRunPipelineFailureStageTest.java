package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.deployment.AgentServiceShutdownState;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * 失败两条路径的对外表达（行为项）：意外异常被安全网接住时，失败事件带阶段名
 * （失败点不被大 try 抹平）与失败四分类（业务拒绝 / 资源信号 / 控制流 / 未知缺陷）。
 * 预期失败不走异常通道，仍由各阶段的结果对象表达。
 */
class LangchainLinearRunPipelineFailureStageTest {

    @Test
    void lostTerminalWriteDoesNotPublishFailureSideEffects() {
        AgentRun run = new AgentRun();
        run.setId("run-terminal-race");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-terminal-race")).thenReturn(run);
        when(runMapper.updateStatus("run-terminal-race", "user-1", AgentRunStatus.EXECUTING))
                .thenReturn(1);
        when(runMapper.updateTerminalSnapshot(anyString(), anyString(), any(), anyString(), eq(true), any()))
                .thenReturn(0);

        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-terminal-race", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests("{}")).thenReturn(false);
        when(eventService.extractRunConfig("{}"))
                .thenReturn(AgentRunEventService.RunConfig.defaults());
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(
                new LangchainRunStageModelResolver.StageModels(
                        null, null, null, "openrouter", "model", List.of()));
        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenThrow(new RuntimeException("planner exploded"));
        AgentCreditService creditService = mock(AgentCreditService.class);
        when(creditService.hasPositiveCredit("user-1")).thenReturn(true);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, mock(LangchainLinearWorkflowExecutor.class),
                mock(LangchainDagWorkflowExecutor.class), stageModelResolver, runMapper,
                eventService, new ObjectMapper(), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), new LangchainFailureMapper(),
                followUpContextSupport, mock(AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class), immediateScheduler(), creditService,
                mock(AgentRunCreditSettlementService.class), finalizationService,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));

        pipeline.executeRun(run);

        verify(finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
        verify(eventService, never()).append(
                anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
    }

    @Test
    void ordinaryShutdownDoesNotTurnInterruptedRunIntoBusinessFailure() {
        AgentRun run = new AgentRun();
        run.setId("run-shutdown");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-shutdown")).thenReturn(run);
        when(runMapper.updateStatus("run-shutdown", "user-1", AgentRunStatus.EXECUTING))
                .thenReturn(1);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-shutdown", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests("{}")).thenReturn(false);
        when(eventService.extractRunConfig("{}"))
                .thenReturn(AgentRunEventService.RunConfig.defaults());
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(
                new LangchainRunStageModelResolver.StageModels(
                        null, null, null, "openrouter", "model", List.of()));
        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                .build());
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        when(linear.executePlanned(any(), any())).thenReturn(LangchainWorkflowResult.builder()
                .success(false)
                .failureReason("interrupted while closing")
                .toolCallsUsed(0)
                .build());
        AgentCreditService creditService = mock(AgentCreditService.class);
        when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear,
                mock(LangchainDagWorkflowExecutor.class), stageModelResolver, runMapper,
                eventService, new ObjectMapper(), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), new LangchainFailureMapper(),
                followUpContextSupport, mock(AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class), immediateScheduler(), creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        AgentServiceShutdownState shutdownState = mock(AgentServiceShutdownState.class);
        when(shutdownState.isShuttingDown()).thenReturn(true);
        ReflectionTestUtils.setField(pipeline, "shutdownState", shutdownState);

        pipeline.executeRun(run);

        verify(runMapper, never()).updateTerminalSnapshot(
                anyString(), anyString(), any(), anyString(), eq(true), any());
        verify(eventService, never()).append(
                anyString(), anyString(), eq("WORKFLOW_FAILED"), any());
    }

    @Test
    void unexpectedExceptionCarriesStageAndFailureClassIntoFailureEvent() {
        AgentRun run = new AgentRun();
        run.setId("run-fs-1");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-fs-1")).thenReturn(run);
        when(runMapper.updateStatus("run-fs-1", "user-1", AgentRunStatus.EXECUTING))
                .thenReturn(1);
        when(runMapper.updateTerminalSnapshot(anyString(), anyString(), any(), anyString(), eq(true), any()))
                .thenReturn(1);

        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-fs-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());

        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));

        // 规划器在计划阶段抛出意外异常：安全网应把 stage=resolve_plan 带进失败事件。
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenThrow(new RuntimeException("planner exploded"));

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        AgentMessageService messageService = mock(AgentMessageService.class);
        when(messageService.buildMetaJson(any(), any(), any(), any())).thenReturn("{}");

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                mock(LangchainLinearWorkflowExecutor.class),
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
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );

        pipeline.executeRun(run);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-fs-1"), eq("user-1"),
                eq("WORKFLOW_FAILED"), payloadCaptor.capture());
        Map<String, Object> captured = payloadCaptor.getValue();
        assertThat(captured).containsEntry("stage", "resolve_plan");
        assertThat(captured).containsKey("failure_class");
        assertThat(captured.get("failure_class")).isInstanceOf(String.class);
        assertThat((String) captured.get("failure_class")).isNotEmpty();
    }

    @Test
    void expectedExecutionFailureCarriesExecutionStageIntoFailureEvent() {
        AgentRun run = new AgentRun();
        run.setId("run-fs-2");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-fs-2")).thenReturn(run);
        when(runMapper.updateStatus("run-fs-2", "user-1", AgentRunStatus.EXECUTING))
                .thenReturn(1);
        when(runMapper.updateTerminalSnapshot(anyString(), anyString(), any(), anyString(), eq(true), any()))
                .thenReturn(1);

        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-fs-2", "user-1")).thenReturn(true);
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
        when(linear.executePlanned(any(), any())).thenReturn(LangchainWorkflowResult.builder()
                .success(false)
                .failureReason("tool_execution_failed:some_tool")
                .toolCallsUsed(1)
                .build());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

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
                mock(AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );

        pipeline.executeRun(run);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-fs-2"), eq("user-1"),
                eq("WORKFLOW_FAILED"), payloadCaptor.capture());
        Map<String, Object> captured = payloadCaptor.getValue();
        // 预期失败不走异常通道，阶段名标到失败来源的执行阶段。
        assertThat(captured).containsEntry("stage", "execute_workflow");
        assertThat(captured).containsKey("failure_class");
        assertThat(captured.get("failure_class")).isInstanceOf(String.class);
        assertThat((String) captured.get("failure_class")).isNotEmpty();
    }
}
