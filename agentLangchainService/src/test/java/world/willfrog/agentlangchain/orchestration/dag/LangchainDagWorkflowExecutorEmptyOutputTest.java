package world.willfrog.agentlangchain.orchestration.dag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.TodoItem;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ccmax #59: DAG TODO_NODE_FAILED 事件 payload 在 empty_todo_output 场景下的结构化观测 + recovery 标记。
 *
 * <p>覆盖以下三种调用：
 * <ul>
 *   <li><b>失败 + observation</b>：调用 9 参 overload，failureMetadata 非空 → payload 含 empty_output_observation</li>
 *   <li><b>成功 + recovered</b>：success=true + recovered=true + recovery_outcome=success → payload 含 recovered=true + recovery_outcome</li>
 *   <li><b>5 参 overload delegation</b>：调用 5 参 overload（向后兼容）→ 走默认 9 参路径，failureMetadata=null，无 observation / recovered</li>
 * </ul>
 */
class LangchainDagWorkflowExecutorEmptyOutputTest {

    @Test
    void todoNodeResultPayload_shouldIncludeEmptyOutputObservationWhenFailureMetadataProvided() {
        LangchainDagWorkflowExecutor executor = newExecutor();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("todo_id", "todo_dag_1");
        observation.put("todo_sequence", 1);
        observation.put("stage", "todo_execution");
        observation.put("model", "OpenRouterProviderRoutedChatModel");
        observation.put("provider", "OpenRouterProviderRoutedChatModel");
        observation.put("finish_reason", "blank_after_trim");
        observation.put("raw_output_length", 5);
        observation.put("trimmed_output_length", 0);
        observation.put("budget_hit", false);
        observation.put("last_non_empty_todo_id", "todo_dag_0");
        observation.put("previous_todo_total_length", 100L);
        observation.put("current_todo_prompt_budget_chars", 300);
        observation.put("recovery_attempted", true);
        observation.put("recovery_outcome", "still_blank");

        TodoItem item = TodoItem.builder().id("todo_dag_1").sequence(1).description("分析指数").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor, "todoNodeResultPayload",
                item, false, "empty_todo_output_after_recovery:todo_dag_1", 1234L, 2,
                null, observation, true, "still_blank");

        // 关键字段
        assertThat(payload).containsEntry("todo_id", "todo_dag_1");
        assertThat(payload).containsEntry("todo_sequence", 1);
        assertThat(payload).containsEntry("success", false);
        assertThat(payload).containsEntry("duration_ms", 1234L);
        assertThat(payload).containsEntry("failure_reason", "empty_todo_output_after_recovery:todo_dag_1");
        // empty_output_observation 完整透传
        assertThat(payload).containsKey("empty_output_observation");
        @SuppressWarnings("unchecked")
        Map<String, Object> obsOut = (Map<String, Object>) payload.get("empty_output_observation");
        assertThat(obsOut).hasSize(14);
        assertThat(obsOut).containsEntry("finish_reason", "blank_after_trim");
        assertThat(obsOut).containsEntry("recovery_outcome", "still_blank");
        assertThat(obsOut).containsEntry("budget_hit", false);
        // DAG 成功才写 recovered，本次 success=false → recovered 字段不在 payload
        assertThat(payload).doesNotContainKey("recovered");
    }

    @Test
    void todoNodeResultPayload_shouldMarkRecoveredTrueWhenSuccessAfterRecovery() {
        LangchainDagWorkflowExecutor executor = newExecutor();
        TodoItem item = TodoItem.builder().id("todo_dag_2").sequence(1).description("分析").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor, "todoNodeResultPayload",
                item, true, "RECOVERED_OUTPUT", 800L, 1,
                null, null, true, "success");

        assertThat(payload).containsEntry("success", true);
        assertThat(payload).containsEntry("recovered", true);
        assertThat(payload).containsEntry("recovery_outcome", "success");
        // 成功路径不写 empty_output_observation
        assertThat(payload).doesNotContainKey("empty_output_observation");
        // 成功路径不写 failure_reason
        assertThat(payload).doesNotContainKey("failure_reason");
    }

    @Test
    void todoNodeResultPayload_fiveArgOverload_shouldNotIncludeObservationOrRecovered() {
        // 5 参 overload（向后兼容）：调用者不传 failureMetadata / recovered / recoveryOutcome → 默认走 9 参路径，null 字段
        LangchainDagWorkflowExecutor executor = newExecutor();
        TodoItem item = TodoItem.builder().id("todo_dag_3").sequence(1).description("普通失败").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor, "todoNodeResultPayload",
                item, false, "tool_error", 500L, 0);

        assertThat(payload).containsEntry("success", false);
        assertThat(payload).containsEntry("failure_reason", "tool_error");
        assertThat(payload).doesNotContainKey("empty_output_observation");
        assertThat(payload).doesNotContainKey("recovered");
        assertThat(payload).doesNotContainKey("recovery_outcome");
    }

    @Test
    void todoNodeResultPayload_shouldInferRunCanceledErrorCodeOnInterruptedFailure() {
        // MF 兼容测试：5 参 + 6 参 overload 行为对齐 — summary 含 "RUN_INTERRUPTED:CANCEL" 时自动写 error_code=RUN_CANCELED
        LangchainDagWorkflowExecutor executor = newExecutor();
        TodoItem item = TodoItem.builder().id("todo_dag_4").sequence(1).description("用户取消").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor, "todoNodeResultPayload",
                item, false, "RUN_INTERRUPTED:CANCEL", 200L, 0);

        assertThat(payload).containsEntry("error_code", "RUN_CANCELED");
        assertThat(payload).containsEntry("failure_reason", "RUN_INTERRUPTED:CANCEL");
    }

    // ========== 辅助 ==========

    private static LangchainDagWorkflowExecutor executor() {
        return newExecutor();
    }

    private static LangchainDagWorkflowExecutor newExecutor() {
        return new LangchainDagWorkflowExecutor(
                mock(world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor.class),
                mock(LangchainDagStateRecorder.class),
                mock(AgentRunEventService.class),
                mock(world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }
}
