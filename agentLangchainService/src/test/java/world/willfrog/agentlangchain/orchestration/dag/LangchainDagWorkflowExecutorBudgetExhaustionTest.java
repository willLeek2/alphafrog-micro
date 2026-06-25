package world.willfrog.agentlangchain.orchestration.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowRequest;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowResult;
import world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ccmax Phase 3.2 A3: DAG 端 budget 触顶降级路径测试。
 * <p>
 * 覆盖 4 个场景：
 * <ul>
 *   <li><b>G1 bypass recovery judge</b>：节点失败 + failureMetadata.budget_exceeded=true → 不调用 recovery judge；</li>
 *   <li><b>G2 partial 路径</b>：completedTodos 非空 → deterministic partial answer + WORKFLOW_PARTIAL_BUDGET；</li>
 *   <li><b>G3 fail-fast 路径</b>：completedTodos 空 → 无 finalAnswer + WORKFLOW_FAILED_BUDGET；</li>
 *   <li><b>non-LLM</b>：writeFinalAnswer 不被调用（budget 已触顶，再触发 LLM 会再抛 RunBudgetException）。</li>
 * </ul>
 */
class LangchainDagWorkflowExecutorBudgetExhaustionTest {

    private AgentEventService eventService;
    private LangchainTodoNodeExecutor nodeExecutor;
    private LangchainDagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-test");
        AgentContext.setUserId("user-test");
        eventService = mock(AgentEventService.class);
        nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainDagStateRecorder stateRecorder = mock(LangchainDagStateRecorder.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> emptyProvider =
                new EmptyStateStoreProvider();
        executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                stateRecorder,
                eventService,
                guard,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new ObjectMapper(),
                emptyProvider);
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 2);
        // 关闭 recovery judge：测试 G1 时不依赖 switch，默认 false 即可；显式 set 一下明确意图
        ReflectionTestUtils.setField(executor, "recoveryJudgeEnabled", false);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void budgetExceeded_withCompletedTodos_shouldEmitPartialBudgetEventAndBypassRecoveryJudge() {
        // DAG：t1 success + t2 budget failure (depends on t1) → t3 skipped
        Map<String, Object> budgetMeta = budgetMetadata("tool_calls", 30L, 30L);
        when(nodeExecutor.execute(any(), argThatItemId("t1"), any(), any(), any(AtomicInteger.class)))
                .thenReturn(LangchainTodoNodeResult.success("t1 数据", 1));
        when(nodeExecutor.execute(any(), argThatItemId("t2"), any(), any(), any(AtomicInteger.class)))
                .thenReturn(LangchainTodoNodeResult.failure("budget hit", budgetMeta));

        LangchainTodoPlan plan = planWithItems(
                item("t1", 1, List.of()),
                item("t2", 2, List.of("t1")),
                item("t3", 3, List.of("t2")));

        LangchainLinearWorkflowResult result = executor.executePlanned(
                request("run-dag-budget-partial", "user-1"), plan);

        // partial = true，finalAnswer 来自 t1（completedTodos 唯一项），不调 writeFinalAnswer
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isTrue();
        assertThat(result.getFinalAnswer()).contains("【t1】").contains("t1 数据");
        assertThat(result.getFailureReason()).contains("RUN_BUDGET_EXCEEDED:tool_calls:30/30");
        assertThat(result.getFailureMetadata()).containsEntry("budget_exceeded", true);
        assertThat(result.getFailureMetadata()).containsEntry("dimension", "tool_calls");

        // G1: recovery judge 没被调用（promptService 不应被触发 dagRecoveryJudgeSystemPrompt）
        verify(mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                never()).dagRecoveryJudgeSystemPrompt();

        // WORKFLOW_PARTIAL_BUDGET 事件发出
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(eq("run-dag-budget-partial"), eq("user-1"),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).contains("WORKFLOW_PARTIAL_BUDGET");

        // M3: writeFinalAnswer 不被调用（budget 触顶，再调 LLM 会立即再被 budget check 拦截）
        verify(nodeExecutor, never()).writeFinalAnswer(any(), any());
    }

    @Test
    void budgetExceeded_withNoCompletedTodos_shouldEmitFailedBudgetEventAndSkipFinalAnswer() {
        // DAG：t1 budget failure（无依赖，立即失败） → t2 skipped, t3 skipped
        Map<String, Object> budgetMeta = budgetMetadata("tokens", 300000L, 300000L);
        when(nodeExecutor.execute(any(), argThatItemId("t1"), any(), any(), any(AtomicInteger.class)))
                .thenReturn(LangchainTodoNodeResult.failure("budget hit", budgetMeta));

        LangchainTodoPlan plan = planWithItems(
                item("t1", 1, List.of()),
                item("t2", 2, List.of("t1")),
                item("t3", 3, List.of("t2")));

        LangchainLinearWorkflowResult result = executor.executePlanned(
                request("run-dag-budget-failfast", "user-1"), plan);

        // fail-fast: success=false, partial=false, no finalAnswer, completedTodos empty
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isFalse();
        assertThat(result.getFinalAnswer()).isNull();
        assertThat(result.getFailureReason()).contains("RUN_BUDGET_EXCEEDED:tokens:300000/300000")
                .contains("no completed todo");
        assertThat(result.getCompletedTodos()).isEmpty();

        // WORKFLOW_FAILED_BUDGET 事件
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(eq("run-dag-budget-failfast"), eq("user-1"),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).contains("WORKFLOW_FAILED_BUDGET");

        // writeFinalAnswer 不被调用
        verify(nodeExecutor, never()).writeFinalAnswer(any(), any());
    }

    @Test
    void budgetExceeded_nonBudgetFailure_shouldStillTriggerNormalFailurePath() {
        // 验证：未带 budget_exceeded 的失败仍走原有 failure 路径（不发 budget 事件）
        Map<String, Object> emptyMeta = Map.of("empty_todo_output", true);
        when(nodeExecutor.execute(any(), argThatItemId("t1"), any(), any(), any(AtomicInteger.class)))
                .thenReturn(LangchainTodoNodeResult.failure("empty", emptyMeta));

        LangchainTodoPlan plan = planWithItems(item("t1", 1, List.of()));
        LangchainLinearWorkflowResult result = executor.executePlanned(
                request("run-dag-normal-fail", "user-1"), plan);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPartial()).isFalse();
        assertThat(result.getFinalAnswer()).isNull();
        assertThat(result.getFailureReason()).doesNotContain("RUN_BUDGET_EXCEEDED");

        // 不发 budget 事件
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, atLeastOnce()).append(any(), any(),
                eventTypeCaptor.capture(), any(Map.class));
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("WORKFLOW_PARTIAL_BUDGET");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("WORKFLOW_FAILED_BUDGET");
    }

    @Test
    void failureOverload_shouldPropagateFailureMetadataThroughResult() {
        // G2: 验证 5 参 failure() overload 能正确把 failureMetadata 写入 result（不在 budget path 中，单独测）
        // 这里通过 reflection 直接调用 overload
        TodoItem item = item("t1", 1, List.of());
        Map<String, Object> meta = Map.of("custom_key", "custom_value");
        LangchainTodoPlan plan = planWithItems(item);

        LangchainLinearWorkflowResult result = (LangchainLinearWorkflowResult) ReflectionTestUtils.invokeMethod(
                executor, "failure",
                plan, List.of(), "test_reason", 0, meta);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("test_reason");
        assertThat(result.getFailureMetadata()).containsEntry("custom_key", "custom_value");
    }

    // ========== 辅助 ==========

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

    private static TodoItem item(String id, int sequence, List<String> dependsOn) {
        return TodoItem.builder()
                .id(id)
                .sequence(sequence)
                .description("desc-" + id)
                .dependsOn(dependsOn)
                .build();
    }

    private static LangchainTodoPlan planWithItems(TodoItem... items) {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(items))
                .extractedEntities(List.of())
                .build();
    }

    private static LangchainLinearWorkflowRequest request(String runId, String userId) {
        return LangchainLinearWorkflowRequest.builder()
                .runId(runId)
                .userId(userId)
                .userGoal("test goal")
                .executionModel(mock(ChatModel.class))
                .finalAnswerModel(mock(ChatModel.class))
                .build();
    }

    private static TodoItem argThatItemId(String id) {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && id.equals(t.getId()));
    }

    /**
     * 给 LangchainDagWorkflowExecutor 的 stateStoreProvider 提供一个空 ObjectProvider，
     * 避免它真的去 Spring 容器里找 AgentRunStateStore。
     */
    private static class EmptyStateStoreProvider
            implements ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> {
        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject() { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject(Object... args) { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfAvailable() { return null; }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfUnique() { return null; }
    }
}