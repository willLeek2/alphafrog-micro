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

    private final AgentRunBudgetService budgetService = mock(AgentRunBudgetService.class);
    private final world.willfrog.agent.platform.service.AgentRunStateStore stateStore =
            mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    // ========== Recovery 行为 ==========

    @Test
    void execute_shouldRecoverAndSucceedWhenFirstResponseIsBlankButSecondIsNot() {
        // 第一次返回空白（"   "），第二次（recovery）返回真实答案
        QueueChatModel model = new QueueChatModel("   ", "RECOVERED_ANSWER");
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_1", 1, "分析指数");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRecovered()).isTrue();
        assertThat(result.getRecoveryOutcome()).isEqualTo("success");
        assertThat(result.getOutput()).isEqualTo("RECOVERED_ANSWER");
        assertThat(result.getFailureMetadata()).isNull();
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void execute_shouldFailWithStillBlankWhenRecoveryAlsoReturnsBlank() {
        QueueChatModel model = new QueueChatModel("   ", "   ");
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_2", 1, "分析指数");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output_after_recovery:todo_2");
        assertThat(result.getFailureMetadata()).isNotNull();
        assertThat(result.getFailureMetadata().get("recovery_attempted")).isEqualTo(true);
        assertThat(result.getFailureMetadata().get("recovery_outcome")).isEqualTo("still_blank");
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void execute_shouldFailWithExceptionWhenRecoveryThrows() {
        // 第一次返回空白，第二次（recovery）抛 RuntimeException
        QueueChatModel model = new QueueChatModel("   ", new RuntimeException("LLM down"));
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_3", 1, "分析指数");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output_after_recovery:todo_3");
        assertThat(result.getFailureMetadata().get("recovery_attempted")).isEqualTo(true);
        assertThat(result.getFailureMetadata().get("recovery_outcome")).isEqualTo("exception");
    }

    // ========== 不走 recovery 的场景 ==========

    @Test
    void execute_shouldRecoverWhenToolSpecsPresentButNoToolActuallyStarted() {
        // D10 fix: 工具规范非空但当前 attempt 无实际工具执行 → 仍应走 recovery。
        // 旧语义是"有 toolSpec 就不恢复"，生产默认总有工具，导致 recovery 永远不可达。
        QueueChatModel model = new QueueChatModel("   ", "RECOVERED_ANSWER");
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析指数")
                .model(model)
                .toolSpecifications(List.of(
                        dev.langchain4j.agent.tool.ToolSpecification.builder()
                                .name("getIndexDaily")
                                .build()))
                .build();
        TodoItem item = todo("todo_4", 1, "调用工具");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // tool spec 非空，但 beforeToolExecution 从未被调用（模型直接返回空）→ 应恢复
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRecovered()).isTrue();
        assertThat(result.getRecoveryOutcome()).isEqualTo("success");
        assertThat(result.getOutput()).isEqualTo("RECOVERED_ANSWER");
        assertThat(model.requests()).hasSize(2); // 两次 LLM 调用（原始 + recovery）
    }

    @Test
    void execute_shouldSkipRecoveryWhenToolAlreadyExecutedBeforeEmptyOutput() {
        // D10: 当前 attempt 先返回 ToolExecutionRequest（触发 beforeToolExecution → tool started），
        // 再返回空白 → 不得恢复。
        var model = new ScriptedChatModel(
                dev.langchain4j.data.message.AiMessage.from(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("getIndexDaily")
                                .arguments("{}")
                                .build()),
                dev.langchain4j.data.message.AiMessage.from("   "));
        var toolProvider = new dev.langchain4j.service.tool.ToolProvider() {
            @Override
            public dev.langchain4j.service.tool.ToolProviderResult provideTools(
                    dev.langchain4j.service.tool.ToolProviderRequest request) {
                return new dev.langchain4j.service.tool.ToolProviderResult(
                        java.util.Map.of(
                                dev.langchain4j.agent.tool.ToolSpecification.builder()
                                        .name("getIndexDaily")
                                        .build(),
                                (dev.langchain4j.service.tool.ToolExecutor) (toolRequest, memoryId) ->
                                        "{\"ok\":true,\"data\":[]}"));
            }
        };

        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor(
                java.util.Optional.of(toolProvider));

        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析指数")
                .model(model)
                .toolSpecifications(List.of(
                        dev.langchain4j.agent.tool.ToolSpecification.builder()
                                .name("getIndexDaily")
                                .build()))
                .maxToolRoundTrips(2)
                .build();
        TodoItem item = todo("todo_tool_evidence", 1, "调用工具");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // 工具确实执行了一次，然后模型返回空白 → 不恢复，直接失败
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output:todo_tool_evidence");
        assertThat(result.getFailureMetadata().get("recovery_attempted")).isEqualTo(false);
        assertThat(result.getFailureMetadata().get("recovery_outcome")).isEqualTo("not_attempted");
        assertThat(result.getFailureMetadata().get("current_attempt_had_tool_evidence")).isEqualTo(true);
        assertThat(result.getFailureMetadata().get("recovery_skip_reason")).isEqualTo("current_attempt_tool_already_executed");
        assertThat(model.requests()).hasSize(2); // 第一次 tool request + tool result 后的第二次空白
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void execute_shouldStillRecoverWhenParallelTodoIncreasesSharedCounter() {
        // D10: 并行 todo 增加了共享 toolCalls，但当前 attempt 没执行工具 → 仍应恢复。
        // 证明没有用共享计数器差值替代 per-attempt 信号。
        AtomicInteger toolCalls = new AtomicInteger(5); // 并行 todo 已增加计数
        // 在第一次 LLM 调用时再由"并行方"增加一次，模拟两方同时执行
        var model = new CallbackChatModel("   ", "RECOVERED_ANSWER") {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                if (requests().isEmpty()) {
                    toolCalls.incrementAndGet(); // 并行 todo 又增加了一次
                }
                return super.doChat(request);
            }
        };
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_parallel", 1, "分析");
        int callsBefore = toolCalls.get();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // 共享计数被并行 todo 改变，但当前 attempt 无工具证据 → 仍恢复
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRecovered()).isTrue();
        assertThat(model.requests()).hasSize(2); // 原始 + recovery，恰好 2 次
        // 并行 todo 确实增加了共享计数，且原始+recovery 都没执行工具 → 差值即 callback 增加的 1
        assertThat(toolCalls.get() - callsBefore).isEqualTo(1);
    }

    // ========== 取消不被 recovery 吞掉 ==========

    @Test
    void execute_shouldNotSwallowCancelDuringRecovery() {
        // D10: 用户在第一次空白与 recovery 请求之间取消 →
        // RUN_INTERRUPTED 必须原样向上抛，不能被改写成 empty_todo_output_after_recovery。
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        // 前两次 check（execute 入口 + 第一次 chatRequestTransformer）→ 可运行
        when(guard.stopReason("run-1", "user-1"))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.empty())
                // 第三次 check（recovery 的 chatRequestTransformer）→ 已取消
                .thenReturn(java.util.Optional.of("CANCEL_REQUESTED"));
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = emptyToolProvider();
        LangchainTodoNodeExecutor executor = new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, guard, budgetService, stateStore,
                LangchainTestFixtures.noopFinanceResultComposer());

        QueueChatModel model = new QueueChatModel("   ");
        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_cancel", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // 取消信号被保留，没有被 recovery 异常分支吞掉
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).contains("RUN_INTERRUPTED:CANCEL_REQUESTED");
        assertThat(result.getFailureReason()).doesNotContain("empty_todo_output");
        assertThat(model.requests()).hasSize(1); // 只发了一次 LLM，recovery 被阻止
    }

    @Test
    void execute_shouldNotRecoverWhenBudgetHit() {
        // ccmax #59 round 2: budgetHit 改为基于实际用量（>= 80% limit）而不是"配置存在"。
        // 这里设 maxLlmCalls=10 + 实际 llmCalls=8（>= 8 阈值 = 80%×10）→ hit=true → 不走 recovery
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(0L, 10, 0, 0, 0));
        world.willfrog.agent.platform.service.AgentRunStateStore stateStore = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        when(stateStore.loadObservability(any())).thenReturn(java.util.Optional.of(
                "{\"summary\":{\"llmCalls\":8,\"toolCalls\":0,\"totalTokens\":100,\"startedAtMillis\":0}}"));
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = emptyToolProvider();
        LangchainTodoNodeExecutor executor = new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, noopExecutionGuard(), budget, stateStore,
                LangchainTestFixtures.noopFinanceResultComposer());

        QueueChatModel model = new QueueChatModel("   ");
        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_5", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output:todo_5");
        assertThat(result.getFailureMetadata().get("budget_hit")).isEqualTo(true);
        assertThat(result.getFailureMetadata().get("recovery_outcome")).isEqualTo("not_attempted");
        assertThat(result.getFailureMetadata().get("recovery_skip_reason")).isEqualTo("budget_near_limit");
        assertThat(result.getFailureMetadata().get("current_attempt_had_tool_evidence")).isEqualTo(false);
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void execute_shouldStillRecoverWhenBudgetConfiguredButUsageLow() {
        // codex 必须改：非 0 默认预算配置 + 当前用量很低 → 仍应触发一次 no-tool recovery
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(600_000L, 50, 30, 300_000, 3));
        world.willfrog.agent.platform.service.AgentRunStateStore stateStore = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        // 实际用量都低：llmCalls=2 (< 50×0.8=40), toolCalls=0, totalTokens=100 (< 240000)
        when(stateStore.loadObservability(any())).thenReturn(java.util.Optional.of(
                "{\"summary\":{\"llmCalls\":2,\"toolCalls\":0,\"totalTokens\":100,\"startedAtMillis\":0}}"));
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = emptyToolProvider();
        LangchainTodoNodeExecutor executor = new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, noopExecutionGuard(), budget, stateStore,
                LangchainTestFixtures.noopFinanceResultComposer());

        QueueChatModel model = new QueueChatModel("   ", "RECOVERED");
        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_low_usage", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // 关键断言：虽然配置了完整预算上限，但用量低 → recovery 仍触发并成功
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRecovered()).isTrue();
        assertThat(result.getRecoveryOutcome()).isEqualTo("success");
        assertThat(result.getFailureMetadata()).isNull();
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void execute_shouldNotRecoverWhenUsageNearThreshold() {
        // codex 必须改：用量接近/超过阈值（80% 比例）时，不触发 recovery，observation budget_hit=true
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(600_000L, 50, 30, 300_000, 3));
        world.willfrog.agent.platform.service.AgentRunStateStore stateStore = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        // 用量达到 80%：llmCalls=40 (50×0.8=40 → 触发) + totalTokens=240000 (300000×0.8=240000 → 也触发)
        when(stateStore.loadObservability(any())).thenReturn(java.util.Optional.of(
                "{\"summary\":{\"llmCalls\":40,\"toolCalls\":0,\"totalTokens\":240000,\"startedAtMillis\":0}}"));
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = emptyToolProvider();
        LangchainTodoNodeExecutor executor = new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, noopExecutionGuard(), budget, stateStore,
                LangchainTestFixtures.noopFinanceResultComposer());

        QueueChatModel model = new QueueChatModel("   ");
        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_near_threshold", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output:todo_near_threshold");
        Map<String, Object> obs = result.getFailureMetadata();
        assertThat(obs.get("budget_hit")).isEqualTo(true);
        assertThat(obs.get("recovery_attempted")).isEqualTo(false);
        assertThat(obs.get("recovery_outcome")).isEqualTo("not_attempted");
        assertThat(model.requests()).hasSize(1); // 没走 recovery
    }

    @Test
    void execute_shouldNotRecoverWhenUsageExceedsLimit() {
        // codex 必须改：用量 >= 100% limit（已超）→ 不走 recovery
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(600_000L, 50, 30, 300_000, 3));
        world.willfrog.agent.platform.service.AgentRunStateStore stateStore = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        // 已超：toolCalls=30 = maxToolCalls（>= 30×0.8=24 → 触发）
        when(stateStore.loadObservability(any())).thenReturn(java.util.Optional.of(
                "{\"summary\":{\"llmCalls\":0,\"toolCalls\":30,\"totalTokens\":0,\"startedAtMillis\":0}}"));
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = emptyToolProvider();
        LangchainTodoNodeExecutor executor = new LangchainTodoNodeExecutor(
                LangchainTestFixtures.promptService(), provider, noopExecutionGuard(), budget, stateStore,
                LangchainTestFixtures.noopFinanceResultComposer());

        QueueChatModel model = new QueueChatModel("   ");
        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_exceeded", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureMetadata().get("budget_hit")).isEqualTo(true);
        assertThat(result.getFailureMetadata().get("recovery_outcome")).isEqualTo("not_attempted");
        assertThat(model.requests()).hasSize(1);
    }

    // ========== finish_reason 字段（MF3） ==========

    @Test
    void execute_shouldMarkFinishReasonNoResponseWhenAiMessageTextIsNull() {
        // 当 LLM 返回 AiMessage(text=null) 时，LC4j AiMessage.from() 抛 IllegalArgumentException("text cannot be null")
        // → executor 走 catch → failure 无 metadata。Recovery 设计上只针对 "trim 后空"，不针对 null text 异常。
        QueueChatModel model = new QueueChatModel((String) null);
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_6", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        // 路径确认：null text → IllegalArgumentException → catch → failure(reason=text cannot be null, failureMetadata=null)
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureMetadata()).isNull();
        assertThat(result.getFailureReason()).contains("text cannot be null");
    }

    @Test
    void execute_shouldMarkFinishReasonBlankAfterTrimWhenFirstOutputIsWhitespace() {
        // "   \n\t  " = 3 spaces + \n + \t + 2 spaces = 7 chars
        QueueChatModel model = new QueueChatModel("   \n\t  ");
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_7", 1, "分析");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, Collections.emptyList(), new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        Map<String, Object> obs = result.getFailureMetadata();
        assertThat(obs.get("finish_reason")).isEqualTo("blank_after_trim");
        assertThat(obs.get("raw_output_length")).isEqualTo(7);
        assertThat(obs.get("trimmed_output_length")).isEqualTo(0);
    }

    // ========== MF6: last_non_empty_todo_id ==========

    @Test
    void execute_shouldRecordLastNonEmptyTodoIdFromCompletedTodos() {
        QueueChatModel model = new QueueChatModel("   ");
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor();

        // 上游已有两个完成 todo：t0 空、t1 非空
        List<LangchainCompletedTodo> completed = new ArrayList<>();
        completed.add(LangchainCompletedTodo.builder()
                .todoId("t0").sequence(0).description("").output("").summary("").build());
        completed.add(LangchainCompletedTodo.builder()
                .todoId("t1").sequence(1).description("").output("REAL_DATA_12345").summary("").build());

        LangchainWorkflowRequest request = baseRequest(model);
        TodoItem item = todo("todo_8", 2, "下一步");
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executor.execute(
                request, item, completed, new LinkedHashMap<>(), toolCalls);

        assertThat(result.isSuccess()).isFalse();
        Map<String, Object> obs = result.getFailureMetadata();
        assertThat(obs.get("last_non_empty_todo_id")).isEqualTo("t1");
        assertThat(obs.get("previous_todo_total_length")).isEqualTo((long) "REAL_DATA_12345".length());
    }

    // ========== 辅助方法 ==========

    private static LangchainWorkflowRequest baseRequest(ChatModel model) {
        return LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析指数")
                .model(model)
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
