package world.willfrog.agentlangchain.orchestration.scheduler;

/**
 * 决定一个 Agent Run 的调度优先级。
 *
 * <p>保留接口是为了以后增加定时任务或交互任务等级时不用重写队列；
 * 当前不实现多用户公平和配额。</p>
 */
public interface RunPriorityPolicy {

    /**
     * @param runId Run 身份；reserve 阶段（Run 行尚未创建）可能为 null
     * @return 该 Run 的优先级等级
     */
    RunPriority priorityFor(String runId);
}
