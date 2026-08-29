package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 服务重启必须复用冻结计划和 LINEAR checkpoint，不能再次调用 planner。 */
class LangchainLinearRunPipelineWorkflowRestartTest {

    @Test
    @SuppressWarnings("unchecked")
    void restartedLinearRunSkipsPlannerAndContinuesFromCheckpoint() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainDagWorkflowExecutor dag = mock(LangchainDagWorkflowExecutor.class);
        LangchainRunStageModelResolver models = mock(LangchainRunStageModelResolver.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        WorkflowCheckpointService checkpoints = mock(WorkflowCheckpointService.class);

        LangchainTodoPlan frozenPlan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("todo-1").sequence(1).description("first").build(),
                        TodoItem.builder().id("todo-2").sequence(2).description("second").build()))
                .build();
        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        checkpoint.setNextTodoId("todo-2");

        AgentRun run = new AgentRun();
        run.setId("run-restart-1");
        run.setUserId("user-1");
        run.setExt("{}");
        run.setPlanJson(objectMapper.writeValueAsString(frozenPlan));
        run.setExecutionCheckpointJson("{\"version\":\"v1\"}");
        run.setRestartAttempt(1);
        when(runMapper.findById(run.getId())).thenReturn(run);
        when(events.isRunnable(run.getId(), run.getUserId())).thenReturn(true);
        when(events.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(models.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "endpoint", "model", List.of()));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(checkpoints.parseAndValidate(run, frozenPlan)).thenReturn(checkpoint);
        when(linear.restartPlanned(any(), eq(frozenPlan), eq(checkpoint)))
                .thenReturn(LangchainWorkflowResult.builder()
                        .success(true)
                        .plan(frozenPlan)
                        .completedTodos(List.of())
                        .finalAnswer("done")
                        .build());

        LangchainFollowUpContextSupport followUp = mock(LangchainFollowUpContextSupport.class);
        when(followUp.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        AgentCreditService credit = mock(AgentCreditService.class);
        when(credit.hasPositiveCredit(run.getUserId())).thenReturn(true);
        ObjectProvider<AgentRunStateStore> stateStoreProvider = mock(ObjectProvider.class);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear, dag, models, runMapper, events, objectMapper,
                mock(ObjectProvider.class), stateStoreProvider, mock(ObjectProvider.class),
                new LangchainFailureMapper(), followUp,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class), guard,
                LangchainRunSchedulerTestSupport.immediateScheduler(), credit,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(pipeline, "workflowCheckpointService", checkpoints);

        pipeline.executeRun(run, true);

        verify(planner, never()).plan(any());
        verify(linear).restartPlanned(any(), eq(frozenPlan), eq(checkpoint));
        verify(dag, never()).executePlanned(any(), any());
        verify(events).appendOnce(eq(run.getId()), eq(run.getUserId()),
                eq("WORKFLOW_RESTARTED"), eq("run-restart-1:restart:1"), any());
    }
}
