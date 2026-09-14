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
            LangchainTestFixtures.todoNodeExecutor(),
            noopExecutionGuard(),
            mock(AgentRunEventService.class)
    );

    @Test
    void linear_simple_success_matchesLegacyOutcomeShape() {
        QueueChatModel model = new QueueChatModel(
                "{\"ok\":true,\"data\":{\"dataset_id\":\"ds_etf_daily\"}}",
                "512800.SH 最近一周呈震荡上行。"
        );

        LangchainWorkflowResult result = executor.executePlanned(
                request(model, "查询 512800.SH 最近一周走势"),
                singleTodoPlan("查询 512800.SH 最近一周走势"));

        assertLegacyAlignedSuccess(result, "512800.SH 最近一周呈震荡上行。");
        assertThat(result.getCompletedTodos()).hasSize(1);
        assertThat(result.getCompletedTodos().get(0).getOutput()).contains("ds_etf_daily");
    }

    @Test
    void empty_final_answer_failed_matchesLegacyFailureShape() {
        QueueChatModel model = new QueueChatModel(
                "{\"ok\":true,\"data\":{}}",
                "   "
        );

        LangchainWorkflowResult result = executor.executePlanned(
                request(model, "分析某只股票"),
                singleTodoPlan("分析某只股票"));

        assertLegacyAlignedFailure(result);
        assertThat(result.getFailureReason()).isEqualTo("empty_final_answer");
    }

    @Test
    void empty_todo_output_failed_matchesLegacyTodoFailureSemantics() {
        QueueChatModel model = new QueueChatModel(
                ""
        );

        LangchainWorkflowResult result = executor.executePlanned(
                request(model, "查询"),
                singleTodoPlan("查询"));

        assertLegacyAlignedFailure(result);
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output_after_recovery:t1");
        assertThat(result.getFinalAnswer()).isBlank();
    }

    private static LangchainWorkflowRequest request(QueueChatModel model, String goal) {
        return LangchainWorkflowRequest.builder()
                .runId("run-parity-1")
                .userId("u1")
                .userGoal(goal)
                .model(model)
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
