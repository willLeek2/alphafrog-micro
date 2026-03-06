package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoPlan {
    private String analysis;
    @Builder.Default
    private List<TodoItem> items = new ArrayList<>();

    /** DAG 元数据（可选，用于优化） */
    private DagMetadata dagMetadata;
}
