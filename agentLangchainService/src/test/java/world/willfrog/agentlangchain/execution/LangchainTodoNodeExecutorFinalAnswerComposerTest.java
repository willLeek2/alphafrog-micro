package world.willfrog.agentlangchain.execution;

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
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

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

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
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

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-no-records")
                .userId("user-z")
                .userGoal("分析指数")
                .model(new FixedChatModel("纯文本答案"))
                .build();

        String result = executor.writeFinalAnswer(request, List.of());

        assertThat(result).isEqualTo("纯文本答案");
        verify(composer).appendFinanceResultBlock("run-no-records", "user-z", "纯文本答案");
    }

    @Test
    void writeFinalAnswer_shouldSendFinalPromptWithFinanceBlockIsolationInstruction() {
        // codex e740f454 ①/③：final-stage prompt 必须带 §11 块外隔离约束——
        // 服务端追加数值表 + 禁止模型复述后台身份/摘要；捕获实际 ChatRequest 钉死。
        FinanceResultComposer composer = mock(FinanceResultComposer.class);
        when(composer.appendFinanceResultBlock(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        LangchainTodoNodeExecutor executor = newExecutor(composer);
        FixedChatModel model = new FixedChatModel("最终答案");

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-prompt")
                .userId("user-p")
                .userGoal("分析指数")
                .model(model)
                .build();

        executor.writeFinalAnswer(request, List.of());

        assertThat(model.requests()).isNotEmpty();
        ChatRequest captured = model.requests().get(model.requests().size() - 1);
        StringBuilder userText = new StringBuilder();
        for (dev.langchain4j.data.message.ChatMessage message : captured.messages()) {
            if (message instanceof dev.langchain4j.data.message.UserMessage userMessage) {
                userText.append(userMessage.singleText()).append('\n');
            }
        }
        String prompt = userText.toString();
        // 服务端追加契约
        assertThat(prompt).contains("由服务端在你的回答之后自动追加");
        // 块外禁止复述：digest/版本/环境/镜像/包/证据/resolver/内部警告/各类 ID
        assertThat(prompt).contains("禁止在回答中复述或拼接任何内部身份与后台信息");
        assertThat(prompt).contains("sha256:");
        assertThat(prompt).contains("执行环境或镜像身份");
        assertThat(prompt).contains("证据类型或等级");
        assertThat(prompt).contains("resolver");
        assertThat(prompt).contains("内部警告");
        // prompt 自身不携带任何真实后台身份值（静态指令文本）
        assertThat(prompt).doesNotContain("run-prompt");
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

    /** 固定文本 ChatModel：无论请求内容都返回同一最终答案；记录全部请求供 prompt 断言。 */
    static class FixedChatModel implements ChatModel {
        private final String answer;
        private final List<ChatRequest> requests = new java.util.ArrayList<>();

        FixedChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }
}
