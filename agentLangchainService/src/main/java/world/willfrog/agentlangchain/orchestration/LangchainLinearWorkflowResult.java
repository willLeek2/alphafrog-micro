package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;

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
    /** DAG recovery judge 的 LLM 调用 traceId（仅 PARTIAL 时有值）。 */
    private String recoveryJudgeTraceId;
    /** DAG recovery judge 的判定理由，截断到 500 字符（仅 PARTIAL 时有值）。 */
    private String recoveryRationale;
}
