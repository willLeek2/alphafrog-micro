package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class LangchainLinearWorkflowResult {
    private boolean success;
    /** DAG recovery judge 判定为部分完成。仅 PARTIAL 终态下为 true。 */
    private boolean partial;
    /** True when canceled/paused mid-flight; pipeline must not overwrite control terminal state. */
    private boolean interrupted;
    private String failureReason;
    private String finalAnswer;
    private LangchainTodoPlan plan;
    @Builder.Default
    private List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
    private int toolCallsUsed;
    /** DAG recovery judge 返回的跳过节点 ID 列表（仅 PARTIAL 时有值）。 */
    private List<String> skippedTodoIds;
    /** DAG recovery judge 的决策 ID（非真实 LLM trace；用于关联 SKIPPED 事件）。
     *  实际 LLM trace 在 observability 中通过 phase=dag_recovery_judge 过滤定位。 */
    private String recoveryJudgeDecisionId;
    /** DAG recovery judge 的判定理由，截断到 500 字符（仅 PARTIAL 时有值）。 */
    private String recoveryRationale;
    /**
     * 失败时的结构化观测数据（透传自首个失败 todo 的 failureMetadata）。
     * 仅在 {@link #failureReason} 为 empty_todo_output / empty_todo_output_after_recovery 时由 linear executor 填入。
     * pipeline 层 publishFailure 会把它写入 WORKFLOW_FAILED event payload 的 empty_output_observation 子 map。
     */
    private Map<String, Object> failureMetadata;
}
