package world.willfrog.agentlangchain.planning;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangchainAiPlannerTest {
    @org.junit.jupiter.api.AfterEach
    void clearVersionedRunContext() { world.willfrog.agent.platform.context.AgentContext.clear(); }

    @org.junit.jupiter.api.BeforeEach
    void prepareVersionedRunContext() {
        world.willfrog.agent.platform.context.AgentContext.setRunId("fixture-current-run");
    }


    private final LangchainAiPlanner planner = LangchainTestFixtures.versionedPlanner();









    static class StructuredOutputCapturingChatModel extends SequentialRecordingChatModel {
        private final java.util.ArrayList<Boolean> structuredOutputSeen = new java.util.ArrayList<>();

        StructuredOutputCapturingChatModel(String... responses) {
            super(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            structuredOutputSeen.add(AgentContext.getStructuredOutputSpec() != null);
            return super.doChat(request);
        }
    }

    static class RecordingChatModel implements ChatModel {
        private final String response;
        private ChatRequest lastRequest;
        private int requestCount;

        RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.lastRequest = request;
            this.requestCount++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    static class SequentialRecordingChatModel implements ChatModel {
        private final List<String> responses;
        private final java.util.ArrayList<ChatRequest> requests = new java.util.ArrayList<>();

        SequentialRecordingChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            String response = responses.get(requests.size() - 1);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    private static LangchainAiPlanner planner(AgentLlmProperties properties) {
        return LangchainTestFixtures.versionedPlanner(properties);
    }

    static class DemoTools {
        @Tool("Search index data")
        String searchIndex(String query) {
            return query;
        }
    }
}
