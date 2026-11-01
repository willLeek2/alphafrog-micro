package world.willfrog.agentlangchain.parity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产默认两阶段规划的最小契约回归。
 */
class LangchainP1ParityHarnessTest {
    @org.junit.jupiter.api.AfterEach
    void clearVersionedRunContext() { world.willfrog.agent.platform.context.AgentContext.clear(); }

    @org.junit.jupiter.api.BeforeEach
    void prepareVersionedRunContext() {
        world.willfrog.agent.platform.context.AgentContext.setRunId("fixture-current-run");
    }


    private final LangchainAiPlanner planner = LangchainTestFixtures.versionedPlanner();



    private static final class JsonChatModel implements ChatModel {
        private final java.util.List<String> responses;
        private int index;

        private JsonChatModel(String... responses) {
            this.responses = java.util.List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(responses.get(index++))).build();
        }
    }
}
