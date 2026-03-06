package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.service.AgentCreditService;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DagWorkflowExecutorTest {

    @Mock private AgentEventService eventService;
    @Mock private AgentPromptService promptService;
    @Mock private ToolRouter toolRouter;
    @Mock private SubAgentRunner subAgentRunner;
    @Mock private AgentRunStateStore stateStore;
    @Mock private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private AgentCreditService creditService;
    @Mock private ChatModel model;

    private DagWorkflowExecutor executor;
    private ExecutorService dagExecutor;

    @BeforeEach
    void setUp() {
        dagExecutor = Executors.newFixedThreadPool(4);
        DagBuilder dagBuilder = new DagBuilder();
        ToolCallCounter counter = new ToolCallCounter(stateStore);
        TodoParamResolver resolver = new TodoParamResolver();

        executor = new DagWorkflowExecutor(
                dagBuilder,
                eventService,
                promptService,
                toolRouter,
                subAgentRunner,
                resolver,
                counter,
                stateStore,
                llmRequestSnapshotBuilder,
                observabilityService,
                creditService,
                dagExecutor,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 20);
        ReflectionTestUtils.setField(executor, "timeoutMinutes", 1);

        lenient().when(stateStore.loadWorkflowState(anyString())).thenReturn(Optional.empty());
        lenient().when(stateStore.getToolCallCount(anyString())).thenReturn(0);
        lenient().when(stateStore.incrementToolCallCount(anyString(), anyInt())).thenReturn(1);
        lenient().when(promptService.workflowFinalSystemPrompt()).thenReturn("final");
        lenient().when(creditService.calculateToolCredits(anyString(), anyBoolean())).thenReturn(1);
        lenient().when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                anyString(), anyString(), anyString(), any(), any(), anyMap())).thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        ChatResponse response = mockResponse("done");
        lenient().when(model.chat(any(List.class))).thenReturn(response);
    }

    @AfterEach
    void tearDown() {
        dagExecutor.shutdownNow();
    }

    @Test
    void execute_shouldCompleteParallelTasksSuccessfully() {
        when(eventService.isRunnable("run-dag-1", "u1")).thenReturn(true);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"ok\":true}").build()
        );

        // 3 parallel tasks, no dependencies
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("searchStock").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("searchStock").build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("searchStock").build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-1", plan));

        assertTrue(result.isSuccess());
        assertFalse(result.isPaused());
        assertEquals(3, result.getCompletedItems().size());
    }

    @Test
    void execute_shouldRespectDependencyOrder() {
        when(eventService.isRunnable("run-dag-2", "u1")).thenReturn(true);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"price\":100}").build()
        );
        when(toolRouter.invokeWithMeta(eq("summarize"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("summary").build()
        );

        // todo_1, todo_2 parallel → todo_3 depends on both
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("searchStock")
                                .parallelizable(true).build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("searchStock")
                                .parallelizable(true).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("summarize")
                                .dependsOn(List.of("todo_1", "todo_2")).build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-2", plan));

        assertTrue(result.isSuccess());
        assertEquals(3, result.getCompletedItems().size());
        // Verify summarize was called (which means deps completed first)
        verify(toolRouter, atLeastOnce()).invokeWithMeta(eq("summarize"), anyMap());
    }

    @Test
    void execute_shouldHandleCyclicDependency() {
        // Cyclic: A → B → C → A
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a")
                                .dependsOn(List.of("todo_3")).build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b")
                                .dependsOn(List.of("todo_1")).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_2")).build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-3", plan));

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("dag_validation_failed"));
    }

    @Test
    void execute_shouldHandleEmptyPlan() {
        TodoPlan plan = TodoPlan.builder().items(List.of()).build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-4", plan));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getCompletedItems().size());
    }

    @Test
    void execute_shouldHandleThoughtNodes() {
        when(eventService.isRunnable("run-dag-5", "u1")).thenReturn(true);

        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.THOUGHT).toolName("think")
                                .reasoning("Let me think about this").build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-5", plan));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getCompletedItems().size());
    }

    @Test
    void execute_shouldReportFailureWhenToolCallFails() {
        when(eventService.isRunnable("run-dag-6", "u1")).thenReturn(true);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(false).output("error occurred").build()
        );

        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("searchStock").build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-6", plan));

        assertFalse(result.isSuccess());
        assertEquals("todo_partial_failed", result.getFailureReason());
    }

    @Test
    void execute_shouldPauseWhenRunNotRunnable() {
        when(eventService.isRunnable("run-dag-7", "u1")).thenReturn(false);

        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("searchStock").build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-7", plan));

        assertTrue(result.isPaused());
        verify(eventService).append(eq("run-dag-7"), eq("u1"), eq("WORKFLOW_PAUSED"), anyMap());
    }

    @Test
    void execute_shouldHandleDiamondDag() {
        when(eventService.isRunnable("run-dag-8", "u1")).thenReturn(true);
        when(toolRouter.invokeWithMeta(anyString(), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"ok\":true}").build()
        );

        //     todo_1
        //     /    \
        // todo_2  todo_3
        //     \    /
        //     todo_4
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b")
                                .dependsOn(List.of("todo_1")).parallelizable(true).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_1")).parallelizable(true).build(),
                        TodoItem.builder().id("todo_4").type(TodoType.TOOL_CALL).toolName("d")
                                .dependsOn(List.of("todo_2", "todo_3")).build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(buildRequest("run-dag-8", plan));

        assertTrue(result.isSuccess());
        assertEquals(4, result.getCompletedItems().size());
    }

    private LinearWorkflowExecutor.WorkflowRequest buildRequest(String runId, TodoPlan plan) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        return LinearWorkflowExecutor.WorkflowRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("test goal")
                .todoPlan(plan)
                .model(model)
                .toolSpecifications(List.of(
                        ToolSpecification.builder().name("searchStock").description("search").build(),
                        ToolSpecification.builder().name("summarize").description("summarize").build(),
                        ToolSpecification.builder().name("a").description("a").build(),
                        ToolSpecification.builder().name("b").description("b").build(),
                        ToolSpecification.builder().name("c").description("c").build(),
                        ToolSpecification.builder().name("d").description("d").build(),
                        ToolSpecification.builder().name("think").description("think").build()
                ))
                .endpointName("test")
                .endpointBaseUrl("http://test")
                .modelName("test-model")
                .build();
    }

    private static ChatResponse mockResponse(String text) {
        AiMessage aiMessage = AiMessage.from(text);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(metadata)
                .build();
    }
}
