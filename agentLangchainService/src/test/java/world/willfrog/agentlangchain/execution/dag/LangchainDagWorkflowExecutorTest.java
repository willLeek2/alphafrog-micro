package world.willfrog.agentlangchain.execution.dag;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainDagWorkflowExecutorTest {

    @Test
    void executePlanned_shouldNotOccupyWorkerWhileWaitingForDependencies() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainDagStateRecorder stateRecorder = mock(LangchainDagStateRecorder.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);
        when(executionGuard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainDagWorkflowExecutor executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                stateRecorder,
                eventService,
                executionGuard,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 1);
        when(nodeExecutor.execute(any(), any(), any(), any(), any(AtomicInteger.class)))
                .thenAnswer(invocation -> {
                    TodoItem item = invocation.getArgument(1);
                    return LangchainTodoNodeResult.success("done:" + item.getId(), 0);
                });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final answer");

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-dag-order")
                .userId("user-1")
                .userGoal("compare indices")
                .executionModel(mock(ChatModel.class))
                .finalAnswerModel(mock(ChatModel.class))
                .build();
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t2").sequence(2).description("compare").dependsOn(List.of("t1")).build(),
                        TodoItem.builder().id("t1").sequence(1).description("fetch").build()
                ))
                .build();

        LangchainWorkflowResult result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> executor.executePlanned(request, plan));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final answer");
    }

    @Test
    void executePlanned_shouldPropagateAndRestoreLaneContextAcrossDagWorkerPool() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainDagStateRecorder stateRecorder = mock(LangchainDagStateRecorder.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);
        when(executionGuard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainDagWorkflowExecutor executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                stateRecorder,
                eventService,
                executionGuard,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 1);

        Map<String, String> laneByTodo = new ConcurrentHashMap<>();
        Map<String, String> mdcByTodo = new ConcurrentHashMap<>();
        Map<String, String> threadByTodo = new ConcurrentHashMap<>();
        when(nodeExecutor.execute(any(), any(), any(), any(), any(AtomicInteger.class)))
                .thenAnswer(invocation -> {
                    TodoItem item = invocation.getArgument(1);
                    laneByTodo.put(item.getId(), LaneContext.trafficScopeId());
                    mdcByTodo.put(item.getId(), MDC.get(LaneContext.MDC_LANE_TAG));
                    threadByTodo.put(item.getId(), Thread.currentThread().getName());
                    if ("t1".equals(item.getId())) {
                        LaneContext.setTrafficScopeId("node-local-value");
                        MDC.put(LaneContext.MDC_LANE_TAG, "node-local-value");
                    }
                    return LangchainTodoNodeResult.success("done:" + item.getId(), 0);
                });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final answer");

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-dag-lane")
                .userId("user-1")
                .userGoal("verify lane propagation")
                .executionModel(mock(ChatModel.class))
                .finalAnswerModel(mock(ChatModel.class))
                .build();
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("first").build(),
                        TodoItem.builder().id("t2").sequence(2).description("second")
                                .dependsOn(List.of("t1")).build()
                ))
                .build();

        LaneContext.setTrafficScopeId("lane-test");
        MDC.put(LaneContext.MDC_LANE_TAG, "lane-test");
        String callerThread = Thread.currentThread().getName();
        try {
            LangchainWorkflowResult result = assertTimeout(Duration.ofSeconds(2),
                    () -> executor.executePlanned(request, plan));

            assertThat(result.isSuccess()).isTrue();
            assertThat(laneByTodo).containsEntry("t1", "lane-test").containsEntry("t2", "lane-test");
            assertThat(mdcByTodo).containsEntry("t1", "lane-test").containsEntry("t2", "lane-test");
            assertThat(threadByTodo.values()).allMatch(thread -> !callerThread.equals(thread));
            assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
            assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("lane-test");
        } finally {
            LaneContext.clear();
            MDC.remove(LaneContext.MDC_LANE_TAG);
        }
    }
}
