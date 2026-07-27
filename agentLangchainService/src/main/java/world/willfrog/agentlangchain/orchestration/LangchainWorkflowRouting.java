package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;

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
     * 返回首次执行、持久化和恢复共同使用的有效计划。
     *
     * <p>当 durable 工具只支持 LINEAR checkpoint 时，不能只切换 executor 而保留一份
     * DAG plan。这里复制 Todo 并移除依赖/并行字段，同时把 executionMode 固定为 LINEAR，
     * 使 PostgreSQL planJson、PLAN_READY、首次执行与 resume 读取到完全相同的语义。</p>
     */
    static LangchainTodoPlan effectivePlan(LangchainTodoPlan plan, boolean forceLinear) {
        if (plan == null || !forceLinear || isAlreadyCanonicalLinear(plan)) {
            return plan;
        }
        List<TodoItem> source = plan.getItems();
        List<TodoItem> linearItems = new ArrayList<>();
        if (source != null) {
            for (TodoItem item : source) {
                if (item == null) {
                    continue;
                }
                // sequence 保留 planner 的确定顺序；DAG 专属字段全部清空，避免恢复时重新解释依赖。
                linearItems.add(TodoItem.builder()
                        .id(item.getId())
                        .sequence(item.getSequence())
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
