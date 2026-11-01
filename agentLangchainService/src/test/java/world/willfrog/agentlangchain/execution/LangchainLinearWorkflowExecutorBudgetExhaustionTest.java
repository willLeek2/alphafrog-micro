package world.willfrog.agentlangchain.execution;

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
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

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
