package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredPlanningSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validateTodoPlan_shouldAcceptCanonicalShape() throws Exception {
        var root = objectMapper.readTree("""
                {
                  "analysis":"顺序执行",
                  "items":[{"id":"todo_1","sequence":1,"description":"查询数据"}]
                }
                """);

        assertThat(StructuredPlanningSupport.validateTodoPlan(root, 5).valid()).isTrue();
    }

    @Test
    void validateTodoPlan_shouldRejectMissingRequiredFields() throws Exception {
        var root = objectMapper.readTree("""
                {"analysis":"顺序执行","items":[{"sequence":1,"description":"查询数据"}]}
                """);

        var result = StructuredPlanningSupport.validateTodoPlan(root, 5);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("todo_item_missing_id@0");
    }

    @Test
    void validateTodoPlan_shouldRejectTooManyItems() throws Exception {
        var root = objectMapper.readTree("""
                {
                  "analysis":"顺序执行",
                  "items":[
                    {"id":"todo_1","sequence":1,"description":"查询"},
                    {"id":"todo_2","sequence":2,"description":"汇总"}
                  ]
                }
                """);

        var result = StructuredPlanningSupport.validateTodoPlan(root, 1);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("todo_plan_items_exceed_max");
    }
}
