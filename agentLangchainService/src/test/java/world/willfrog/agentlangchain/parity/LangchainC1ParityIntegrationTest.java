package world.willfrog.agentlangchain.parity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;
import world.willfrog.agentlangchain.execution.LangchainLinearWorkflowExecutor;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C1: LangChain linear path golden cases aligned with legacy batch-1 parity semantics.
 */
class LangchainC1ParityIntegrationTest {

    private final LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
            LangchainTestFixtures.versionedTodoNodeExecutor(),
            noopExecutionGuard(),
            mock(AgentRunEventService.class)
    );




    private static LangchainWorkflowRequest request(QueueChatModel model, String goal) {
        return LangchainWorkflowRequest.builder()
                .runId("run-parity-1")
                .userId("u1")
                .userGoal(goal)
                .model(LangchainTestFixtures.archivedNodeModel(model))
                        .executionEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
                        .finalAnswerEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
                .build();
    }

    private static LangchainTodoPlan singleTodoPlan(String description) {
        return LangchainTodoPlan.builder()
                .items(List.of(TodoItem.builder()
                        .id("t1")
                        .sequence(1)
                        .description(description)
                        .build()))
                .build();
    }

    private static void assertLegacyAlignedSuccess(LangchainWorkflowResult result, String expectedAnswer) {
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo(expectedAnswer);
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getPlan()).isNotNull();
        assertThat(result.getPlan().getItems()).isNotEmpty();
    }

    private static void assertLegacyAlignedFailure(LangchainWorkflowResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFinalAnswer()).isBlank();
        assertThat(result.getFailureReason()).isNotBlank();
    }

    static class QueueChatModel implements ChatModel {
        private final List<String> responses;
        private int index;

        QueueChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            String response = index < responses.size() ? responses.get(index++) : "";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        return guard;
    }
}
