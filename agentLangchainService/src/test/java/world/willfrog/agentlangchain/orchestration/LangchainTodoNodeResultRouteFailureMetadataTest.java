package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ccmax Phase 3.2 A3: failureMetadata 路由测试。
 * <p>
 * Phase 3.2 A3 MF1: budget failureMetadata 不能继续写到 {@code empty_output_observation} 字段，
 * 需要按语义路由到 {@code budget_failure} / {@code empty_output_observation} / {@code failure_metadata} 之一。
 * <p>
 * 覆盖 6 个场景：
 * <ul>
 *   <li>空 map → null（不应写入任何字段）；</li>
 *   <li>budget_exceeded=true → budget_failure；</li>
 *   <li>14 个 #59 字段中任一 → empty_output_observation（#59 兼容）；</li>
 *   <li>explicit empty_todo_output key → empty_output_observation；</li>
 *   <li>其他 generic metadata → failure_metadata；</li>
 *   <li>priority：budget_exceeded 比 #59 字段优先级高（避免空 output 误入 budget）</li>
 * </ul>
 */
class LangchainTodoNodeResultRouteFailureMetadataTest {

    @Test
    void routeFailureMetadataField_nullOrEmpty_shouldReturnNull() {
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(null)).isNull();
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(Map.of())).isNull();
    }

    @Test
    void routeFailureMetadataField_budgetExceededTrue_shouldReturnBudgetFailureField() {
        Map<String, Object> meta = budgetMetadata();
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                .isEqualTo(LangchainTodoNodeResult.BUDGET_FAILURE_FIELD)
                .isEqualTo("budget_failure");
    }

    @Test
    void routeFailureMetadataField_budgetExceededFalse_shouldNotBeBudget() {
        // budget_exceeded=false 时不归 budget（虽然是 budget 相关 metric，但语义上未超限）
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("budget_exceeded", false);
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                .isEqualTo(LangchainTodoNodeResult.FAILURE_METADATA_FIELD);
    }

    @Test
    void routeFailureMetadataField_59Fields_shouldReturnEmptyOutputObservationField() {
        // 14 个 #59 字段中任一 → empty_output_observation
        String[] fields = {
                "finish_reason", "raw_output_length", "trimmed_output_length",
                "recovery_attempted", "recovery_outcome", "budget_hit",
                "last_non_empty_todo_id", "previous_todo_total_length",
                "current_todo_prompt_budget_chars"
        };
        for (String field : fields) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(field, "any_value");
            assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                    .as("Field %s should route to empty_output_observation", field)
                    .isEqualTo(LangchainTodoNodeResult.EMPTY_OUTPUT_OBSERVATION_FIELD)
                    .isEqualTo("empty_output_observation");
        }
    }

    @Test
    void routeFailureMetadataField_explicitEmptyTodoOutputKey_shouldReturnEmptyOutputObservationField() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("empty_todo_output", true);
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                .isEqualTo(LangchainTodoNodeResult.EMPTY_OUTPUT_OBSERVATION_FIELD);
    }

    @Test
    void routeFailureMetadataField_genericMetadata_shouldReturnFailureMetadataField() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("custom_key", "custom_value");
        meta.put("another_key", 42);
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                .isEqualTo(LangchainTodoNodeResult.FAILURE_METADATA_FIELD)
                .isEqualTo("failure_metadata");
    }

    @Test
    void routeFailureMetadataField_budgetPriority_shouldOverrideEmptyOutputFields() {
        // 同时含 budget_exceeded=true 和 #59 字段 → budget 优先（防止空 output 误入 budget 误读）
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("budget_exceeded", true);
        meta.put("finish_reason", "blank_after_trim");
        meta.put("raw_output_length", 0);
        assertThat(LangchainTodoNodeResult.routeFailureMetadataField(meta))
                .isEqualTo(LangchainTodoNodeResult.BUDGET_FAILURE_FIELD);
    }

    private static Map<String, Object> budgetMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("budget_exceeded", true);
        meta.put("dimension", "tool_calls");
        meta.put("actual", 30L);
        meta.put("limit", 30L);
        meta.put("ratio", 1.0);
        meta.put("partial", false);
        return meta;
    }
}