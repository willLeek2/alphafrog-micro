package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

/**
 * 把一次 Run 的执行模式收到一处裁决。
 *
 * <p>创建 Run 时写入的是用户要求（LINEAR / DAG / AUTO）；规划器返回的计划上还有一份
 * 规划结果；真正拿去落库、发 PLAN_READY、选执行器的只能是冻结后的生效模式。
 * 这三层以前散落在管线和路由工具方法里，读代码时分不清“用户要什么”和“这次实际跑什么”。</p>
 */
public final class ExecutionModeResolver {

    private ExecutionModeResolver() {
    }

    /**
     * 一次裁决的完整结果。{@code effectivePlan} 与 {@code effective} 必须一起用，
     * 避免管线自己再猜一次 LINEAR / DAG。
     */
    public record Decision(
            PlanExecutionMode requested,
            PlanExecutionMode planned,
            PlanExecutionMode effective,
            LangchainTodoPlan effectivePlan,
            boolean useDag,
            String reason
    ) {
    }

    /**
     * 首次规划路径：用用户要求去冻结规划器输出。
     *
     * <p>计划形状（拓扑排序、清掉依赖字段）仍由 {@link LangchainWorkflowRouting} 负责；
     * 这里只负责三层词汇和给 PLAN_READY 用的原因说明。</p>
     */
    public static Decision resolve(PlanExecutionMode requested, LangchainTodoPlan planned) {
        PlanExecutionMode requestedMode = requested == null ? PlanExecutionMode.AUTO : requested;
        PlanExecutionMode plannedMode = planned == null ? null : planned.getExecutionMode();
        LangchainTodoPlan effectivePlan = LangchainWorkflowRouting.effectivePlan(planned, requestedMode);
        boolean useDag = LangchainWorkflowRouting.shouldUseDag(effectivePlan);
        PlanExecutionMode effective = useDag ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR;
        return new Decision(
                requestedMode,
                plannedMode,
                effective,
                effectivePlan,
                useDag,
                describeReason(requestedMode, planned, effective));
    }

    /**
     * 服务重启 / 恢复路径：计划已经冻结，只读生效模式，不再拿 AUTO 重新猜。
     */
    public static Decision inspectFrozen(LangchainTodoPlan frozenPlan) {
        boolean useDag = LangchainWorkflowRouting.shouldUseDag(frozenPlan);
        PlanExecutionMode effective = useDag ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR;
        PlanExecutionMode frozenMode = frozenPlan == null ? null : frozenPlan.getExecutionMode();
        return new Decision(
                frozenMode == null ? effective : frozenMode,
                frozenMode,
                effective,
                frozenPlan,
                useDag,
                "使用已冻结计划，不再重新裁决");
    }

    static String describeReason(
            PlanExecutionMode requested,
            LangchainTodoPlan planned,
            PlanExecutionMode effective) {
        PlanExecutionMode requestedMode = requested == null ? PlanExecutionMode.AUTO : requested;
        return switch (requestedMode) {
            case LINEAR -> "用户指定线性执行，规划结果按线性冻结";
            case DAG -> "用户指定 DAG 执行，保留规划里的节点依赖";
            case AUTO -> autoReason(planned, effective);
        };
    }

    private static String autoReason(LangchainTodoPlan planned, PlanExecutionMode effective) {
        if (effective == PlanExecutionMode.DAG) {
            if (planned != null && planned.getExecutionMode() == PlanExecutionMode.DAG) {
                return "用户未指定模式，规划结果为 DAG，按 DAG 执行";
            }
            return "用户未指定模式，计划含有依赖，按 DAG 执行";
        }
        return "用户未指定模式，按线性执行";
    }
}
