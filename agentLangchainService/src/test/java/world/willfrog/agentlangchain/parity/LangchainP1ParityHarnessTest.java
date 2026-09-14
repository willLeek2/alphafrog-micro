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

    private final LangchainAiPlanner planner = LangchainTestFixtures.planner();

    @Test
    void linear_simple_success_plan() {
        ChatModel model = new JsonChatModel(
                """
                {"overallPlan":{"mode":"LINEAR","detail":"简单任务"}}
                """,
                """
                {
                  "analysis": "simple task",
                  "items": [
                    {"id": "t1", "sequence": 1, "description": "todo 1"}
                  ]
                }
                """);

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                .userGoal("analyze one stock")
                .model(model)
                .executionMode(PlanExecutionMode.LINEAR)
                .build());

        assertThat(plan.getItems()).hasSize(1);
        assertThat(plan.getItems().get(0).getId()).isEqualTo("t1");
        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
    }

    @Test
    void empty_plan_fails_afterConfiguredTwoStageRetries() {
        ChatModel model = new JsonChatModel(
                "{\"overallPlan\":{\"mode\":\"LINEAR\",\"detail\":\"无任务\"}}",
                "{\"analysis\":\"none\",\"items\":[]}",
                "{\"overallPlan\":{\"mode\":\"LINEAR\",\"detail\":\"无任务\"}}",
                "{\"analysis\":\"none\",\"items\":[]}");

        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("do nothing")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning_retry_exhausted")
                .hasMessageContaining("todo_plan_items_empty");
    }

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
