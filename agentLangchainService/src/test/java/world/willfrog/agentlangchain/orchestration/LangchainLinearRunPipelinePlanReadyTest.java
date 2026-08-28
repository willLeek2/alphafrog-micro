package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
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
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangchainLinearRunPipelinePlanReadyTest {

    @Test
    void executeRun_explicitDagShouldKeepDagEvenWhenCodeInterpreterIsEnabled() throws Exception {
        LangchainTodoPlan plannerLinearPlan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("todo-1").sequence(1).description("查询").build(),
                        TodoItem.builder().id("todo-2").sequence(2).description("Python").build()))
                .build();

        assertPipelineMode(
                PlanExecutionMode.DAG,
                plannerLinearPlan,
                PlanExecutionMode.DAG,
                true);
    }

    @Test
    void executeRun_explicitLinearShouldCanonicalizePlannerDagEvenWhenCodeInterpreterIsEnabled() throws Exception {
        LangchainTodoPlan plannerDagPlan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("todo-1").sequence(1).description("查询").build(),
                        TodoItem.builder().id("todo-2").sequence(2).description("Python")
                                .dependsOn(List.of("todo-1"))
                                .parallelizable(true)
                                .groupKey("analysis")
                                .build()))
                .build();

        assertPipelineMode(
                PlanExecutionMode.LINEAR,
                plannerDagPlan,
                PlanExecutionMode.LINEAR,
                false);
    }

    @Test
    void executeRun_autoLinearShouldPersistAndExecuteTheSameEffectivePlan() throws Exception {
        LangchainTodoPlan plannerLinearPlan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("todo-1").sequence(1).description("查询").build(),
                        TodoItem.builder().id("todo-2").sequence(2).description("Python").build()))
                .build();

        assertPipelineMode(
                PlanExecutionMode.AUTO,
                plannerLinearPlan,
                PlanExecutionMode.LINEAR,
                false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeRun_shouldFailClosedWhenSuspensionCheckpointIsUnavailable() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);
        AgentRun run = new AgentRun();
        run.setId("run-pending-1");
        run.setUserId("user-1");
        run.setExt("{}");
        when(runMapper.findById("run-pending-1")).thenReturn(run);
        when(runMapper.updateTerminalSnapshot(eq("run-pending-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), eq(true), eq("tool_job_checkpoint_anchor_missing"))).thenReturn(1);
        when(eventService.isRunnable("run-pending-1", "user-1")).thenReturn(true);
        when(eventService.extractRunConfig("{}")).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(eventService.extractUserGoal("{}")).thenReturn("goal");
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));
        when(executionGuard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_2").sequence(2).description("python").build()))
                .build();
        when(planner.plan(any())).thenReturn(plan);
        when(linear.executePlanned(any(), eq(plan))).thenReturn(LangchainLinearWorkflowResult.builder()
                .success(false)
                .suspended(true)
                .plan(plan)
                .suspendedTodoId("todo_2")
                .suspendedTodoSequence(2)
                .pendingToolCallId("tc-pending")
                .pendingAttempt(1)
                .build());
        ObjectProvider<AgentRunStateStore> stateStoreProvider = mock(ObjectProvider.class);
        when(stateStoreProvider.getIfAvailable()).thenReturn(stateStore);
        LangchainFollowUpContextSupport followUp = mock(LangchainFollowUpContextSupport.class);
        when(followUp.resolve(run)).thenReturn(new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        AgentCreditService creditService = mock(AgentCreditService.class);
        when(creditService.hasPositiveCredit("user-1")).thenReturn(true);
        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, linear, mock(LangchainDagWorkflowExecutor.class), stageModelResolver,
                runMapper, eventService, new ObjectMapper(), mock(ObjectProvider.class), stateStoreProvider,
                mock(ObjectProvider.class), new LangchainFailureMapper(), followUp,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class), executionGuard,
                immediateScheduler(), creditService, mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));

        pipeline.executeRun(run);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq("run-pending-1"), eq("user-1"),
                eq("TOOL_JOB_CHECKPOINT_FAILED"), any(), payload.capture());
        assertThat((Map<String, Object>) payload.getValue())
                .containsEntry("tool_call_id", "tc-pending")
                .containsEntry("todo_id", "todo_2")
                .containsEntry("durable_failure_disposition", true);
        verify(eventService, never()).append(eq("run-pending-1"), eq("user-1"),
                eq("TOOL_CALL_SUSPENDED"), any());
        verify(runMapper).updateTerminalSnapshot(eq("run-pending-1"), eq("user-1"),
                eq(world.willfrog.agent.platform.model.AgentRunStatus.FAILED),
                any(), eq(true), eq("tool_job_checkpoint_anchor_missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeRun_autoDagWithCodeInterpreterShouldPersistAndExecuteEffectiveDagPlan() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainDagWorkflowExecutor dag = mock(LangchainDagWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRun run = new AgentRun();
        run.setId("run-plan-1");
        run.setUserId("user-1");
        run.setExt("{}");
        when(runMapper.findById("run-plan-1")).thenReturn(run);
        when(eventService.isRunnable("run-plan-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(eventService.extractExecutionMode(run.getExt())).thenReturn("AUTO");
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));
        when(executionGuard.stopReason(eq("run-plan-1"), eq("user-1"))).thenReturn(Optional.empty());

        LangchainTodoPlan plannerDagPlan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder()
                                .id("todo-1")
                                .sequence(1)
                                .description("查询指数行情")
                                .parallelizable(true)
                                .groupKey("market")
                                .build(),
                        TodoItem.builder()
                                .id("todo-2")
                                .sequence(2)
                                .description("执行 Python 分析")
                                .dependsOn(List.of("todo-1"))
                                .parallelizable(true)
                                .groupKey("market")
                                .build()))
                .build();
        when(planner.plan(any())).thenReturn(plannerDagPlan);
        when(dag.executePlanned(any(), any())).thenAnswer(invocation -> {
            LangchainTodoPlan effectivePlan = invocation.getArgument(1);
            return LangchainLinearWorkflowResult.builder()
                    .success(true)
                    .finalAnswer("ok")
                    .plan(effectivePlan)
                    .completedTodos(List.of())
                    .build();
        });

        ObjectProvider<AgentRunStateStore> stateStoreProvider = mock(ObjectProvider.class);
        when(stateStoreProvider.getIfAvailable()).thenReturn(stateStore);
        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linear,
                dag,
                stageModelResolver,
                runMapper,
                eventService,
                objectMapper,
                mock(ObjectProvider.class),
                stateStoreProvider,
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                executionGuard,
                immediateScheduler(),
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );

        pipeline.executeRun(run);

        ArgumentCaptor<LangchainPlanningRequest> planningRequest =
                ArgumentCaptor.forClass(LangchainPlanningRequest.class);
        verify(planner).plan(planningRequest.capture());
        assertThat(planningRequest.getValue().getExecutionMode())
                .isEqualTo(PlanExecutionMode.AUTO);

        ArgumentCaptor<LangchainTodoPlan> effectivePlanCaptor =
                ArgumentCaptor.forClass(LangchainTodoPlan.class);
        verify(dag).executePlanned(any(), effectivePlanCaptor.capture());
        verify(linear, never()).executePlanned(any(), any());
        LangchainTodoPlan effectivePlan = effectivePlanCaptor.getValue();
        assertThat(effectivePlan).isSameAs(plannerDagPlan);
        assertThat(effectivePlan.getExecutionMode()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(effectivePlan.getItems()).hasSize(2);
        assertThat(effectivePlan.getItems().get(1).getDependsOn()).containsExactly("todo-1");
        assertThat(effectivePlan.getItems().get(1).getGroupKey()).isEqualTo("market");
        assertThat(effectivePlan.getItems().get(1).isParallelizable()).isTrue();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).append(eq("run-plan-1"), eq("user-1"), eq("PLAN_READY"), payloadCaptor.capture());
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("execution_mode", "DAG")
                .containsEntry("requested_execution_mode", "AUTO")
                .containsEntry("effective_execution_mode", "DAG")
                .containsEntry("workflow", "dag");
        assertThat(payload.get("todo_count")).isEqualTo(2);
        assertThat(payload.get("plan")).isSameAs(effectivePlan);

        String expectedPlanJson = objectMapper.writeValueAsString(effectivePlan);
        InOrder inOrder = inOrder(runMapper, stateStore, eventService);
        inOrder.verify(runMapper).updatePlanJson("run-plan-1", "user-1", expectedPlanJson);
        inOrder.verify(stateStore).recordPlan("run-plan-1", expectedPlanJson, true);
        inOrder.verify(eventService).append(eq("run-plan-1"), eq("user-1"), eq("PLAN_READY"), any());
        verify(runMapper).updateTerminalSnapshot(eq("run-plan-1"), eq("user-1"), any(), any(), anyBoolean(), any());
    }

    @SuppressWarnings("unchecked")
    private void assertPipelineMode(PlanExecutionMode requestedMode,
                                    LangchainTodoPlan plannerPlan,
                                    PlanExecutionMode expectedMode,
                                    boolean expectDag) throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainDagWorkflowExecutor dag = mock(LangchainDagWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRun run = new AgentRun();
        run.setId("run-mode-" + requestedMode.name().toLowerCase());
        run.setUserId("user-mode");
        run.setExt("{\"execution_mode\":\"" + requestedMode.name() + "\"}");
        when(runMapper.findById(run.getId())).thenReturn(run);
        when(eventService.isRunnable(run.getId(), run.getUserId())).thenReturn(true);
        when(eventService.extractExecutionMode(run.getExt())).thenReturn(requestedMode.name());
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));
        when(executionGuard.stopReason(run.getId(), run.getUserId())).thenReturn(Optional.empty());
        when(planner.plan(any())).thenReturn(plannerPlan);
        when(linear.executePlanned(any(), any())).thenAnswer(invocation -> success(invocation.getArgument(1)));
        when(dag.executePlanned(any(), any())).thenAnswer(invocation -> success(invocation.getArgument(1)));
        ObjectProvider<AgentRunStateStore> stateStoreProvider = mock(ObjectProvider.class);
        when(stateStoreProvider.getIfAvailable()).thenReturn(stateStore);
        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));
        AgentCreditService creditService = mock(AgentCreditService.class);
        when(creditService.hasPositiveCredit(run.getUserId())).thenReturn(true);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linear,
                dag,
                stageModelResolver,
                runMapper,
                eventService,
                objectMapper,
                mock(ObjectProvider.class),
                stateStoreProvider,
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                executionGuard,
                immediateScheduler(),
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));

        pipeline.executeRun(run);

        ArgumentCaptor<LangchainPlanningRequest> requestCaptor =
                ArgumentCaptor.forClass(LangchainPlanningRequest.class);
        verify(planner).plan(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getExecutionMode()).isEqualTo(requestedMode);

        ArgumentCaptor<LangchainTodoPlan> planCaptor = ArgumentCaptor.forClass(LangchainTodoPlan.class);
        if (expectDag) {
            verify(dag).executePlanned(any(), planCaptor.capture());
            verify(linear, never()).executePlanned(any(), any());
        } else {
            verify(linear).executePlanned(any(), planCaptor.capture());
            verify(dag, never()).executePlanned(any(), any());
        }
        LangchainTodoPlan effectivePlan = planCaptor.getValue();
        assertThat(effectivePlan.getExecutionMode()).isEqualTo(expectedMode);
        if (expectedMode == PlanExecutionMode.LINEAR) {
            assertThat(effectivePlan.getItems()).allSatisfy(item -> {
                assertThat(item.getDependsOn()).isEmpty();
                assertThat(item.getGroupKey()).isNull();
                assertThat(item.isParallelizable()).isFalse();
            });
        }

        String expectedPlanJson = objectMapper.writeValueAsString(effectivePlan);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        InOrder inOrder = inOrder(runMapper, stateStore, eventService);
        inOrder.verify(runMapper).updatePlanJson(run.getId(), run.getUserId(), expectedPlanJson);
        inOrder.verify(stateStore).recordPlan(run.getId(), expectedPlanJson, true);
        inOrder.verify(eventService).append(
                eq(run.getId()), eq(run.getUserId()), eq("PLAN_READY"), payloadCaptor.capture());
        assertThat((Map<String, Object>) payloadCaptor.getValue())
                .containsEntry("execution_mode", expectedMode.name())
                .containsEntry("requested_execution_mode", requestedMode.name())
                .containsEntry("effective_execution_mode", expectedMode.name())
                .containsEntry("workflow", expectDag ? "dag" : "linear")
                .containsEntry("plan", effectivePlan);
    }

    private LangchainLinearWorkflowResult success(LangchainTodoPlan plan) {
        return LangchainLinearWorkflowResult.builder()
                .success(true)
                .finalAnswer("ok")
                .plan(plan)
                .completedTodos(List.of())
                .build();
    }
}
