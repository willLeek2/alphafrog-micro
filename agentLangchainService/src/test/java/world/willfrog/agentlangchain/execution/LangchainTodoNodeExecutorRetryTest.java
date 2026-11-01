package world.willfrog.agentlangchain.execution;

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
    @org.junit.jupiter.api.AfterEach
    void clearVersionedRunContext() { world.willfrog.agent.platform.context.AgentContext.clear(); }

    @org.junit.jupiter.api.BeforeEach
    void prepareVersionedRunContext() {
        world.willfrog.agent.platform.context.AgentContext.setRunId("fixture-current-run");
    }




    private LangchainTodoNodeExecutor configuredExecutor(ToolProvider tools) {
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.versionedTodoNodeExecutor(Optional.of(LangchainTestFixtures.runtimeDataTools(tools)));
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
                .model(LangchainTestFixtures.archivedNodeModel(model))
                .executionEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
                .finalAnswerEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
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
