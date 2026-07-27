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
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangchainAiPlannerTest {

    private final LangchainAiPlanner planner = LangchainTestFixtures.planner();

    @Test
    void plan_shouldSetStructuredOutputSpecWhileCallingModel() {
        StructuredOutputCapturingChatModel model = new StructuredOutputCapturingChatModel("""
                {
                  "analysis": "ok",
                  "items": [{"id":"todo_1","sequence":1,"description":"查数据"}],
                  "extractedEntities": []
                }
                """);

        LangchainTestFixtures.planner().plan(LangchainPlanningRequest.builder()
                .runId("run-structured")
                .userGoal("分析沪深300")
                .model(model)
                .build());

        assertThat(model.structuredOutputSeen).isTrue();
        assertThat(AgentContext.getStructuredOutputSpec()).isNull();
    }

    @Test
    void plan_shouldUseAiServiceStructuredOutputAndNormalizeTodoPlan() {
        RecordingChatModel model = new RecordingChatModel("""
                {
                  "analysis": "先查数据再总结。",
                  "items": [
                    {
                      "id": "",
                      "sequence": 0,
                      "description": "查询沪深300近一年走势。",
                      "dependsOn": ["", "todo_0"],
                      "parallelizable": true
                    },
                    {
                      "id": "todo_2",
                      "sequence": 2,
                      "description": "基于查询结果生成结论。"
                    }
                  ],
                  "extractedEntities": ["沪深300", "沪深300", "2025"]
                }
                """);

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析沪深300近一年走势")
                .dialogueContext("无")
                .model(model)
                .toolSpecifications(ToolSpecifications.toolSpecificationsFrom(new DemoTools()))
                .executionMode(PlanExecutionMode.DAG)
                .maxTodos(5)
                .build());

        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(plan.getAnalysis()).contains("查数据");
        assertThat(plan.getExtractedEntities()).containsExactly("沪深300", "2025");
        assertThat(plan.getItems()).hasSize(2);
        assertThat(plan.getItems().get(0).getId()).isEqualTo("todo_1");
        assertThat(plan.getItems().get(0).getSequence()).isEqualTo(1);
        assertThat(plan.getItems().get(0).getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(plan.getItems().get(0).getDependsOn()).containsExactly("todo_0");
        assertThat(plan.getItems().get(0).isParallelizable()).isTrue();
        assertThat(model.lastRequest.toString()).contains("searchIndex");
        assertThat(model.lastRequest.toString()).contains("步骤数尽可能少，上限 5");
    }

    @Test
    void twoStagePlannerShouldPropagateForcedLinearIntoBothPrompts() {
        SequentialRecordingChatModel model = new SequentialRecordingChatModel(
                """
                {"overallPlan":{"mode":"DAG","detail":"先并行查询，再汇总。"}}
                """,
                """
                {
                  "analysis":"按顺序查询并汇总。",
                  "items":[
                    {"id":"todo_1","sequence":1,"description":"查询数据"},
                    {"id":"todo_2","sequence":2,"description":"汇总结论"}
                  ]
                }
                """);

        LangchainTodoPlan plan = twoStagePlanner().plan(LangchainPlanningRequest.builder()
                .runId("run-forced-linear")
                .userGoal("分析数据")
                .model(model)
                .executionMode(PlanExecutionMode.LINEAR)
                .maxTodos(5)
                .build());

        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.get(0).toString())
                .contains("执行模式由调度器强制为 LINEAR")
                .contains("overallPlan.mode 必须返回 LINEAR");
        assertThat(model.requests.get(1).toString())
                .contains("\"mode\":\"LINEAR\"")
                .doesNotContain("\"mode\":\"DAG\"");
    }

    @Test
    void twoStagePlannerShouldAcceptLinearizableDagMetadataForExecutionCanonicalization() {
        SequentialRecordingChatModel model = new SequentialRecordingChatModel(
                """
                {"overallPlan":{"mode":"LINEAR","detail":"顺序执行。"}}
                """,
                """
                {
                  "analysis":"错误地生成依赖。",
                  "items":[
                    {"id":"todo_1","sequence":1,"description":"查询"},
                    {"id":"todo_2","sequence":2,"description":"汇总","dependsOn":["todo_1"]}
                  ]
                }
                """);

        LangchainTodoPlan plan = twoStagePlanner().plan(LangchainPlanningRequest.builder()
                .runId("run-linear-dag-shape")
                .userGoal("分析数据")
                .model(model)
                .executionMode(PlanExecutionMode.LINEAR)
                .maxTodos(5)
                .build());

        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(plan.getItems()).extracting(item -> item.getId())
                .containsExactly("todo_1", "todo_2");
        assertThat(plan.getItems().get(1).getDependsOn()).containsExactly("todo_1");
        assertThat(model.requests).hasSize(2);
    }

    @Test
    void plan_shouldRejectMissingModel() {
        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("hello")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planning_chat_model_required");
    }

    @Test
    void plan_shouldFailOnEmptyItems() {
        RecordingChatModel model = new RecordingChatModel("""
                {"analysis":"empty","items":[],"extractedEntities":[]}
                """);

        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("hello")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("todo_plan_empty");
    }

    static class StructuredOutputCapturingChatModel extends RecordingChatModel {
        private boolean structuredOutputSeen;

        StructuredOutputCapturingChatModel(String response) {
            super(response);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            structuredOutputSeen = AgentContext.getStructuredOutputSpec() != null;
            return super.doChat(request);
        }
    }

    static class RecordingChatModel implements ChatModel {
        private final String response;
        private ChatRequest lastRequest;

        RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.lastRequest = request;
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

    private static LangchainAiPlanner twoStagePlanner() {
        AgentLlmProperties properties = LangchainTestFixtures.llmProperties();
        properties.getRuntime().getPlanning().getStructuredOutput()
                .setStrategyStageEnabled(true);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(objectMapper);
        return new LangchainAiPlanner(
                new AgentPromptService(properties, loader),
                new LangchainPlanningStructuredOutputSettings(properties, loader),
                objectMapper);
    }

    static class DemoTools {
        @Tool("Search index data")
        String searchIndex(String query) {
            return query;
        }
    }
}
