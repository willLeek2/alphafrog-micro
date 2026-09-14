package world.willfrog.agentlangchain.execution.dag;

import lombok.AllArgsConstructor;
import lombok.Data;
import world.willfrog.agent.workflow.TodoItem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime DAG graph (dependencies + dependents) ported from legacy {@code DagWorkflowExecutor.ExecutionGraph}.
 */
@Data
@AllArgsConstructor
public class LangchainDagExecutionGraph {

    private Map<String, TodoItem> itemMap;
    private Map<String, Set<String>> dependencies;
    private Map<String, Set<String>> dependents;

    public static LangchainDagExecutionGraph from(List<TodoItem> items) {
        Map<String, TodoItem> itemMap = items.stream()
                .collect(Collectors.toMap(TodoItem::getId, i -> i, (a, b) -> a));
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (TodoItem item : items) {
            dependencies.putIfAbsent(item.getId(), new HashSet<>());
            dependents.putIfAbsent(item.getId(), new HashSet<>());
            List<String> deps = item.getDependsOn() == null ? List.of() : item.getDependsOn();
            for (String depId : deps) {
                dependencies.putIfAbsent(depId, new HashSet<>());
                dependents.putIfAbsent(depId, new HashSet<>());
                if (itemMap.containsKey(depId)) {
                    dependencies.get(item.getId()).add(depId);
                    dependents.get(depId).add(item.getId());
                }
            }
        }
        return new LangchainDagExecutionGraph(itemMap, dependencies, dependents);
    }

    public Set<String> getDependencies(String nodeId) {
        return dependencies.getOrDefault(nodeId, Set.of());
    }

    public Set<String> getDependents(String nodeId) {
        return dependents.getOrDefault(nodeId, Set.of());
    }

    public boolean hasCycle() {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String nodeId : itemMap.keySet()) {
            if (hasCycleFrom(nodeId, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleFrom(String nodeId, Set<String> visiting, Set<String> visited) {
        if (visited.contains(nodeId)) {
            return false;
        }
        if (visiting.contains(nodeId)) {
            return true;
        }
        visiting.add(nodeId);
        for (String dep : getDependencies(nodeId)) {
            if (hasCycleFrom(dep, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    /**
     * 依赖链最大深度：从任意节点沿 dependsOn 走到源头，最长那条链上的节点数。
     * 无环图才有定义；有环时不要调用。
     */
    public int maxDependencyDepth() {
        if (itemMap == null || itemMap.isEmpty()) {
            return 0;
        }
        Map<String, Integer> memo = new HashMap<>();
        int max = 0;
        for (String nodeId : itemMap.keySet()) {
            max = Math.max(max, depthFrom(nodeId, memo));
        }
        return max;
    }

    private int depthFrom(String nodeId, Map<String, Integer> memo) {
        Integer cached = memo.get(nodeId);
        if (cached != null) {
            return cached;
        }
        int depth = 1;
        for (String dep : getDependencies(nodeId)) {
            depth = Math.max(depth, 1 + depthFrom(dep, memo));
        }
        memo.put(nodeId, depth);
        return depth;
    }
}
