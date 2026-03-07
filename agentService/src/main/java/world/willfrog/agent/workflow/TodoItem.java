package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {
    private String id;
    private int sequence;
    private TodoType type;
    private String toolName;
    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();
    private String reasoning;
    private ExecutionMode executionMode;
    private TodoStatus status;
    private String resultSummary;
    private String output;
    private String decisionLlmTraceId;
    private String decisionStage;
    private String decisionExcerpt;
    private Instant createdAt;
    private Instant completedAt;

    /** 依赖的 todoId 列表，空列表表示该任务可以立即执行（无依赖） */
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();

    /** 分组键，同组任务可并行执行 */
    private String groupKey;

    /** 是否可并行化（由规划器标注） */
    @Builder.Default
    private boolean parallelizable = false;

    /** 预估执行时间（秒），用于调度优化 */
    @Builder.Default
    private int estimatedDuration = 5;
}
