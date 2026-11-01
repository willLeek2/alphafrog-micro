package world.willfrog.agentlangchain.execution;

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
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

class LangchainLinearWorkflowExecutorTest {

    @org.junit.jupiter.api.AfterEach
    void clearContext() { AgentContext.clear(); }






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
