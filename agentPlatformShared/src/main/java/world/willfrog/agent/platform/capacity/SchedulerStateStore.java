package world.willfrog.agent.platform.capacity;

/**
 * 调度器全局状态的存储接口：新增暂停标记与跨图轮转轮次。
 *
 * <p>暂停判定固定走 {@link SchedulerPausePolicy}，并且每次判定都先把库里的暂停标记读出来当输入：
 * 重启之后只能按库里那条事实继续判断，不能拿「当前数量低于高水位」重新起算。</p>
 */
public interface SchedulerStateStore {

    /** 读当前的全局容量状态；还没有记录时返回空。 */
    SchedulerCapacityState loadCapacity();

    /**
     * 判定并记录一次新增暂停状态。
     *
     * <p>返回的是写库之后的实际状态与「这次有没有改变标记」。数量到高水位就暂停，暂停后只回落到低水位
     * 才恢复，落在两个水位之间保持原样。</p>
     */
    SchedulerPauseDecision decideAndRecord(long unfinishedCount, int highWatermark, int lowWatermark);

    /** 推进一步轮次并返回新的轮次号；数据库是轮次号的唯一权威。 */
    long advanceRound(SchedulerRoundScope scope);

    /** 读当前轮次号；还没有记录时返回 0。 */
    long currentRound(SchedulerRoundScope scope);
}
