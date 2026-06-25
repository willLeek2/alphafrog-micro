package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agentlangchain.orchestration.LangchainCompletedTodo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.2 A3 G3: 在 run 触发 100% budget 触顶时，从已完成 todo 的 output 拼出一个确定性的 partial answer，
 * 跳过 {@code writeFinalAnswer()} 的 LLM 调用（避免在 budget 已超限时再发请求触发硬截断 / 异常）。
 *
 * <h2>长度限制</h2>
 * <ul>
 *   <li>最多取前 {@link #MAX_TODOS} (5) 个 completed todo，每个 todo output 截断到 {@link #MAX_PER_TODO_CHARS} (4096) 字符</li>
 *   <li>拼接总长截断到 {@link #MAX_TOTAL_CHARS} (8192) 字符；超出时追加 truncation 提示</li>
 *   <li>原始 todo 数与总长度记入返回值 payload，供 observability / TUI 显示</li>
 * </ul>
 *
 * <h2>设计理由</h2>
 * <ul>
 *   <li>不能用 LLM 写 finalAnswer：budget 已经触顶，再发 LLM 调用会立即再触发 RunBudgetException；</li>
 *   <li>需要限长：避免把超大的工具输出塞进 WORKFLOW_PARTIAL_BUDGET event payload / snapshot；</li>
 *   <li>截断提示保留可追溯性：用户能看到"还有 N 个 todo 未完成，全长 M 字符"。</li>
 * </ul>
 */
public final class LangchainBudgetPartialAnswerBuilder {

    /** deterministic partial answer 中最多保留几个 completed todo。 */
    public static final int MAX_TODOS = 5;

    /** 每个 todo output 截断到多少字符。 */
    public static final int MAX_PER_TODO_CHARS = 4096;

    /** 拼接后 finalAnswer 总长上限（chars）。 */
    public static final int MAX_TOTAL_CHARS = 8192;

    /**
     * 拼接结果：包含 deterministic finalAnswer + 长度统计字段。
     */
    public record PartialAnswer(
            String finalAnswer,
            int finalAnswerLength,
            int includedTodoCount,
            int skippedTodoCount,
            long originalTotalLength
    ) {
    }

    private LangchainBudgetPartialAnswerBuilder() {
    }

    /**
     * 从 completedTodos 拼 deterministic partial answer。空列表返回空字符串 + 全零统计。
     *
     * @param completedTodos 当前 run 已经成功完成的 todo（output 可能为 null/blank，会跳过）
     * @return 拼接结果；finalAnswer 已是 trimmed，可直接写入 LangchainLinearWorkflowResult.finalAnswer
     */
    public static PartialAnswer build(List<LangchainCompletedTodo> completedTodos) {
        List<LangchainCompletedTodo> safeTodos = completedTodos == null ? List.of() : completedTodos;
        long originalTotalLength = 0L;
        for (LangchainCompletedTodo t : safeTodos) {
            String out = t.displayOutput();
            if (out != null) {
                originalTotalLength += out.length();
            }
        }

        StringBuilder sb = new StringBuilder();
        int included = 0;
        for (int i = 0; i < safeTodos.size() && included < MAX_TODOS; i++) {
            LangchainCompletedTodo t = safeTodos.get(i);
            String out = t.displayOutput();
            if (out == null || out.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("【").append(nvl(t.getTodoId(), "todo_?")).append("】\n");
            sb.append(truncate(out, MAX_PER_TODO_CHARS));
            included++;
        }
        String full = sb.toString();
        int totalChars = full.length();
        int skippedTodoCount = Math.max(0, safeTodos.size() - included);
        int finalAnswerLength = totalChars;
        String finalAnswer = full;
        if (totalChars > MAX_TOTAL_CHARS) {
            String truncated = full.substring(0, MAX_TOTAL_CHARS);
            int omitted = totalChars - MAX_TOTAL_CHARS;
            finalAnswer = truncated
                    + "\n\n[... truncated, "
                    + skippedTodoCount + " todo(s) omitted, "
                    + included + " todo(s) included, "
                    + "full length " + totalChars + " chars, "
                    + "truncated " + omitted + " chars ...]";
            finalAnswerLength = finalAnswer.length();
        }
        return new PartialAnswer(finalAnswer, finalAnswerLength, included, skippedTodoCount, originalTotalLength);
    }

    /**
     * 构造事件 payload 通用字段（completedTodoIds + summary），由 LINEAR/DAG executor 在发
     * WORKFLOW_PARTIAL_BUDGET / WORKFLOW_FAILED_BUDGET 时复用。
     */
    public static Map<String, Object> completedTodoIdsPayload(List<LangchainCompletedTodo> completedTodos) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        if (completedTodos != null) {
            for (LangchainCompletedTodo t : completedTodos) {
                if (t.getTodoId() != null) {
                    ids.add(t.getTodoId());
                }
            }
        }
        out.put("completed_todo_ids", ids);
        out.put("completed_todo_count", ids.size());
        return out;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}