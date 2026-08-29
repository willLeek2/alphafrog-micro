package world.willfrog.agentlangchain.orchestration.dag;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.orchestration.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.orchestration.LangchainWorkflowResult;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
}
