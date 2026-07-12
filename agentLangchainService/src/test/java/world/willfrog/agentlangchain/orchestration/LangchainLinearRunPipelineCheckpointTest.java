package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.*;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointRequest;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangchainLinearRunPipelineCheckpointTest {

    @Test
    @SuppressWarnings("unchecked")
    void suspendedPipelineWritesFullDurableCheckpointBeforeReturning() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunStageModelResolver models = mock(LangchainRunStageModelResolver.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        LangchainFollowUpContextSupport followUp = mock(LangchainFollowUpContextSupport.class);
        ToolJobCheckpointWriter writer = mock(ToolJobCheckpointWriter.class);
        AgentRunDatasetRegistry registry = mock(AgentRunDatasetRegistry.class);
        ObjectProvider<AgentRunDatasetRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        when(registry.snapshot("run-1")).thenReturn(AgentRunDatasetSnapshot.empty());

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setExt("{}");
        AgentRun anchored = new AgentRun();
        anchored.setId("run-1");
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-1");
        anchor.setCheckpointVersion(4);
        anchor.setEstimateJson("{\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchored.setToolJobAnchorJson(anchor.toJson());
        when(runMapper.findById("run-1")).thenReturn(run, anchored);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentEventService.RunConfig.defaults());
        when(models.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "endpoint", "model", List.of()));
        when(followUp.resolve(run)).thenReturn(new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AgentCreditService credit = mock(AgentCreditService.class);
        when(credit.hasPositiveCredit("user-1")).thenReturn(true);
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo-2").sequence(2).description("python").build()))
                .build();
        when(planner.plan(any())).thenReturn(plan);
        LangchainCompletedTodo completed = LangchainCompletedTodo.builder()
                .todoId("todo-1").sequence(1).description("prior").output("out").build();
        when(linear.executePlanned(any(), eq(plan))).thenReturn(LangchainLinearWorkflowResult.builder()
                .suspended(true)
                .plan(plan)
                .completedTodos(List.of(completed))
                .toolCallsUsed(3)
                .suspendedTodoId("todo-2")
                .suspendedTodoSequence(2)
                .pendingToolCallId("tc-1")
                .pendingAttempt(1)
                .build());
        when(writer.captureAndSave(any())).thenReturn(true);
        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear, mock(LangchainDagWorkflowExecutor.class), models,
                runMapper, events, new ObjectMapper().findAndRegisterModules(),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new LangchainFailureMapper(), followUp, mock(AgentMessageService.class), guard,
                LangchainRunSchedulerTestSupport.immediateScheduler(), credit,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                registryProvider, mock(ObjectProvider.class));
        Field field = LangchainLinearRunPipelineImpl.class.getDeclaredField("toolJobCheckpointWriter");
        field.setAccessible(true);
        field.set(pipeline, writer);

        pipeline.executeRun(run);

        ArgumentCaptor<ToolJobCheckpointRequest> request = ArgumentCaptor.forClass(ToolJobCheckpointRequest.class);
        verify(writer).captureAndSave(request.capture());
        assertThat(request.getValue().getExpectedCheckpointVersion()).isEqualTo(4);
        assertThat(request.getValue().getTodoId()).isEqualTo("todo-2");
        assertThat(request.getValue().getCompletedTodos()).extracting("todoId").containsExactly("todo-1");
        assertThat(request.getValue().getDatasetSnapshotDigest())
                .isEqualTo(AgentRunDatasetSnapshot.empty().immutableDigest());
        assertThat(request.getValue().getToolCallsUsed()).isEqualTo(3);
    }
}
