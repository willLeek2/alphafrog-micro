package world.willfrog.agentlangchain.execution;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次工作流执行的完整返回值，也是 pipeline 判断“结束”还是“暂时让出线程”的边界对象。
 *
 * <p>LINEAR 和 DAG 执行器都返回这个类型。名字不再带 Linear。</p>
 *
 * <p>{@link #suspended} 为 {@code true} 时，本对象绝不能被解释成业务失败：此时外部工具仍在其他
 * 进程中执行，pipeline 只负责把下面的 todo 坐标、工具调用身份和已完成上下文写入 durable anchor，
 * 然后正常返回调度器。调度器释放当前 Agent worker；工具结果到达后，resume 链再从这些字段指向的
 * 原始 todo 继续执行，而不是重新规划整个 Run。</p>
 */
@Data
@Builder
public class LangchainWorkflowResult {
    /** 正常跑到工作流终点且已得到最终回答；挂起时固定为 false。 */
    private boolean success;
    /** 恢复判定器判定为部分完成。仅 PARTIAL 终态下为 true。 */
    private boolean partial;
    /** 用户取消或暂停导致的协作式中断；pipeline 不得用自己的返回状态覆盖控制面终态。 */
    private boolean interrupted;
    /** 外部工具仍在运行；pipeline 必须保存检查点并保留数据库中的 WAITING_TOOL_JOB。 */
    private boolean suspended;
    private String failureReason;
    private String finalAnswer;
    private LangchainTodoPlan plan;
    @Builder.Default
    private List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
    private int toolCallsUsed;
    /** 恢复判定器返回的跳过节点 ID 列表（仅 PARTIAL 时有值）。 */
    private List<String> skippedTodoIds;
    /** 恢复判定器的决策 ID（不是真实模型调用追踪号；用于关联 SKIPPED 事件）。
     *  实际模型调用追踪在观测数据里用 phase=dag_recovery_judge 过滤定位。 */
    private String recoveryJudgeDecisionId;
    /** 恢复判定器的判定理由，截断到 500 字符（仅 PARTIAL 时有值）。 */
    private String recoveryRationale;
    /**
     * 失败时的结构化观测数据（透传自首个失败 todo 的 failureMetadata）。
     * 仅在 {@link #failureReason} 为 empty_todo_output / empty_todo_output_after_recovery 时由 linear executor 填入。
     * pipeline 层 publishFailure 会按 {@link LangchainTodoNodeResult#routeFailureMetadataField} 语义
     * 把它路由到 WORKFLOW_FAILED event payload 的 {@code budget_failure} / {@code empty_output_observation} / {@code failure_metadata} 之一。
     */
    private Map<String, Object> failureMetadata;
    /** 被慢工具挂起的原始 todo ID；恢复时用它定位要注入 terminal result 的节点。 */
    private String suspendedTodoId;
    /** 被挂起 todo 在原计划中的序号；与 todo ID 一起防止恢复到错误节点。 */
    private Integer suspendedTodoSequence;
    /** pending 异常携带的 Run ID；检查点落库前必须与当前 pipeline Run 一致。 */
    private String pendingRunId;
    /** 本次异步工具调用 ID；最终结果、anchor 和恢复上下文都用它关联同一调用。 */
    private String pendingToolCallId;
    /** 同一 toolCallId 的尝试次数；用于拒绝旧 attempt 的迟到结果。 */
    private int pendingAttempt;
}
