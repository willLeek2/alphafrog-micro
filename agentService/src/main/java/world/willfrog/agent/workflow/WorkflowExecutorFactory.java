package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WorkflowExecutorFactory {

    private final LinearWorkflowExecutor linearExecutor;
    private final DagWorkflowExecutor dagExecutor;

    public WorkflowExecutor getExecutor(TodoPlan plan) {
        if (isDagPlan(plan)) {
            return dagExecutor;
        }
        return linearExecutor;
    }

    private boolean isDagPlan(TodoPlan plan) {
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            return false;
        }
        List<TodoItem> items = plan.getItems();
        for (TodoItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.getExecutionMode() == ExecutionMode.DAG) {
                return true;
            }
            if (item.isParallelizable()) {
                return true;
            }
            if (item.getDependsOn() != null && !item.getDependsOn().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
