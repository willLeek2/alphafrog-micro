package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowState {
    /** 线性模式使用的当前索引 */
    private int currentIndex;
    @Builder.Default
    private List<TodoItem> completedItems = new ArrayList<>();
    @Builder.Default
    private Map<String, TodoExecutionRecord> context = new LinkedHashMap<>();
    private int toolCallsUsed;
    private Instant savedAt;

    /** DAG 模式：已完成节点 ID 集合 */
    @Builder.Default
    private Set<String> completedNodeIds = new HashSet<>();

    /** DAG 模式：运行中节点 ID 集合 */
    @Builder.Default
    private Set<String> runningNodeIds = new HashSet<>();

    /** 执行模式标识（用于区分线性和 DAG 模式） */
    private String executionModeName;

    public boolean isNodeCompleted(String todoId) {
        return completedNodeIds != null && completedNodeIds.contains(todoId);
    }

    public void markNodeCompleted(String todoId) {
        if (completedNodeIds == null) {
            completedNodeIds = new HashSet<>();
        }
        completedNodeIds.add(todoId);
        if (runningNodeIds != null) {
            runningNodeIds.remove(todoId);
        }
    }

    public void markNodeRunning(String todoId) {
        if (runningNodeIds == null) {
            runningNodeIds = new HashSet<>();
        }
        runningNodeIds.add(todoId);
    }
}
