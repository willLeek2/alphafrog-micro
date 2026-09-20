package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.stereotype.Component;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.execution.dag.LangchainDagExecutionGraph;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 LINEAR（顺序）与 DAG（依赖图）计划投影成同一种节点工作项草稿。
 *
 * <p>本类只在 Run 协调侧解释编排语义。它只返回当前已经可运行、且数据库中尚未存在的节点；
 * 节点池拿到草稿落成的记录后只按稳定身份领取和执行，不重新解释依赖图。</p>
 */
@Component
public class NodeWorkPlanAdapter {

    private static final int INITIAL_ATTEMPT = 0;
    private static final int INITIAL_SEGMENT = 0;

    public List<NodeWorkDraft> runnableLinear(String runId,
                                              int planGeneration,
                                              LangchainTodoPlan plan,
                                              Set<String> completedNodeIds,
                                              Set<String> existingNodeIds,
                                              long contextVersion,
                                              long runControlVersion) {
        List<TodoItem> ordered = items(plan).stream()
                .sorted(Comparator.comparingInt(TodoItem::getSequence))
                .toList();
        validateItems(ordered);
        Set<String> completed = safeSet(completedNodeIds);
        Set<String> existing = safeSet(existingNodeIds);
        String previous = null;
        for (TodoItem item : ordered) {
            requireNodeId(item);
            if (completed.contains(item.getId())) {
                previous = item.getId();
                continue;
            }
            // 顺序计划一次只放出第一个未完成节点；已有工作项由数据库状态机继续管理。
            if (existing.contains(item.getId())) {
                return List.of();
            }
            List<String> dependency = previous == null ? List.of() : List.of(previous);
            return List.of(draft(runId, planGeneration, item, "LINEAR", dependency,
                    contextVersion, runControlVersion));
        }
        return List.of();
    }

    public List<NodeWorkDraft> runnableDag(String runId,
                                           int planGeneration,
                                           LangchainTodoPlan plan,
                                           Set<String> completedNodeIds,
                                           Set<String> existingNodeIds,
                                           long contextVersion,
                                           long runControlVersion) {
        List<TodoItem> items = items(plan);
        validateItems(items);
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(items);
        if (graph.hasCycle()) {
            throw new IllegalArgumentException("dag_circular_dependency");
        }
        Set<String> completed = safeSet(completedNodeIds);
        Set<String> existing = safeSet(existingNodeIds);
        List<NodeWorkDraft> runnable = new ArrayList<>();
        for (TodoItem item : items) {
            requireNodeId(item);
            if (completed.contains(item.getId()) || existing.contains(item.getId())) {
                continue;
            }
            Set<String> dependencies = graph.getDependencies(item.getId());
            if (!completed.containsAll(dependencies)) {
                continue;
            }
            runnable.add(draft(runId, planGeneration, item, "DAG",
                    dependencies.stream().sorted().toList(), contextVersion, runControlVersion));
        }
        return List.copyOf(runnable);
    }

    private NodeWorkDraft draft(String runId,
                                int planGeneration,
                                TodoItem item,
                                String workflow,
                                List<String> dependencies,
                                long contextVersion,
                                long runControlVersion) {
        return new NodeWorkDraft(
                new NodeWorkItemIdentity(runId, planGeneration, item.getId(), INITIAL_ATTEMPT, INITIAL_SEGMENT),
                workflow,
                dependencies,
                contextVersion,
                runControlVersion,
                SchedulerVersionPolicy.DUAL_POOL_V1,
                item);
    }

    private List<TodoItem> items(LangchainTodoPlan plan) {
        if (plan == null || plan.getItems() == null) {
            throw new IllegalArgumentException("todo_plan_required");
        }
        return plan.getItems();
    }

    private Set<String> safeSet(Set<String> values) {
        return values == null ? Set.of() : new HashSet<>(values);
    }

    private void requireNodeId(TodoItem item) {
        if (item == null || item.getId() == null || item.getId().isBlank()) {
            throw new IllegalArgumentException("todo_node_id_required");
        }
    }

    private void validateItems(List<TodoItem> items) {
        Set<String> ids = new HashSet<>();
        for (TodoItem item : items) {
            requireNodeId(item);
            if (!ids.add(item.getId())) {
                throw new IllegalArgumentException("duplicate_todo_node_id:" + item.getId());
            }
        }
    }
}
