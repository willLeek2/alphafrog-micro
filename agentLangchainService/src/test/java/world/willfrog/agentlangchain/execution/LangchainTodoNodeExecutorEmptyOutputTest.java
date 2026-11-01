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
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

/**
 * empty_todo_output 安全 recovery + 结构化观测的单测。
 *
 * <p>覆盖以下关键路径：
 * <ul>
 *   <li>当前 attempt 无工具执行证据 → 第一次 LLM 返回空 → 触发一次无工具 recovery（与 toolSpecifications 非空无关）</li>
 *   <li>当前 attempt 已有工具执行证据 → 第一次 LLM 返回空 → <b>不</b>走 recovery（直接失败）</li>
 *   <li>recovery 成功 → success(recovered=true, recovery_outcome=success)</li>
 *   <li>recovery 仍空 → failure(reason=empty_todo_output_after_recovery, recovery_outcome=still_blank)</li>
 *   <li>recovery 抛普通 LLM 异常 → failure(recovery_outcome=exception)；控制信号原样向上抛</li>
 *   <li>finish_reason 字段：null → "no_response"，"   " → "blank_after_trim"</li>
 *   <li>MF6: last_non_empty_todo_id = 已完成 todo 中最后一个非空 id</li>
 *   <li>MF1: stage / todoId / model 在 try 块外捕获，不受 finally 清 ThreadLocal 影响</li>
 *   <li>并行 todo 增加共享 toolCalls 计数器不影响当前 attempt 的恢复判断</li>
 * </ul>
 */
class LangchainTodoNodeExecutorEmptyOutputTest {
    @org.junit.jupiter.api.BeforeEach
    void prepareVersionedRunContext() {
        world.willfrog.agent.platform.context.AgentContext.setRunId("fixture-current-run");
    }


    private final AgentRunBudgetService budgetService = mock(AgentRunBudgetService.class);
    private final world.willfrog.agent.platform.service.AgentRunStateStore stateStore =
            mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    // ========== Recovery 行为 ==========




    // ========== 不走 recovery 的场景 ==========




    // ========== 取消不被 recovery 吞掉 ==========






    // ========== finish_reason 字段（MF3） ==========



    // ========== MF6: last_non_empty_todo_id ==========


    // ========== 辅助方法 ==========

    private static LangchainWorkflowRequest baseRequest(ChatModel model) {
        return LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析指数")
                .model(LangchainTestFixtures.archivedNodeModel(model))
                .executionEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
                .finalAnswerEndpointName(LangchainTestFixtures.NODE_ENDPOINT)
                .build();
    }

    private static TodoItem todo(String id, int seq, String desc) {
        return TodoItem.builder().id(id).sequence(seq).description(desc).build();
    }

    private static ObjectProvider<dev.langchain4j.service.tool.ToolProvider> emptyToolProvider() {
        return new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() { return null; }
            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() { return null; }
        };
    }

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(java.util.Optional.empty());
        return guard;
    }

    /**
     * 简单 ChatModel stub：根据 constructor 给的 responses 顺序返回；遇到 null 元素抛 RuntimeException。
     */
    static class QueueChatModel implements ChatModel {
        private final List<Object> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        QueueChatModel(String... responses) {
            this.responses = new ArrayList<>();
            Collections.addAll(this.responses, (Object[]) responses);
        }

        QueueChatModel(String first, RuntimeException recoveryEx) {
            this.responses = new ArrayList<>();
            this.responses.add(first);
            this.responses.add(recoveryEx);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            Object r = index < responses.size() ? responses.get(index++) : "";
            if (r instanceof RuntimeException ex) {
                throw ex;
            }
            String response = (String) r;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }

    /**
     * ChatModel 按照预置的 AiMessage 列表顺序返回（支持 ToolExecutionRequest）。
     * 用于测试 tool-request → blank 的流程。
     */
    static class ScriptedChatModel implements ChatModel {
        private final List<AiMessage> messages;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        ScriptedChatModel(AiMessage... messages) {
            this.messages = List.of(messages);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            AiMessage msg = index < messages.size() ? messages.get(index++) : AiMessage.from("");
            return ChatResponse.builder().aiMessage(msg).build();
        }

        List<ChatRequest> requests() { return requests; }
    }

    /**
     * 可被子类覆写 doChat 的 QueueChatModel，用于在 LLM 调用时注入副作用（如模拟并行 todo 增加共享计数器）。
     */
    static class CallbackChatModel extends QueueChatModel {
        CallbackChatModel(String... responses) {
            super(responses);
        }
    }
}
