package world.willfrog.agentlangchain.orchestration.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.AgentRunFailureClass;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowRequest;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowResult;
import world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainDagWorkflowExecutorUnknownDefectTest {

    private AgentRunEventService eventService;
    private LangchainTodoNodeExecutor nodeExecutor;
    private LangchainDagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-test");
        AgentContext.setUserId("user-test");
        eventService = mock(AgentRunEventService.class);
        nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                mock(LangchainDagStateRecorder.class),
                eventService,
                guard,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new ObjectMapper(),
                new EmptyStateStoreProvider());
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 2);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void nodeError_shouldCompleteAsyncResultAsUnknownDefect() {
        when(nodeExecutor.execute(any(), any(), any(), any(), any(AtomicInteger.class)))
                .thenAnswer(invocation -> {
                    throw new AssertionError("boom");
                });

        TodoItem item = TodoItem.builder()
                .id("t1")
                .sequence(1)
                .description("desc-t1")
                .dependsOn(List.of())
                .build();
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(item))
                .extractedEntities(List.of())
                .build();
        LangchainLinearWorkflowRequest request = LangchainLinearWorkflowRequest.builder()
                .runId("run-dag-unknown-defect")
                .userId("user-1")
                .userGoal("test goal")
                .executionModel(mock(ChatModel.class))
                .finalAnswerModel(mock(ChatModel.class))
                .build();

        LangchainLinearWorkflowResult result = executor.executePlanned(request, plan);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).contains("DAG execution failed");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verifyAppendFailed(payloadCaptor);
        Map<String, Object> failedPayload = payloadCaptor.getValue();
        assertThat(failedPayload).containsKey("failure_metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) failedPayload.get("failure_metadata");
        assertThat(meta).containsEntry("unknown_defect", true);
        assertThat(meta).containsEntry("failure_class", AgentRunFailureClass.UNKNOWN_DEFECT.wireName());
        assertThat(meta).containsEntry("throwable_type", AssertionError.class.getName());
    }

    private void verifyAppendFailed(ArgumentCaptor<Map<String, Object>> payloadCaptor) {
        org.mockito.Mockito.verify(eventService, atLeastOnce()).append(
                eq("run-dag-unknown-defect"), eq("user-1"), eq("TODO_NODE_FAILED"), payloadCaptor.capture());
    }

    private static class EmptyStateStoreProvider
            implements ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> {
        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject() { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject(Object... args) { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfAvailable() { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfUnique() { return null; }
    }
}
