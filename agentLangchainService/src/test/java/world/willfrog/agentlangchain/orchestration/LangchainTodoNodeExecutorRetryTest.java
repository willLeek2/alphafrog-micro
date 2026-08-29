package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LangchainTodoNodeExecutorRetryTest {

    @Test
    void readOnlyToolGetsExactlyOneCorrectiveTodoRetry() {
        AtomicInteger executions = new AtomicInteger();
        ToolProvider tools = provider("searchWeb", (request, memoryId) -> {
            if (executions.getAndIncrement() == 0) {
                throw new IllegalStateException("upstream timeout");
            }
            return "{\"ok\":true,\"data\":[]}";
        });
        var model = new LangchainTodoNodeExecutorEmptyOutputTest.ScriptedChatModel(
                toolCall("call-1", "searchWeb", "{\"query\":\"old\"}"),
                toolCall("call-2", "searchWeb", "{\"query\":\"corrected\"}"),
                AiMessage.from("done"));
        LangchainTodoNodeExecutor executor = configuredExecutor(tools);

        LangchainTodoNodeResult result = executor.execute(
                request(model, "searchWeb"),
                TodoItem.builder().id("todo_1").sequence(1).description("search").build(),
                List.of(), new LinkedHashMap<>(), new AtomicInteger());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("done");
        assertThat(result.getTodoRetryAttempts()).isEqualTo(1);
        assertThat(result.getTodoRetryOutcome()).isEqualTo("success");
        assertThat(executions.get()).isEqualTo(2);
        assertThat(model.requests().get(1).messages().toString())
                .contains("TODO_RETRY_CONTEXT", "old", "upstream timeout");
    }

    @Test
    void unsafeToolNeverGetsSecondExecutionEvenWhenFailureIsRetryable() {
        AtomicInteger executions = new AtomicInteger();
        ToolProvider tools = provider("spawnSubAgent", (request, memoryId) -> {
            executions.incrementAndGet();
            throw new IllegalStateException("upstream timeout");
        });
        var model = new LangchainTodoNodeExecutorEmptyOutputTest.ScriptedChatModel(
                toolCall("call-1", "spawnSubAgent", "{\"goal\":\"write\"}"));
        LangchainTodoNodeExecutor executor = configuredExecutor(tools);

        LangchainTodoNodeResult result = executor.execute(
                request(model, "spawnSubAgent"),
                TodoItem.builder().id("todo_unsafe").sequence(1).description("spawn").build(),
                List.of(), new LinkedHashMap<>(), new AtomicInteger());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureMetadata()).containsEntry("retryable", true);
        assertThat(result.getFailureMetadata()).containsEntry("todo_retry_allowed", false);
        assertThat(result.getFailureMetadata()).containsEntry("tool_retry_safety", "UNSAFE");
        assertThat(result.getFailureMetadata()).containsEntry("todo_retry_attempts", 0);
        assertThat(executions.get()).isEqualTo(1);
        assertThat(model.requests()).hasSize(1);
    }

    private LangchainTodoNodeExecutor configuredExecutor(ToolProvider tools) {
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor(Optional.of(tools));
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        ReflectionTestUtils.setField(executor, "todoRetryPolicy", new TodoRetryPolicy(
                new LangchainFailureMapper(), new ToolRetrySafetyCatalog(), meterProvider));
        WorkflowCheckpointService checkpointService = mock(WorkflowCheckpointService.class);
        ReflectionTestUtils.setField(executor, "workflowCheckpointService", checkpointService);
        return executor;
    }

    private LangchainWorkflowRequest request(
            dev.langchain4j.model.chat.ChatModel model, String toolName) {
        return LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("goal")
                .model(model)
                .toolSpecifications(List.of(ToolSpecification.builder().name(toolName).build()))
                .maxToolRoundTrips(3)
                .build();
    }

    private ToolProvider provider(String toolName, ToolExecutor executor) {
        return new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(ToolProviderRequest request) {
                return new ToolProviderResult(Map.of(
                        ToolSpecification.builder().name(toolName).build(), executor));
            }
        };
    }

    private AiMessage toolCall(String id, String name, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build());
    }
}
