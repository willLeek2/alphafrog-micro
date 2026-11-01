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
    @org.junit.jupiter.api.BeforeEach
    void prepareVersionedRunContext() {
        world.willfrog.agent.platform.context.AgentContext.setRunId("fixture-current-run");
    }


    @AfterEach
    void cleanup() {
        AgentContext.clear();
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
        return LangchainTestFixtures.versionedNode(new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, guard, budget, stateStore, composer));
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
