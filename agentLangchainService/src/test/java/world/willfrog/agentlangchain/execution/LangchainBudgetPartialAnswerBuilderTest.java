package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ccmax Phase 3.2 A3 G3: 确定性 partial answer builder 的长度上限测试。
 * <p>
 * 5 个场景：
 * <ul>
 *   <li>空列表 → 空字符串 + 全零统计；</li>
 *   <li>3 个 short todo → 直接拼接，不截断；</li>
 *   <li>单 todo 长度超 MAX_PER_TODO_CHARS → 单 todo 内截断；</li>
 *   <li>10 个 todo × 2000 chars → 总长 > 8K → 加 truncation 提示；</li>
 *   <li>20 个 todo → 只取前 MAX_TODOS=5 个；</li>
 *   <li>completedTodoIdsPayload 透传 todoId 列表 + count。</li>
 * </ul>
 */
class LangchainBudgetPartialAnswerBuilderTest {

    @Test
    void build_emptyList_shouldReturnEmptyStringAndZeroStats() {
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(List.of());

        assertThat(result.finalAnswer()).isEmpty();
        assertThat(result.finalAnswerLength()).isZero();
        assertThat(result.includedTodoCount()).isZero();
        assertThat(result.skippedTodoCount()).isZero();
        assertThat(result.originalTotalLength()).isZero();
    }

    @Test
    void build_threeShortTodos_shouldConcatenateWithoutTruncation() {
        List<LangchainCompletedTodo> todos = List.of(
                completedTodo("todo_1", 1, "第一段查询"),
                completedTodo("todo_2", 2, "第二段分析"),
                completedTodo("todo_3", 3, "第三段汇总")
        );
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(todos);

        assertThat(result.finalAnswer())
                .contains("【todo_1】").contains("第一段查询")
                .contains("【todo_2】").contains("第二段分析")
                .contains("【todo_3】").contains("第三段汇总");
        assertThat(result.includedTodoCount()).isEqualTo(3);
        assertThat(result.skippedTodoCount()).isZero();
        assertThat(result.finalAnswer()).doesNotContain("[... truncated,");
    }

    @Test
    void build_singleTodoExceedsMaxPerTodo_shouldTruncateAt4096() {
        String longOutput = "x".repeat(LangchainBudgetPartialAnswerBuilder.MAX_PER_TODO_CHARS + 1000);
        List<LangchainCompletedTodo> todos = List.of(
                completedTodo("todo_long", 1, longOutput)
        );
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(todos);

        // 单 todo 内 truncation: finalAnswer 总长 < longOutput.length
        assertThat(result.finalAnswer().length()).isLessThan(longOutput.length());
        // 仍然保留头部的 【todo_long】\n
        assertThat(result.finalAnswer()).startsWith("【todo_long】\n");
        assertThat(result.includedTodoCount()).isEqualTo(1);
        assertThat(result.originalTotalLength()).isEqualTo(longOutput.length());
    }

    @Test
    void build_tenTodosExceedMaxTotal_shouldTruncateAt8192AndAppendTruncationNote() {
        // 10 个 todo × 2000 chars = 20000 chars（output 总和）> 8192 → 应触发 truncation
        // 实际拼接 5 个 included todo × (2000 + ~13 头/分隔) ≈ 10065 chars > 8192 → truncation
        List<LangchainCompletedTodo> todos = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            todos.add(completedTodo("todo_" + i, i, "y".repeat(2000)));
        }
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(todos);

        // 总长 ≤ 8192 + truncation note
        assertThat(result.finalAnswer().length())
                .isLessThanOrEqualTo(LangchainBudgetPartialAnswerBuilder.MAX_TOTAL_CHARS + 300);
        // 含截断提示
        assertThat(result.finalAnswer()).contains("[... truncated,");
        assertThat(result.finalAnswer()).containsPattern("full length \\d+ chars");
        // includedTodoCount = 5（MAX_TODOS）
        assertThat(result.includedTodoCount()).isEqualTo(5);
        // skippedTodoCount = 5（剩余 5 个被省略）
        assertThat(result.skippedTodoCount()).isEqualTo(5);
        // originalTotalLength = 10 todos × 2000 = 20000
        assertThat(result.originalTotalLength()).isEqualTo(20000L);
    }

    @Test
    void build_twentyTodos_shouldOnlyIncludeFirstFive() {
        List<LangchainCompletedTodo> todos = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            todos.add(completedTodo("todo_" + i, i, "short output " + i));
        }
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(todos);

        // 只保留前 5 个
        assertThat(result.includedTodoCount()).isEqualTo(5);
        assertThat(result.skippedTodoCount()).isEqualTo(15);
        assertThat(result.finalAnswer()).contains("【todo_1】")
                .contains("【todo_5】")
                .doesNotContain("【todo_6】");
    }

    @Test
    void build_todosWithNullOrBlankOutput_shouldBeSkippedButCounted() {
        LangchainCompletedTodo nullOutput = LangchainCompletedTodo.builder()
                .todoId("todo_blank").sequence(1).description("blank").output(null).summary("").build();
        List<LangchainCompletedTodo> todos = List.of(
                completedTodo("todo_1", 1, "valid output"),
                nullOutput,
                completedTodo("todo_2", 2, "another valid")
        );
        LangchainBudgetPartialAnswerBuilder.PartialAnswer result =
                LangchainBudgetPartialAnswerBuilder.build(todos);

        // 只含 2 个有效 todo；blank 不计入 includedTodoCount
        assertThat(result.includedTodoCount()).isEqualTo(2);
        assertThat(result.finalAnswer())
                .contains("【todo_1】").contains("valid output")
                .contains("【todo_2】").contains("another valid");
    }

    @Test
    void completedTodoIdsPayload_shouldIncludeAllTodoIds() {
        List<LangchainCompletedTodo> todos = List.of(
                completedTodo("todo_1", 1, "a"),
                completedTodo("todo_2", 2, "b"),
                completedTodo("todo_3", 3, "c")
        );
        Map<String, Object> payload = LangchainBudgetPartialAnswerBuilder.completedTodoIdsPayload(todos);

        assertThat(payload).containsEntry("completed_todo_count", 3);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("completed_todo_ids");
        assertThat(ids).containsExactly("todo_1", "todo_2", "todo_3");
    }

    @Test
    void completedTodoIdsPayload_nullList_shouldReturnEmpty() {
        Map<String, Object> payload = LangchainBudgetPartialAnswerBuilder.completedTodoIdsPayload(null);
        assertThat(payload).containsEntry("completed_todo_count", 0);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("completed_todo_ids");
        assertThat(ids).isEmpty();
    }

    private static LangchainCompletedTodo completedTodo(String id, int sequence, String output) {
        return LangchainCompletedTodo.builder()
                .todoId(id)
                .sequence(sequence)
                .description("desc-" + id)
                .output(output)
                .summary(output)
                .build();
    }
}