package world.willfrog.agent.platform.capacity;

/**
 * 全局新增暂停的高低水位判定。
 *
 * <p>规则只有三条，顺序不能调换：</p>
 * <ol>
 *   <li>当前未完成数达到高水位：暂停新增。</li>
 *   <li>否则回落到低水位及以下：恢复新增。</li>
 *   <li>否则落在两个水位之间：保持原来的标记不动。</li>
 * </ol>
 *
 * <p>第三条是这个策略的全部意义：数量从高水位往下掉、但还没掉到低水位时，不能因为「已经低于高水位」
 * 就恢复新增，否则高水位附近会反复开关，已经暂停的图会被反复插入新节点。暂停标记因此必须持久化，
 * 进程重启后也要按库里那条事实继续判断，而不是拿「当前数量低于高水位」重新起算。</p>
 *
 * <p>两个水位允许相等，此时判定退化成单阈值：达到就暂停、低于就恢复，第三条不再出现。</p>
 */
public final class SchedulerPausePolicy {

    private SchedulerPausePolicy() {
    }

    /**
     * 判定这一次要不要暂停新增。
     *
     * @param currentlyPaused 数据库里当前的暂停标记（权威值，不是内存推断）
     * @param unfinishedCount 全局未完成工作项数
     * @param highWatermark   高水位
     * @param lowWatermark    低水位
     */
    public static SchedulerPauseDecision decide(boolean currentlyPaused,
                                                long unfinishedCount,
                                                int highWatermark,
                                                int lowWatermark) {
        requireSane(unfinishedCount, highWatermark, lowWatermark);
        boolean paused;
        if (unfinishedCount >= highWatermark) {
            paused = true;
        } else if (unfinishedCount <= lowWatermark) {
            paused = false;
        } else {
            paused = currentlyPaused;
        }
        return new SchedulerPauseDecision(
                paused, paused != currentlyPaused, unfinishedCount, highWatermark, lowWatermark);
    }

    /**
     * 水位与数量必须自洽。配置写错时失败关闭：宁可让这次判定抛错被看见，
     * 也不能拿一个反过来的水位区间去决定要不要暂停。
     */
    private static void requireSane(long unfinishedCount, int highWatermark, int lowWatermark) {
        if (unfinishedCount < 0) {
            throw new IllegalArgumentException("全局未完成工作项数不能是负数：" + unfinishedCount);
        }
        if (highWatermark < 0 || lowWatermark < 0) {
            throw new IllegalArgumentException(
                    "水位不能是负数：高 " + highWatermark + "，低 " + lowWatermark);
        }
        if (lowWatermark > highWatermark) {
            throw new IllegalArgumentException(
                    "低水位不能高于高水位：高 " + highWatermark + "，低 " + lowWatermark);
        }
    }
}
