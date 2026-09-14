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

    private final LangchainAiPlanner planner = LangchainTestFixtures.planner();

    @Test
    void plan_shouldSetStructuredOutputSpecWhileCallingModel() {
        StructuredOutputCapturingChatModel model = new StructuredOutputCapturingChatModel(
                """
                {"overallPlan":{"mode":"LINEAR","detail":"顺序规划。"}}
                """,
                """
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

        assertThat(model.structuredOutputSeen).containsExactly(true, true);
        assertThat(AgentContext.getStructuredOutputSpec()).isNull();
    }

    @Test
    void legacySingleStage_shouldUseSharedStructuredValidation() {
        RecordingChatModel model = new RecordingChatModel("""
                {
                  "analysis": "先查数据再总结。",
                  "items": [
                    {
                      "id": "todo_1",
                      "sequence": 1,
                      "description": "查询沪深300近一年走势。",
                      "dependsOn": [],
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

        LangchainTodoPlan plan = LangchainTestFixtures.legacySingleStagePlanner().plan(
                LangchainPlanningRequest.builder()
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
        assertThat(plan.getItems().get(0).getDependsOn()).isEmpty();
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

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                .runId("run-forced-linear")
                .userGoal("分析数据")
                .model(model)
                .executionMode(PlanExecutionMode.LINEAR)
                .maxTodos(5)
                .build());

        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.get(0).toString())
                .contains("本 Run 请求的执行模式为 LINEAR")
                .contains("overallPlan.mode 必须返回 LINEAR");
        assertThat(model.requests.get(1).toString())
                .contains("\"mode\":\"LINEAR\"")
                .doesNotContain("\"mode\":\"DAG\"");
    }

    @Test
    void twoStagePlannerWithNoTools_shouldNotLeakAnyRegistryToolIntoSystemOrUserMessages() {
        SequentialRecordingChatModel model = new SequentialRecordingChatModel(
                """
                {"overallPlan":{"mode":"LINEAR","detail":"顺序分析。"}}
                """,
                """
                {"analysis":"直接回答。","items":[{"id":"todo_1","sequence":1,"description":"整理结论"}]}
                """);

        planner.plan(LangchainPlanningRequest.builder()
                .runId("run-no-tools")
                .userGoal("整理现有信息")
                .model(model)
                .toolSpecifications(List.of())
                .executionMode(PlanExecutionMode.LINEAR)
                .maxTodos(3)
                .build());

        assertThat(model.requests).hasSize(2);
        String completePlanningMessages = model.requests.toString();
        for (String toolName : AgentToolRegistry.declaredToolNames()) {
            assertThat(completePlanningMessages)
                    .as("没有开放工具时，planning 的完整 System+User 不得泄漏 %s", toolName)
                    .doesNotContain(toolName);
        }
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

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
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
    void legacySingleStage_shouldRetrySharedValidationAndFailOnEmptyItems() {
        RecordingChatModel model = new RecordingChatModel("""
                {"analysis":"empty","items":[],"extractedEntities":[]}
                """);

        assertThatThrownBy(() -> LangchainTestFixtures.legacySingleStagePlanner().plan(
                LangchainPlanningRequest.builder()
                .userGoal("hello")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning_retry_exhausted")
                .hasMessageContaining("todo_plan_items_empty");
        assertThat(model.requestCount).isEqualTo(2);
    }

    @Test
    void maxAttemptsConfig_shouldControlTwoStageValidationRetries() {
        AgentLlmProperties properties = LangchainTestFixtures.llmProperties();
        properties.getRuntime().getPlanning().getStructuredOutput().setMaxAttempts(3);
        SequentialRecordingChatModel model = new SequentialRecordingChatModel(
                "{}", "{}", "{}"
        );

        assertThatThrownBy(() -> planner(properties).plan(LangchainPlanningRequest.builder()
                .runId("run-retry-config")
                .userGoal("分析数据")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning_retry_exhausted");
        assertThat(model.requests).hasSize(3);
    }

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
