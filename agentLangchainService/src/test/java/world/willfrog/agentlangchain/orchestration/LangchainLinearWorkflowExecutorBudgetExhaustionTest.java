package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ccmax Phase 3.2 A3: LINEAR 端 budget 触顶降级路径测试。
 * <p>
 * 覆盖 4 个场景：
 * <ul>
 *   <li><b>partial 路径</b>：已完成 todo ≥ 1 → deterministic partial answer + WORKFLOW_PARTIAL_BUDGET 事件 + 不调 writeFinalAnswer；</li>
 *   <li><b>fail-fast 路径</b>：已完成 todo = 0 → 无 partial answer + WORKFLOW_FAILED_BUDGET 事件 + 不调 writeFinalAnswer；</li>
 *   <li><b>非 budget 失败不受影响</b>：普通 failure（无 budget_exceeded）→ 走原有 failure() 路径，不发 budget 事件；</li>
 *   <li><b>长度上限</b>：12 个 todo × 2K chars → finalAnswer 总长 ≤ 8K + truncation 提示。</li>
 * </ul>
 * <p>
 * 测试用 {@code executor.executePlanned(request, plan)} 跳过 planner，预构建 todo items + mocked
 * {@link LangchainTodoNodeExecutor}（按 todo_id 顺序返回 success / failure-with-budgetMetadata）。
 */
class LangchainLinearWorkflowExecutorBudgetExhaustionTest {

    private AgentRunEventService eventService;
    private LangchainTodoNodeExecutor todoNodeExecutor;
    private LangchainLinearWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        eventService = mock(AgentRunEventService.class);
        todoNodeExecutor = mock(LangchainTodoNodeExecutor.class);
        AgentContext.setRunId("run-test");
        AgentContext.setUserId("user-test");
        executor = new LangchainLinearWorkflowExecutor(
                todoNodeExecutor,
                noopExecutionGuard(),
                eventService);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void budgetExceeded_withCompletedTodos_shouldEmitPartialBudgetEventAndSkipFinalAnswer() {
        Map<String, Object> budgetMeta = budgetMetadata("tool_calls", 30L, 30L);
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_1"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("完成查询", 1));
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_2"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.failure("simulated", budgetMeta));

        LangchainTodoPlan plan = planWithItems(item("todo_1", 1), item("todo_2", 2));
        LangchainWorkflowResult result = executor.executePlanned(
                request("run-budget-partial", "user-1"), plan);

        // success=false + partial=true + finalAnswer 来自 completed todos（不调 LLM）
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isTrue();
        assertThat(result.getFailureReason()).contains("RUN_BUDGET_EXCEEDED:tool_calls:30/30");
        assertThat(result.getFinalAnswer()).contains("【todo_1】").contains("完成查询");
        assertThat(result.getCompletedTodos()).hasSize(1);
        assertThat(result.getFailureMetadata()).containsEntry("budget_exceeded", true);
        assertThat(result.getFailureMetadata()).containsEntry("dimension", "tool_calls");

        // 验证 WORKFLOW_PARTIAL_BUDGET 事件被发出
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(eq("run-budget-partial"), eq("user-1"),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).contains("WORKFLOW_PARTIAL_BUDGET");
        assertThat(eventTypeCaptor.getAllValues()).contains("TODO_NODE_FAILED");

        // MF1: TODO_NODE_FAILED event payload 含 budget_failure 字段，**不**含 empty_output_observation
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService, atLeastOnce()).append(eq("run-budget-partial"), eq("user-1"),
                eq("TODO_NODE_FAILED"), payloadCaptor.capture());
        Map<String, Object> failedPayload = payloadCaptor.getValue();
        assertThat(failedPayload).containsKey("budget_failure");
        assertThat(failedPayload).doesNotContainKey("empty_output_observation");
        @SuppressWarnings("unchecked")
        Map<String, Object> budgetField = (Map<String, Object>) failedPayload.get("budget_failure");
        assertThat(budgetField).containsEntry("budget_exceeded", true);
    }

    @Test
    void budgetExceeded_withNoCompletedTodos_shouldEmitFailedBudgetEventAndSkipFinalAnswer() {
        Map<String, Object> budgetMeta = budgetMetadata("tokens", 300000L, 300000L);
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_1"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.failure("simulated", budgetMeta));

        LangchainTodoPlan plan = planWithItems(item("todo_1", 1));
        LangchainWorkflowResult result = executor.executePlanned(
                request("run-budget-failfast", "user-1"), plan);

        // success=false + partial=false（无任何完成 todo）+ no finalAnswer
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isFalse();
        assertThat(result.getFinalAnswer()).isNull();
        assertThat(result.getFailureReason()).contains("RUN_BUDGET_EXCEEDED:tokens:300000/300000")
                .contains("no completed todo");
        assertThat(result.getCompletedTodos()).isEmpty();
        assertThat(result.getFailureMetadata()).containsEntry("budget_exceeded", true);
        assertThat(result.getFailureMetadata()).containsEntry("dimension", "tokens");

        // 验证 WORKFLOW_FAILED_BUDGET 事件被发出
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(eq("run-budget-failfast"), eq("user-1"),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).contains("WORKFLOW_FAILED_BUDGET");
        assertThat(eventTypeCaptor.getAllValues()).contains("TODO_NODE_FAILED");

        // MF1: TODO_NODE_FAILED event payload 含 budget_failure，**不**含 empty_output_observation
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService, atLeastOnce()).append(eq("run-budget-failfast"), eq("user-1"),
                eq("TODO_NODE_FAILED"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsKey("budget_failure");
        assertThat(payloadCaptor.getValue()).doesNotContainKey("empty_output_observation");
    }

    @Test
    void nonBudgetFailure_shouldUseNormalFailurePathAndNotEmitBudgetEvent() {
        Map<String, Object> emptyMeta = Map.of("empty_todo_output", true);
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_1"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("完成查询", 1));
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_2"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.failure("simulated", emptyMeta));

        LangchainTodoPlan plan = planWithItems(item("todo_1", 1), item("todo_2", 2));
        LangchainWorkflowResult result = executor.executePlanned(
                request("run-normal-fail", "user-1"), plan);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isFalse();
        assertThat(result.getFinalAnswer()).isNull();
        assertThat(result.getFailureReason()).doesNotContain("RUN_BUDGET_EXCEEDED");
        assertThat(result.getCompletedTodos()).hasSize(1);

        // 不应触发 budget 事件
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(any(), any(),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("WORKFLOW_PARTIAL_BUDGET");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("WORKFLOW_FAILED_BUDGET");
    }

    @Test
    void budgetExceeded_partialAnswer_shouldContainCompletedTodoContent() {
        // 线性 executor 在第一个失败节点即返回，所以 completedTodos 数量由 plan 中 budget 失败前的 success 节点数决定。
        // 这里设 3 个 success + 1 个 budget failure → completedTodos 应该有 3 项，finalAnswer 含全部 3 项。
        Map<String, Object> budgetMeta = budgetMetadata("tool_calls", 30L, 30L);
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_1"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("第一段查询结果", 1));
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_2"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("第二段分析结果", 1));
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_3"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("第三段汇总结果", 1));
        when(todoNodeExecutor.execute(any(), argThatItemId("todo_4"), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.failure("simulated", budgetMeta));

        LangchainTodoPlan plan = planWithItems(
                item("todo_1", 1), item("todo_2", 2), item("todo_3", 3), item("todo_4", 4));
        LangchainWorkflowResult result = executor.executePlanned(
                request("run-budget-multi", "user-1"), plan);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isTrue();
        assertThat(result.getFinalAnswer()).isNotNull();
        // partial answer 含全部 3 个 completed todo 的内容
        assertThat(result.getFinalAnswer())
                .contains("【todo_1】").contains("第一段查询结果")
                .contains("【todo_2】").contains("第二段分析结果")
                .contains("【todo_3】").contains("第三段汇总结果");
        // 长度在合理范围内（每个 todo 不超 MAX_PER_TODO_CHARS=4096 + 头）
        assertThat(result.getFinalAnswer().length()).isLessThan(4096 * 3 + 200);
        // completedTodos 列表正确
        assertThat(result.getCompletedTodos()).hasSize(3);
        // WORKFLOW_PARTIAL_BUDGET 事件 payload 含 final_answer 字段
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService, atLeastOnce()).append(eq("run-budget-multi"), eq("user-1"),
                eq("WORKFLOW_PARTIAL_BUDGET"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("final_answer_included_todo_count", 3);
        assertThat(payloadCaptor.getValue()).containsEntry("dimension", "tool_calls");
        assertThat(payloadCaptor.getValue()).containsEntry("actual", 30L);
        assertThat(payloadCaptor.getValue()).containsEntry("limit", 30L);
    }

    // ========== 辅助 ==========

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        return guard;
    }

    private static TodoItem item(String id, int sequence) {
        return TodoItem.builder().id(id).sequence(sequence).description("desc-" + id).build();
    }

    private static LangchainTodoPlan planWithItems(TodoItem... items) {
        return LangchainTodoPlan.builder()
                .analysis("test")
                .items(new ArrayList<>(List.of(items)))
                .extractedEntities(new ArrayList<>())
                .build();
    }

    private static LangchainWorkflowRequest request(String runId, String userId) {
        return LangchainWorkflowRequest.builder()
                .runId(runId)
                .userId(userId)
                .userGoal("test goal")
                .model(mock(ChatModel.class))
                .maxTodos(15)
                .build();
    }

    private static Map<String, Object> budgetMetadata(String dimension, long actual, long limit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("budget_exceeded", true);
        meta.put("dimension", dimension);
        meta.put("actual", actual);
        meta.put("limit", limit);
        meta.put("ratio", limit > 0 ? ((double) actual) / limit : 0.0);
        meta.put("partial", false);
        return meta;
    }

    private static TodoItem argThatItemId(String id) {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && id.equals(t.getId()));
    }
}
