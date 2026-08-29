package world.willfrog.agentlangchain.execution;

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
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointRequest;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointFailureRecoveryService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport;

class LangchainLinearRunPipelineCheckpointTest {

    @Test
    @SuppressWarnings("unchecked")
    void suspendedPipelineWritesFullDurableCheckpointBeforeReturning() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
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
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
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
        when(linear.executePlanned(any(), eq(plan))).thenReturn(LangchainWorkflowResult.builder()
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
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
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

        reset(events);
        when(runMapper.findById("run-1")).thenReturn(run, anchored);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(writer.captureAndSave(any())).thenReturn(false);
        ToolJobCheckpointFailureRecoveryService recoveryService =
                mock(ToolJobCheckpointFailureRecoveryService.class);
        when(recoveryService.handleFailure(any(ToolJobCheckpointRequest.class)))
                .thenReturn(ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED);
        Field recoveryField = LangchainLinearRunPipelineImpl.class
                .getDeclaredField("checkpointFailureRecoveryService");
        recoveryField.setAccessible(true);
        recoveryField.set(pipeline, recoveryService);

        pipeline.executeRun(run);

        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());
        verify(events).appendOnce(eq("run-1"), eq("user-1"),
                eq("TOOL_JOB_CHECKPOINT_FAILED"), any(), any());

        reset(events);
        clearInvocations(recoveryService);
        ToolJobAnchor newerAnchor = ToolJobAnchor.fromJson(anchor.toJson());
        newerAnchor.setCheckpointVersion(5);
        newerAnchor.setTodoId("todo-2");
        newerAnchor.setSequence(2);
        newerAnchor.setDatasetSnapshotJson(new ObjectMapper().writeValueAsString(
                AgentRunDatasetSnapshot.empty()));
        newerAnchor.setDatasetSnapshotDigest(AgentRunDatasetSnapshot.empty().immutableDigest());
        AgentRun newer = new AgentRun();
        newer.setId("run-1");
        newer.setToolJobAnchorJson(newerAnchor.toJson());
        when(runMapper.findById("run-1")).thenReturn(run, anchored, newer);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(recoveryService.handleFailure(any(ToolJobCheckpointRequest.class)))
                .thenReturn(ToolJobCheckpointFailureRecoveryService.Outcome.HEALTHY_CHECKPOINT);
        pipeline.executeRun(run);
        verify(recoveryService).handleFailure(any(ToolJobCheckpointRequest.class));
        verify(events).append(eq("run-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());
        verify(events, never()).appendOnce(eq("run-1"), eq("user-1"),
                eq("TOOL_JOB_CHECKPOINT_FAILED"), any(), any());

        reset(events);
        clearInvocations(recoveryService);
        when(runMapper.findById("run-1")).thenReturn(run, anchored, anchored);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(recoveryService.handleFailure(any(ToolJobCheckpointRequest.class)))
                .thenThrow(new IllegalStateException("conflict"));
        pipeline.executeRun(run);
        verify(events).appendOnce(eq("run-1"), eq("user-1"),
                eq("TOOL_JOB_CHECKPOINT_FAILED"), any(), any());
        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());

        reset(events);
        clearInvocations(recoveryService);
        when(runMapper.findById("run-1")).thenReturn(run, anchored);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(recoveryService.handleFailure(any(ToolJobCheckpointRequest.class)))
                .thenReturn(ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED);
        field.set(pipeline, null);
        pipeline.executeRun(run);
        verify(recoveryService).handleFailure(argThat(r -> r.getExpectedCheckpointVersion() == 4
                && "task-1".equals(r.getTaskId())));

        reset(events);
        clearInvocations(recoveryService);
        field.set(pipeline, writer);
        when(registryProvider.getIfAvailable()).thenReturn(null);
        when(runMapper.findById("run-1")).thenReturn(run, anchored);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        pipeline.executeRun(run);
        verify(recoveryService).handleFailure(any(ToolJobCheckpointRequest.class));

        reset(events);
        AgentRun missingAnchor = new AgentRun();
        missingAnchor.setId("run-1");
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        when(runMapper.findById("run-1")).thenReturn(run, missingAnchor);
        when(runMapper.updateTerminalSnapshot(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), eq(true), eq("tool_job_checkpoint_anchor_missing"))).thenReturn(1);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        pipeline.executeRun(run);
        verify(runMapper).updateTerminalSnapshot(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), eq(true), eq("tool_job_checkpoint_anchor_missing"));
        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());
    }
}
