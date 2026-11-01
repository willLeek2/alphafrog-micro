package world.willfrog.agentlangchain.execution.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
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
 *   <li><b>G1 bypass recovery judge</b>：节点失败 + failureMetadata.budget_exceeded=true
 *       → 即使 {@code recoveryJudgeEnabled=true}，recovery judge 也不被调用
 *       （验证注入的 promptService.dagRecoveryJudgeSystemPrompt() 永远 never）；</li>
 *   <li><b>G2 partial 路径</b>：completedTodos 非空 → deterministic partial answer + WORKFLOW_PARTIAL_BUDGET；</li>
 *   <li><b>G3 fail-fast 路径</b>：completedTodos 空 → 无 finalAnswer + WORKFLOW_FAILED_BUDGET；</li>
 *   <li><b>non-LLM</b>：writeFinalAnswer 不被调用（budget 已触顶，再触发 LLM 会再抛 RunBudgetException）。</li>
 * </ul>
 * <p>
 * 关键设计：{@code promptService} 作为字段保存（不内联 mock），让 {@code verify()} 能验证真正注入 executor 的 mock。
 * 所有测试统一开 {@code recoveryJudgeEnabled=true}，保证 G1 bypass 真的在带开关状态下被覆盖。
 */
class LangchainDagWorkflowExecutorBudgetExhaustionTest {

    private AgentRunEventService eventService;
    private LangchainTodoNodeExecutor nodeExecutor;
    private world.willfrog.agent.platform.service.AgentPromptService promptService;
    private LangchainDagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-test");
        AgentContext.setUserId("user-test");
        eventService = mock(AgentRunEventService.class);
        nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainDagStateRecorder stateRecorder = mock(LangchainDagStateRecorder.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> emptyProvider =
                new EmptyStateStoreProvider();
        promptService = mock(world.willfrog.agent.platform.service.AgentPromptService.class);
        executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                stateRecorder,
                eventService,
                guard,
                promptService,
                new ObjectMapper(),
                emptyProvider);
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 2);
        // 关键：开启 recovery judge 开关，让 G1 bypass 测试真正有"如果不 bypass 就会调"的反事实覆盖
        ReflectionTestUtils.setField(executor, "recoveryJudgeEnabled", true);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
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

    private static LangchainWorkflowRequest request(String runId, String userId) {
        return LangchainWorkflowRequest.builder()
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