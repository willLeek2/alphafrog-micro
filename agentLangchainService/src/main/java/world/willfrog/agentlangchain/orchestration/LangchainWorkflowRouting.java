package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * LINEAR vs DAG 路由决策器。~30 行薄判断层。
 *
 * <p>决策逻辑：
 * <ol>
 *   <li>plan 的 executionMode 显式为 DAG → DAG</li>
 *   <li>plan 的 executionMode 显式为 LINEAR → LINEAR</li>
 *   <li>AUTO 模式：检查是否有 Todo 声明了 dependsOn（依赖关系），
 *       有则 DAG，无则 LINEAR</li>
 * </ol>
 *
 * <p>被 {@code LangchainLinearRunPipelineImpl} 调用，
 * 决定走 DAG 执行器还是 LINEAR 执行器。
 */
final class LangchainWorkflowRouting {

    private LangchainWorkflowRouting() {
    }

    static boolean shouldUseDag(LangchainTodoPlan plan) {
        if (plan == null) {
            return false;
        }
        if (plan.getExecutionMode() == PlanExecutionMode.DAG) {
            return true;
        }
        if (plan.getExecutionMode() == PlanExecutionMode.LINEAR) {
            return false;
        }
        List<TodoItem> items = plan.getItems();
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (TodoItem item : items) {
            if (item != null && item.getDependsOn() != null && !item.getDependsOn().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 Run 创建时的 requested mode 与 planner 输出合并成唯一 effective plan。
     *
     * <ul>
     *   <li>requested LINEAR：稳定拓扑排序并移除 DAG metadata，持久化为 LINEAR；</li>
     *   <li>requested DAG：保留 planner 的节点/依赖，持久化为 DAG；</li>
     *   <li>requested AUTO：按 planner mode / dependsOn 路由，随后冻结为 LINEAR 或 DAG。</li>
     * </ul>
     *
     * <p>返回计划会同时用于 planJson、PLAN_READY 和 executor 选择，所以不会再出现
     * “持久化 AUTO/DAG、实际却临时跑 LINEAR”的分裂语义。</p>
     */
    static LangchainTodoPlan effectivePlan(LangchainTodoPlan plan, PlanExecutionMode requestedMode) {
        if (plan == null) {
            return null;
        }
        PlanExecutionMode requested = requestedMode == null ? PlanExecutionMode.AUTO : requestedMode;
        return switch (requested) {
            case LINEAR -> effectivePlan(plan, true);
            case DAG -> copyWithMode(plan, PlanExecutionMode.DAG);
            case AUTO -> copyWithMode(
                    plan,
                    shouldUseDag(plan) ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR);
        };
    }

    /**
     * 返回首次执行、持久化和恢复共同使用的有效计划。
     *
     * <p>当 durable 工具只支持 LINEAR checkpoint 时，不能只切换 executor 而保留一份
     * DAG plan。这里先按依赖做稳定拓扑排序，再复制 Todo、重新编号并移除依赖/并行字段，
     * 同时把 executionMode 固定为 LINEAR。这样 PostgreSQL planJson、PLAN_READY、首次执行
     * 与 resume 读取到完全相同的语义，也不会因为 planner 恰好返回了合法的 DAG 元数据
     * 就误杀本可线性化的计划。</p>
     *
     * <p>缺失依赖、重复 id 或环都无法安全猜测，必须 fail-closed。无依赖节点之间使用
     * planner sequence、原数组位置、id 依次作为稳定 tie-break，保证同一输入得到同一计划。</p>
     */
    static LangchainTodoPlan effectivePlan(LangchainTodoPlan plan, boolean forceLinear) {
        if (plan == null || !forceLinear || isAlreadyCanonicalLinear(plan)) {
            return plan;
        }
        List<TodoItem> source = plan.getItems();
        List<TodoItem> linearItems = new ArrayList<>();
        if (source != null) {
            List<TodoItem> ordered = stableTopologicalOrder(source);
            for (int index = 0; index < ordered.size(); index++) {
                TodoItem item = ordered.get(index);
                // sequence 与拓扑数组顺序重新对齐；DAG 专属字段全部清空，避免恢复时重新解释依赖。
                linearItems.add(TodoItem.builder()
                        .id(item.getId())
                        .sequence(index + 1)
                        .description(item.getDescription())
                        .status(item.getStatus())
                        .resultSummary(item.getResultSummary())
                        .output(item.getOutput())
                        .createdAt(item.getCreatedAt())
                        .completedAt(item.getCompletedAt())
                        .dependsOn(List.of())
                        .groupKey(null)
                        .parallelizable(false)
                        .build());
            }
        }
        return LangchainTodoPlan.builder()
                .analysis(plan.getAnalysis())
                .items(linearItems)
                .extractedEntities(plan.getExtractedEntities() == null
                        ? List.of() : new ArrayList<>(plan.getExtractedEntities()))
                .executionMode(PlanExecutionMode.LINEAR)
                .build();
    }

    private static LangchainTodoPlan copyWithMode(LangchainTodoPlan plan, PlanExecutionMode mode) {
        if (plan.getExecutionMode() == mode) {
            return plan;
        }
        return LangchainTodoPlan.builder()
                .analysis(plan.getAnalysis())
                .items(plan.getItems() == null ? List.of() : new ArrayList<>(plan.getItems()))
                .extractedEntities(plan.getExtractedEntities() == null
                        ? List.of() : new ArrayList<>(plan.getExtractedEntities()))
                .executionMode(mode)
                .build();
    }

    private static List<TodoItem> stableTopologicalOrder(List<TodoItem> source) {
        List<IndexedTodo> nodes = new ArrayList<>();
        Map<String, IndexedTodo> byId = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            TodoItem item = source.get(index);
            if (item == null) {
                continue;
            }
            String id = item.getId() == null ? "" : item.getId().trim();
            if (id.isEmpty()) {
                throw invalidLinearPlan("missing_todo_id");
            }
            IndexedTodo node = new IndexedTodo(item, index);
            if (byId.putIfAbsent(id, node) != null) {
                throw invalidLinearPlan("duplicate_todo_id:" + id);
            }
            nodes.add(node);
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<IndexedTodo>> outgoing = new HashMap<>();
        for (IndexedTodo node : nodes) {
            indegree.put(node.item().getId().trim(), 0);
        }
        for (IndexedTodo node : nodes) {
            String nodeId = node.item().getId().trim();
            Set<String> uniqueDependencies = new LinkedHashSet<>();
            List<String> dependencies = node.item().getDependsOn();
            if (dependencies != null) {
                for (String rawDependency : dependencies) {
                    String dependency = rawDependency == null ? "" : rawDependency.trim();
                    if (dependency.isEmpty() || !byId.containsKey(dependency)) {
                        throw invalidLinearPlan("missing_dependency:" + nodeId + "->" + dependency);
                    }
                    uniqueDependencies.add(dependency);
                }
            }
            indegree.put(nodeId, uniqueDependencies.size());
            for (String dependency : uniqueDependencies) {
                outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node);
            }
        }

        Comparator<IndexedTodo> stableOrder = Comparator
                .comparingInt((IndexedTodo node) -> node.item().getSequence())
                .thenComparingInt(IndexedTodo::originalIndex)
                .thenComparing(node -> node.item().getId());
        PriorityQueue<IndexedTodo> ready = new PriorityQueue<>(stableOrder);
        nodes.stream()
                .filter(node -> indegree.get(node.item().getId().trim()) == 0)
                .forEach(ready::add);

        List<TodoItem> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            IndexedTodo current = ready.remove();
            String currentId = current.item().getId().trim();
            ordered.add(current.item());
            for (IndexedTodo dependent : outgoing.getOrDefault(currentId, List.of())) {
                String dependentId = dependent.item().getId().trim();
                int remaining = indegree.computeIfPresent(dependentId, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            throw invalidLinearPlan("dependency_cycle");
        }
        return ordered;
    }

    private static IllegalStateException invalidLinearPlan(String reason) {
        return new IllegalStateException("linear_plan_not_linearizable:" + reason);
    }

    private record IndexedTodo(TodoItem item, int originalIndex) {
    }

    /**
     * LINEAR mode 只有在 Todo 也不携带 DAG 元数据时才算可直接持久化。
     * 显式 LINEAR 但仍含 dependsOn/groupKey/parallelizable 的 planner 响应也必须正规化，
     * 否则首次执行虽按顺序运行，恢复时数据库里仍残留两套互相矛盾的语义。
     */
    private static boolean isAlreadyCanonicalLinear(LangchainTodoPlan plan) {
        if (plan.getExecutionMode() != PlanExecutionMode.LINEAR) {
            return false;
        }
        List<TodoItem> items = plan.getItems();
        if (items == null) {
            return true;
        }
        for (TodoItem item : items) {
            if (item != null
                    && ((item.getDependsOn() != null && !item.getDependsOn().isEmpty())
                    || (item.getGroupKey() != null && !item.getGroupKey().isBlank())
                    || item.isParallelizable())) {
                return false;
            }
        }
        return true;
    }
}
