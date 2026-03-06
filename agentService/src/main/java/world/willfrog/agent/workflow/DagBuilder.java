package world.willfrog.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从 TodoPlan 构建 DAG 执行图。
 */
@Component
public class DagBuilder {

    /**
     * 构建 DAG 执行图。
     *
     * @param plan TodoPlan
     * @return ExecutionGraph
     * @throws DagValidationException 存在循环依赖时抛出
     */
    public ExecutionGraph buildGraph(TodoPlan plan) {
        ExecutionGraph graph = new ExecutionGraph();
        List<TodoItem> items = plan.getItems() == null ? List.of() : plan.getItems();

        for (TodoItem item : items) {
            graph.addNode(item.getId(), item);
        }

        for (TodoItem item : items) {
            List<String> deps = item.getDependsOn();
            if (deps != null) {
                for (String depId : deps) {
                    graph.addEdge(depId, item.getId());
                }
            }
        }

        graph.calculateIndegrees();

        if (graph.hasCycle()) {
            throw new DagValidationException("Todo plan contains circular dependencies");
        }

        return graph;
    }
}
