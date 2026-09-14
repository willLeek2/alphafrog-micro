package world.willfrog.agentlangchain.control.scheduler;

/**
 * 决定一个 Agent Run 占用的容量权重（容量单位）。
 *
 * <p>当前没有任何请求信号可以区分普通任务和重任务，生产策略恒定返回
 * {@link #STANDARD_WEIGHT}；接口与账本保留权重维度，未来由 Create Run
 * 请求字段或规划结果提供分类信号。</p>
 */
public interface RunWeightPolicy {

    /** 普通任务的容量权重。 */
    int STANDARD_WEIGHT = 1;

    /** 重任务的容量权重（未来使用）。 */
    int HEAVY_WEIGHT = 3;

    /**
     * @param runId Run 身份；reserve 阶段（Run 行尚未创建）可能为 null
     * @return 该 Run 的容量权重，必须为正数
     */
    int weightUnitsFor(String runId);
}
