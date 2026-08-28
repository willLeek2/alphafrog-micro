package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangchainLinearWorkflowExecutorTest {

    @Test
    void executePlanned_shouldRunPlanTodosAndFinalAnswerInOrder() {
        QueueChatModel model = new QueueChatModel(
                "todo1 output",
                "todo2 output based on todo1",
                "final answer"
        );
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                LangchainTestFixtures.todoNodeExecutor(),
                noopExecutionGuard(),
                mock(AgentRunEventService.class)
        );
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").sequence(1).description("查询沪深300").build(),
                        TodoItem.builder().id("todo_2").sequence(2).description("总结走势").build()))
                .extractedEntities(List.of("沪深300"))
                .build();

        LangchainLinearWorkflowResult result = executor.executePlanned(
                LangchainLinearWorkflowRequest.builder()
                        .runId("run-linear-1")
                        .userId("user-1")
                        .userGoal("分析沪深300")
                        .model(model)
                        .maxTodos(5)
                        .build(),
                plan);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final answer");
        assertThat(result.getCompletedTodos()).hasSize(2);
        assertThat(result.getCompletedTodos().get(0).getOutput()).isEqualTo("todo1 output");
        assertThat(result.getCompletedTodos().get(1).getOutput()).contains("todo1");
        assertThat(result.getPlan().getExtractedEntities()).containsExactly("沪深300");
        assertThat(model.requests()).hasSize(3);
        assertThat(model.requests().get(1).toString()).contains("todo1 output");
        assertThat(model.requests().get(2).toString()).contains("todo2 output based on todo1");
        assertThat(AgentContext.getRunId()).isNull();
    }

    @Test
    void executePlanned_shouldFailWhenTodoOutputIsBlank() {
        // ccmax #59: 第一次返回空 → executor 走 recovery（第二次）→ recovery 也空 → failure(empty_todo_output_after_recovery:...)
        QueueChatModel model = new QueueChatModel(
                "   ",  // 第一次：todo 执行返回空
                "   "   // 第二次：recovery 也返回空 → empty_todo_output_after_recovery
        );
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                LangchainTestFixtures.todoNodeExecutor(),
                noopExecutionGuard(),
                mock(AgentRunEventService.class)
        );
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("查询").build()))
                .extractedEntities(List.of())
                .build();

        LangchainLinearWorkflowResult result = executor.executePlanned(
                LangchainLinearWorkflowRequest.builder()
                        .userGoal("分析")
                        .model(model)
                        .build(),
                plan);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output_after_recovery:todo_1");
        assertThat(result.getFinalAnswer()).isNull();
    }

    @Test
    void executePlanned_shouldInjectDatasetRefsIntoLaterTodoPrompt() {
        QueueChatModel model = new QueueChatModel(
                "{\"ok\":true,\"tool\":\"getIndexDaily\",\"data\":{\"dataset_id\":\"dataset-hs300\"}}",
                "calculated result",
                "final answer"
        );
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                LangchainTestFixtures.todoNodeExecutor(),
                noopExecutionGuard(),
                mock(AgentRunEventService.class)
        );
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").sequence(1).description("获取指数数据").build(),
                        TodoItem.builder().id("todo_2").sequence(2).description("用 Python 计算收益").build()))
                .extractedEntities(List.of("沪深300"))
                .build();

        LangchainLinearWorkflowResult result = executor.executePlanned(
                LangchainLinearWorkflowRequest.builder()
                        .runId("run-dataset-1")
                        .userId("user-1")
                        .userGoal("计算收益")
                        .model(model)
                        .maxTodos(5)
                        .build(),
                plan);

        assertThat(result.isSuccess()).isTrue();
        assertThat(model.requests()).hasSize(3);
        assertThat(model.requests().get(1).toString())
                .contains("已有原始数据引用")
                .contains("dataset-hs300")
                .doesNotContain("run-level dataset_ids/manifest_ids")
                .doesNotContain("listMyData");
    }

    @Test
    void executePlanned_shouldReturnSuspendedAtCurrentTodo() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        ExternalToolJobPendingException pending =
                new ExternalToolJobPendingException("run-pending", "tc-pending", 3, "pending");
        when(nodeExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.suspended(pending));
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                nodeExecutor, noopExecutionGuard(), events);
        TodoItem todo = TodoItem.builder().id("todo_2").sequence(2).description("long python").build();
        LangchainTodoPlan plan = LangchainTodoPlan.builder().items(List.of(todo)).build();

        LangchainLinearWorkflowResult result = executor.executePlanned(
                LangchainLinearWorkflowRequest.builder()
                        .runId("run-pending")
                        .userId("user-1")
                        .userGoal("analyze")
                        .model(new QueueChatModel("unused"))
                        .build(),
                plan);

        assertThat(result.isSuspended()).isTrue();
        assertThat(result.getSuspendedTodoId()).isEqualTo("todo_2");
        assertThat(result.getPendingToolCallId()).isEqualTo("tc-pending");
        assertThat(result.getPendingAttempt()).isEqualTo(3);
        verify(events).append(org.mockito.ArgumentMatchers.eq("run-pending"),
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("TODO_NODE_SUSPENDED"), any());
    }

    @Test
    void todoNodeExecutor_shouldConvertWrappedPendingIntoSuspendedResult() {
        ExternalToolJobPendingException pending =
                new ExternalToolJobPendingException("run-pending", "tc-pending", 2, "pending");
        ChatModel pendingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new RuntimeException("lc4j wrapper", pending);
            }
        };

        LangchainTodoNodeResult result = LangchainTestFixtures.todoNodeExecutor().execute(
                LangchainLinearWorkflowRequest.builder()
                        .runId("run-pending")
                        .userId("user-1")
                        .userGoal("analyze")
                        .model(pendingModel)
                        .build(),
                TodoItem.builder().id("todo_2").sequence(2).description("long python").build(),
                List.of(),
                new java.util.LinkedHashMap<>(),
                new AtomicInteger());

        assertThat(result.isSuspended()).isTrue();
        assertThat(result.getPendingToolCallId()).isEqualTo("tc-pending");
        assertThat(result.getPendingAttempt()).isEqualTo(2);
    }

    static class QueueChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        QueueChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            String response = index < responses.size() ? responses.get(index++) : "";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        return guard;
    }
}
