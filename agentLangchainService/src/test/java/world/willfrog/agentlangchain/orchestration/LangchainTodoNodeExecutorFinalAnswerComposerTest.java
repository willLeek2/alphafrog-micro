package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.finance.FinanceResultComposer;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * writeFinalAnswer 与 {@link FinanceResultComposer} 的接线测试（Spec §11）。
 *
 * <p>钉死：模型块外说明生成后，executor 必须以当前请求的 runId/userId 调用 composer，
 * 并把 composer 的返回值作为最终答案字符串返回。</p>
 */
class LangchainTodoNodeExecutorFinalAnswerComposerTest {

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    @Test
    void writeFinalAnswer_shouldReturnComposerAugmentedText() {
        FinanceResultComposer composer = mock(FinanceResultComposer.class);
        when(composer.appendFinanceResultBlock(eq("run-x"), eq("user-y"), eq("模型最终答案")))
                .thenReturn("模型最终答案\n\n| 方法 | 结果 | 如何计算 |\n|---|---:|---|\n| M | 1 | H |");
        LangchainTodoNodeExecutor executor = newExecutor(composer);

        LangchainLinearWorkflowRequest request = LangchainLinearWorkflowRequest.builder()
                .runId("run-x")
                .userId("user-y")
                .userGoal("分析指数")
                .model(new FixedChatModel("模型最终答案"))
                .build();

        String result = executor.writeFinalAnswer(request, List.of());

        assertThat(result).isEqualTo("模型最终答案\n\n| 方法 | 结果 | 如何计算 |\n|---|---:|---|\n| M | 1 | H |");
        verify(composer).appendFinanceResultBlock("run-x", "user-y", "模型最终答案");
    }

    @Test
    void writeFinalAnswer_shouldReturnModelTextWhenComposerPassesThrough() {
        FinanceResultComposer composer = mock(FinanceResultComposer.class);
        when(composer.appendFinanceResultBlock(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        LangchainTodoNodeExecutor executor = newExecutor(composer);

        LangchainLinearWorkflowRequest request = LangchainLinearWorkflowRequest.builder()
                .runId("run-no-records")
                .userId("user-z")
                .userGoal("分析指数")
                .model(new FixedChatModel("纯文本答案"))
                .build();

        String result = executor.writeFinalAnswer(request, List.of());

        assertThat(result).isEqualTo("纯文本答案");
        verify(composer).appendFinanceResultBlock("run-no-records", "user-z", "纯文本答案");
    }

    private static LangchainTodoNodeExecutor newExecutor(FinanceResultComposer composer) {
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() { return null; }
        };
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AgentRunBudgetService budget = LangchainTestFixtures.noopBudgetService();
        AgentRunStateStore stateStore = LangchainTestFixtures.noopStateStore();
        return new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, guard, budget, stateStore, composer);
    }

    /** 固定文本 ChatModel：无论请求内容都返回同一最终答案。 */
    static class FixedChatModel implements ChatModel {
        private final String answer;

        FixedChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        }
    }
}
