package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.*;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangchainLinearRunPipelineResumeTest {

    @Test
    @SuppressWarnings("unchecked")
    void resumedPipelineLoadsDurablePlanAndNeverInvokesPlanner() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        LangchainRunStageModelResolver stageModels = mock(LangchainRunStageModelResolver.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        LangchainFollowUpContextSupport followUp = mock(LangchainFollowUpContextSupport.class);
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo-2").sequence(2).description("resume").build()))
                .build();
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setExt("{}");
        run.setPlanJson(objectMapper.writeValueAsString(plan));
        when(runMapper.findById("run-1")).thenReturn(run);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentEventService.RunConfig.defaults());
        ChatModel model = mock(ChatModel.class);
        when(stageModels.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                model, model, model, "endpoint", "model", List.of()));
        when(followUp.resolve(run)).thenReturn(new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(true)
                        .finalAnswer("done")
                        .plan(plan)
                        .completedTodos(List.of())
                        .toolCallsUsed(4)
                        .build());
        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear, mock(LangchainDagWorkflowExecutor.class), stageModels,
                runMapper, events, objectMapper, mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), new LangchainFailureMapper(), followUp,
                mock(AgentMessageService.class), guard,
                LangchainRunSchedulerTestSupport.immediateScheduler(), mock(AgentCreditService.class),
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(3);
        context.setToolCallsUsed(4);

        pipeline.executeResumedRun(run, context, () -> true);

        verifyNoInteractions(planner);
        verify(linear).resumePlanned(any(), eq(plan), same(context), any());
        verify(events).appendOnce(eq("run-1"), eq("user-1"), eq("WORKFLOW_RESUMED"),
                eq("run-1:token-1:3:workflow_resumed"), any());
        verify(events, never()).append(eq("run-1"), eq("user-1"), eq("PLAN_READY"), any());
    }
}
