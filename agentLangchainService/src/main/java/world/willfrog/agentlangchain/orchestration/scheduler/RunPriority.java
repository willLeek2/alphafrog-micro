package world.willfrog.agentlangchain.orchestration.scheduler;

/**
 * Agent Run 的调度优先级等级。
 *
 * <p>当前所有 Run 都使用 {@link #NORMAL}；枚举与队列结构保留多等级扩展位置，
 * 未来增加定时任务或交互任务等级时不需要改写队列。</p>
 */
public enum RunPriority {

    NORMAL;

    /** 枚举声明顺序即调度轮转顺序；新增等级应放在语义上更优先的位置。 */
    public int schedulingOrder() {
        return ordinal();
    }
}
