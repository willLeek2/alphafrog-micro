package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;

import java.util.Map;

@Data
@Builder
public class LangchainTodoNodeResult {
    private boolean success;
    @Builder.Default
    private boolean suspended = false;
    private String output;
    private String summary;
    private String failureReason;
    @Builder.Default
    private int toolCallsUsed = 0;

    /**
     * 是否经过一次安全 recovery 后成功。压测报告可借此区分"一次空输出被恢复"与"完全没空输出"。
     * 仅在 {@link #success} 为 true 时有语义。
     */
    @Builder.Default
    private boolean recovered = false;

    /**
     * recovery 结果："success" / "still_blank" / "exception" / "not_attempted"。仅当走过 recovery 路径时有值。
     */
    private String recoveryOutcome;

    /**
     * 结构化观测数据。empty_todo_output 场景下填充，由 executor 在 {@code try} 块内构造、在 {@code finally} 清 ThreadLocal 之前写入。
     * 链路：LangchainTodoNodeResult → LangchainLinearWorkflowResult → publishFailure event payload
     * （按 {@link #routeFailureMetadataField} 语义路由到 {@code budget_failure} / {@code empty_output_observation} / {@code failure_metadata} 之一）。
     */
    private Map<String, Object> failureMetadata;
    private String pendingRunId;
    private String pendingToolCallId;
    private int pendingAttempt;

    public static LangchainTodoNodeResult success(String output, int toolCallsUsed) {
        return success(output, toolCallsUsed, false, null);
    }

    public static LangchainTodoNodeResult success(String output, int toolCallsUsed,
                                                  boolean recovered, String recoveryOutcome) {
        String trimmed = output == null ? "" : output.trim();
        return LangchainTodoNodeResult.builder()
                .success(true)
                .output(trimmed)
                .summary(trimmed)
                .toolCallsUsed(toolCallsUsed)
                .recovered(recovered)
                .recoveryOutcome(recoveryOutcome)
                .build();
    }

    public static LangchainTodoNodeResult failure(String reason) {
        return failure(reason, null);
    }

    public static LangchainTodoNodeResult failure(String reason, Map<String, Object> failureMetadata) {
        return LangchainTodoNodeResult.builder()
                .success(false)
                .failureReason(reason)
                .summary(reason)
                .output("")
                .failureMetadata(failureMetadata)
                .build();
    }

    public static LangchainTodoNodeResult skipped(String dependencyId) {
        String reason = "Skipped: dependency " + dependencyId + " failed";
        return LangchainTodoNodeResult.builder()
                .success(false)
                .failureReason(reason)
                .summary(reason)
                .output("")
                .build();
    }

    public static LangchainTodoNodeResult suspended(ExternalToolJobPendingException pending) {
        return LangchainTodoNodeResult.builder()
                .success(false)
                .suspended(true)
                .summary("external_tool_job_pending")
                .output("")
                .pendingRunId(pending.getRunId())
                .pendingToolCallId(pending.getToolCallId())
                .pendingAttempt(pending.getAttempt())
                .build();
    }

    /**
     * Phase 3.2 A3: 把 failureMetadata 按语义路由到 event payload 的对应子字段。
     * <p>
     * 路由规则（互斥，按优先级）：
     * <ul>
     *   <li>{@code budget_exceeded=true} → {@code budget_failure}（Phase 3.2 A3 新增，
     *       防止 budget failure 被误归类为 empty_todo_output）</li>
     *   <li>含 #59 14 字段中任一（如 {@code finish_reason / raw_output_length / recovery_outcome} 等）
     *       或显式 {@code empty_todo_output} 标记 → {@code empty_output_observation}（#59 兼容）</li>
     *   <li>其他 generic metadata → {@code failure_metadata}（保留通道，避免信息丢失）</li>
     * </ul>
     * <p>
     * 调用方：{@code LangchainLinearRunPipelineImpl#publishFailure} / {@code LangchainLinearWorkflowExecutor#emitTodoNodeEvent} /
     * {@code LangchainDagWorkflowExecutor#todoNodeResultPayload}，分别在 WORKFLOW_FAILED / TODO_NODE_FAILED event payload 中调用。
     * <p>
     * 返回的 key 名称是 event payload 字段 snake_case 命名。
     */
    public static final String BUDGET_FAILURE_FIELD = "budget_failure";
    public static final String EMPTY_OUTPUT_OBSERVATION_FIELD = "empty_output_observation";
    public static final String FAILURE_METADATA_FIELD = "failure_metadata";

    public static String routeFailureMetadataField(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        // budget priority 最高：A3 引入，先于 #59 兼容
        if (Boolean.TRUE.equals(meta.get("budget_exceeded"))) {
            return BUDGET_FAILURE_FIELD;
        }
        // #59 兼容：empty_todo_output 显式标记 / 14 字段中任一
        if (meta.containsKey("empty_todo_output")
                || meta.containsKey("finish_reason")
                || meta.containsKey("raw_output_length")
                || meta.containsKey("trimmed_output_length")
                || meta.containsKey("recovery_attempted")
                || meta.containsKey("recovery_outcome")
                || meta.containsKey("budget_hit")
                || meta.containsKey("last_non_empty_todo_id")
                || meta.containsKey("previous_todo_total_length")
                || meta.containsKey("current_todo_prompt_budget_chars")) {
            return EMPTY_OUTPUT_OBSERVATION_FIELD;
        }
        // generic fallback
        return FAILURE_METADATA_FIELD;
    }
}
