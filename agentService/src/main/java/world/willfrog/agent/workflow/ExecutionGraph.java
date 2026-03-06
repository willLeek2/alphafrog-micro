package world.willfrog.agent.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * DAG 执行图：节点+有向边+入度表。
 */
public class ExecutionGraph {

    private final Map<String, TodoItem> nodes = new HashMap<>();
    private final Map<String, List<String>> adjacency = new HashMap<>();
    private final Map<String, Integer> indegree = new HashMap<>();

    public void addNode(String id, TodoItem item) {
        nodes.put(id, item);
        adjacency.putIfAbsent(id, new ArrayList<>());
        indegree.putIfAbsent(id, 0);
    }

    public void addEdge(String from, String to) {
        adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
    }

    public void calculateIndegrees() {
        for (String nodeId : nodes.keySet()) {
            indegree.putIfAbsent(nodeId, 0);
        }
        for (Map.Entry<String, List<String>> entry : adjacency.entrySet()) {
            for (String successor : entry.getValue()) {
                indegree.merge(successor, 1, Integer::sum);
            }
        }
    }

    /**
     * 检测循环依赖（Kahn 算法）。
     */
    public boolean hasCycle() {
        Map<String, Integer> tempDegree = new HashMap<>(indegree);
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : tempDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String successor : getSuccessors(current)) {
                int newDegree = tempDegree.get(successor) - 1;
                tempDegree.put(successor, newDegree);
                if (newDegree == 0) {
                    queue.add(successor);
                }
            }
        }
        return visited != nodes.size();
    }

    public Set<String> getNodesWithZeroIndegree() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public List<String> getSuccessors(String nodeId) {
        return adjacency.getOrDefault(nodeId, List.of());
    }

    public TodoItem getNode(String id) {
        return nodes.get(id);
    }

    public int getTotalNodes() {
        return nodes.size();
    }

    public int getIndegree(String nodeId) {
        return indegree.getOrDefault(nodeId, 0);
    }

    /**
     * 线程安全地减少后继节点入度。
     *
     * @return 减少后的入度值
     */
    public synchronized int decrementIndegree(String nodeId) {
        int current = indegree.getOrDefault(nodeId, 0);
        int newValue = Math.max(0, current - 1);
        indegree.put(nodeId, newValue);
        return newValue;
    }
}
