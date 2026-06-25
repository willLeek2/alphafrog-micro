package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class LangchainTodoNodeResult {
    private boolean success;
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
     * 链路：LangchainTodoNodeResult → LangchainLinearWorkflowResult → publishFailure event payload 的 empty_output_observation 子 map。
     */
    private Map<String, Object> failureMetadata;

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
}
