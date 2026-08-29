package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.*;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointFailureRecoveryService;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport;

class LangchainLinearRunPipelineResumeTest {

    @Test
    @SuppressWarnings("unchecked")
    void resumedPipelineLoadsDurablePlanAndNeverInvokesPlanner() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        LangchainRunStageModelResolver stageModels = mock(LangchainRunStageModelResolver.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        LangchainFollowUpContextSupport followUp = mock(LangchainFollowUpContextSupport.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
        ObjectProvider<AgentRunObservabilityService> observabilityProvider = mock(ObjectProvider.class);
        when(observabilityProvider.getIfAvailable()).thenReturn(observabilityService);
        when(observabilityService.prepareTerminalSnapshot(
                eq("run-1"), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    world.willfrog.agent.platform.model.AgentRunStatus status = invocation.getArgument(2);
                    String snapshot = invocation.getArgument(1);
                    return new AgentRunObservabilityService.TerminalSnapshotCandidate(
                            "run-1", status, snapshot, "{\"status\":\"" + status.name() + "\"}", 1, 1);
                });
        AgentRunCreditSettlementService settlementService =
                mock(AgentRunCreditSettlementService.class);
        world.willfrog.agent.platform.event.AgentRunFinalizationService finalizationService =
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class);
        world.willfrog.agent.platform.service.AgentPromptService promptService =
                mock(world.willfrog.agent.platform.service.AgentPromptService.class);
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo-2").sequence(2).description("resume").build()))
                .build();
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setExt("""
                {"prompt_selection":{"schema_version":1,"bundle_version":"default-v1",
                "variant":"control","bundle_digest":"bundle-digest",
                "capability_catalog_digest":"capability-digest","reference_date":"2025-02-03"}}
                """);
        run.setPlanJson(objectMapper.writeValueAsString(plan));
        when(runMapper.findById("run-1")).thenReturn(run);
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.COMPLETED),
                any(), any(), eq(true), isNull(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenReturn(1);
        when(events.isRunnable("run-1", "user-1")).thenReturn(true);
        when(events.extractRunConfig(anyString())).thenReturn(AgentRunEventService.RunConfig.defaults());
        ChatModel model = mock(ChatModel.class);
        when(stageModels.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                model, model, model, "endpoint", "model", List.of()));
        when(followUp.resolve(run)).thenReturn(new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AtomicReference<world.willfrog.agent.platform.prompt.PromptRunSelection> restoredSelection =
                new AtomicReference<>();
        when(linear.resumePlanned(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (AgentContext.getPromptRunSelection() != null) {
                restoredSelection.set(AgentContext.getPromptRunSelection());
            }
            return LangchainLinearWorkflowResult.builder()
                    .success(true)
                    .finalAnswer("done")
                    .plan(plan)
                    .completedTodos(List.of())
                    .toolCallsUsed(4)
                    .build();
        });
        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear, mock(LangchainDagWorkflowExecutor.class), stageModels,
                runMapper, events, objectMapper, mock(ObjectProvider.class), mock(ObjectProvider.class),
                observabilityProvider, new LangchainFailureMapper(), followUp,
                messageService, guard,
                LangchainRunSchedulerTestSupport.immediateScheduler(), mock(AgentCreditService.class),
                settlementService,
                finalizationService,
                promptService,
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(3);
        context.setResumeLauncherOwnerId("owner-1");
        context.setResultConsumed(true);
        context.setToolCallsUsed(4);

        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isTrue();
        assertThat(restoredSelection.get()).isNotNull();
        assertThat(restoredSelection.get().bundleVersion()).isEqualTo("default-v1");
        assertThat(restoredSelection.get().variant()).isEqualTo("control");
        assertThat(restoredSelection.get().referenceDate().toString()).isEqualTo("2025-02-03");
        verify(promptService).validatePromptSelection(argThat(selection ->
                selection != null && "default-v1".equals(selection.bundleVersion())));
        InOrder promptBoundaryOrder = inOrder(promptService, stageModels, followUp);
        promptBoundaryOrder.verify(promptService).validatePromptSelection(any());
        promptBoundaryOrder.verify(stageModels).resolve(run);
        promptBoundaryOrder.verify(followUp).resolve(run);
        assertThat(AgentContext.getPromptRunSelection()).isNull();
        verify(observabilityService, times(1)).commitTerminalSnapshot(argThat(candidate ->
                candidate.status() == world.willfrog.agent.platform.model.AgentRunStatus.COMPLETED));

        verifyNoInteractions(planner);
        verify(linear).resumePlanned(any(), eq(plan), same(context), any());
        verify(events).appendOnce(eq("run-1"), eq("user-1"), eq("WORKFLOW_RESUMED"),
                eq("run-1:token-1:3:workflow_resumed"), any());
        verify(events, never()).append(eq("run-1"), eq("user-1"), eq("PLAN_READY"), any());

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-2:1");
        anchor.setToolCallId("tc-2");
        anchor.setTaskId("task-2");
        anchor.setAttempt(1);
        run.setToolJobAnchorJson(anchor.toJson());
        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .suspended(true).plan(plan).completedTodos(List.of())
                        .suspendedTodoId("todo-2").suspendedTodoSequence(2)
                        .pendingToolCallId("tc-2").pendingAttempt(1).build());
        Field writerField = LangchainLinearRunPipelineImpl.class
                .getDeclaredField("toolJobCheckpointWriter");
        writerField.setAccessible(true);
        writerField.set(pipeline, mock(ToolJobCheckpointWriter.class));
        ToolJobCheckpointFailureRecoveryService recoveryService =
                mock(ToolJobCheckpointFailureRecoveryService.class);
        when(recoveryService.handleFailure(any()))
                .thenReturn(ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED);
        Field recoveryField = LangchainLinearRunPipelineImpl.class
                .getDeclaredField("checkpointFailureRecoveryService");
        recoveryField.setAccessible(true);
        recoveryField.set(pipeline, recoveryService);
        clearInvocations(events);

        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();

        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());
        verify(events).appendOnce(eq("run-1"), eq("user-1"),
                eq("TOOL_JOB_CHECKPOINT_FAILED"), any(), any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(true).finalAnswer("not-durable").plan(plan)
                        .completedTodos(List.of()).build());
        clearInvocations(messageService, settlementService, finalizationService, events,
                observabilityService);
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.COMPLETED),
                any(), any(), eq(true), isNull(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenReturn(0);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(messageService, never()).createAssistantMessage(any(), any(), any());
        verify(settlementService, never()).settleAsync(any(), any());
        verify(finalizationService, never()).publishFinalizedEvent(any(), any(), any());
        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("WORKFLOW_COMPLETED"), any());
        verify(observabilityService, never()).commitTerminalSnapshot(any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .partial(true).failureReason("partial-result").finalAnswer("partial-answer")
                        .plan(plan).completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.PARTIAL),
                any(), any(), eq(true), eq("partial-result"),
                eq("token-1"), eq(3L), eq("owner-1"))).thenReturn(0);
        clearInvocations(messageService, settlementService, finalizationService, events,
                observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(observabilityService, never()).commitTerminalSnapshot(any());
        verify(messageService, never()).createAssistantMessage(any(), any(), any());
        verify(settlementService, never()).settleAsync(any(), any());
        verify(finalizationService, never()).publishFinalizedEvent(any(), any(), any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(false).failureReason("stale-worker-failure").plan(plan)
                        .completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), any(), eq(true), any(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenReturn(0);
        clearInvocations(messageService, settlementService, finalizationService, events,
                observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(messageService, never()).createAssistantMessage(any(), any(), any());
        verify(settlementService, never()).settleAsync(any(), any());
        verify(finalizationService, never()).publishFinalizedEvent(any(), any(), any());
        verify(events, never()).append(eq("run-1"), eq("user-1"),
                eq("WORKFLOW_FAILED"), any());
        verify(observabilityService, never()).commitTerminalSnapshot(any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .partial(true).failureReason("winner-partial").finalAnswer("partial-answer")
                        .plan(plan).completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.PARTIAL),
                any(), any(), eq(true), eq("winner-partial"),
                eq("token-1"), eq(3L), eq("owner-1"))).thenReturn(1);
        clearInvocations(observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isTrue();
        verify(observabilityService, times(1)).commitTerminalSnapshot(argThat(candidate ->
                candidate.status() == world.willfrog.agent.platform.model.AgentRunStatus.PARTIAL));

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(false).failureReason("winner-failure").plan(plan)
                        .completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), any(), eq(true), any(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenReturn(1);
        clearInvocations(observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isTrue();
        verify(observabilityService, times(1)).commitTerminalSnapshot(argThat(candidate ->
                candidate.status() == world.willfrog.agent.platform.model.AgentRunStatus.FAILED));

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .partial(true).failureReason("throwing-partial-write")
                        .plan(plan).completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.PARTIAL),
                any(), any(), eq(true), eq("throwing-partial-write"),
                eq("token-1"), eq(3L), eq("owner-1")))
                .thenThrow(new IllegalStateException("db-partial-write-failed"));
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), any(), eq(true), any(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenThrow(new IllegalStateException("db-failure-write-failed"));
        clearInvocations(observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(observabilityService, never()).commitTerminalSnapshot(any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(false).failureReason("throwing-failure-write")
                        .plan(plan).completedTodos(List.of()).build());
        clearInvocations(observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(observabilityService, never()).commitTerminalSnapshot(any());

        when(linear.resumePlanned(any(), any(), any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder()
                        .success(true).finalAnswer("throwing-write").plan(plan)
                        .completedTodos(List.of()).build());
        when(runMapper.updateResumedTerminal(eq("run-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.COMPLETED),
                any(), any(), eq(true), isNull(), eq("token-1"), eq(3L), eq("owner-1")))
                .thenThrow(new IllegalStateException("db-write-failed"));
        clearInvocations(observabilityService);
        assertThat(pipeline.executeResumedRun(run, context, () -> true)).isFalse();
        verify(observabilityService, never()).commitTerminalSnapshot(any());
        verify(runMapper, never()).updatePlanJson(any(), any(), any());
        verify(runMapper, never()).updateSnapshot(any(), any(), any(), any(), anyBoolean(), any());
    }
}
