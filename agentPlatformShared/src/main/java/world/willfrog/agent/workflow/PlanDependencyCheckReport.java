package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 计划形成时取得的有限检查事实，原检查时间与当前执行代次分开保存。 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PlanDependencyCheckReport {
    public static final int MAX_DEPENDENCIES = 8;
    public static final int MAX_HINTS = 32;
    public static final long TRAVERSAL_BUDGET_MS = 250;
    public static final int MAX_REPORT_CHARACTERS = 8000;

    @Builder.Default
    private int version = 1;
    private String checkedAt;
    private Integer checkedPlanGeneration;
    private long durationMs;
    private int checkedDependencyCount;
    @Builder.Default
    private List<Hint> hints = new ArrayList<>();
    /** 未保留的提示数；预算耗尽时每条尚未逐项检查的预计工具记录记一条待检查提示。 */
    private int omittedCount;
    @Builder.Default
    private List<String> omittedTodoIds = new ArrayList<>();
    private int omittedTodoCount;
    private boolean truncated;

    public enum Status { AVAILABLE, UNAVAILABLE, UNCHECKED }
    public enum Basis { REFERENCE_STATE, CONFIG_PRESENT, TOOL_AVAILABILITY, NONE }

    public record Hint(String todoId, String toolName, String dependencyId,
                       String dependencyLabel, Status status, Basis basis, String reason) {
    }
}
