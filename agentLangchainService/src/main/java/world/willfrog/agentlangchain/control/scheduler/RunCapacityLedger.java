package world.willfrog.agentlangchain.control.scheduler;

/**
 * 进程内容量账本：按权重记录当前已占用的容量单位。
 *
 * <p>当前只需要进程内实现。以后 Agent 与 Sandbox 变成多节点时，可以替换为
 * 共享实现；工作流执行器不应直接读写 Redis、租约或实例身份。</p>
 */
public interface RunCapacityLedger {

    /**
     * 尝试为一个执行名额占用容量。
     *
     * @param key    唯一标识（scheduler 使用 reservation id）
     * @param weight 容量权重，必须为正数
     * @return true 表示占用成功；false 表示剩余容量不足，不得执行
     */
    boolean tryAcquire(Object key, int weight);

    /**
     * 归还一个此前成功占用的名额。重复归还与未知 key 必须幂等无害。
     */
    void release(Object key);

    /** 当前已占用容量单位总数。 */
    int usedUnits();

    /** 账本允许的最大容量单位总数。 */
    int maxUnits();
}
