package world.willfrog.agent.platform.capacity;

/**
 * 一次「要不要暂停新增」的判定结果。
 *
 * <p>结果里带上判定时用的数量与两个水位，日志和指标可以直接用，不需要再去别处拼那几个数。</p>
 *
 * @param paused         判定之后的暂停标记
 * @param changed        这次判定是否改变了原来的标记；只有改变时才需要写库和记一条事件
 * @param unfinishedCount 判定时的全局未完成工作项数
 * @param highWatermark  判定用的高水位
 * @param lowWatermark   判定用的低水位
 */
public record SchedulerPauseDecision(
        boolean paused,
        boolean changed,
        long unfinishedCount,
        int highWatermark,
        int lowWatermark) {

    /**
     * 日志用的中文说明，讲清楚这次判定落在三条规则的哪一条上。
     *
     * <p>说明按「数量落在哪个区间」和「标记是否改变」一起写：同样是保持暂停，数量高于高水位与落在
     * 高低水位之间是两回事，混成一句会让读日志的人以为数量已经掉下来了。数量与标记不吻合的组合
     * 也照实写出来，不粉饰成正常情形。</p>
     */
    public String describe() {
        String action;
        if (paused && changed) {
            action = "达到高水位，暂停新增";
        } else if (!paused && changed) {
            action = "回落到低水位，恢复新增";
        } else if (paused && unfinishedCount >= highWatermark) {
            action = "仍不低于高水位，保持暂停";
        } else if (paused && unfinishedCount <= lowWatermark) {
            action = "数量已低于低水位但标记仍是暂停，与恢复规则不符";
        } else if (paused) {
            action = "在高低水位之间，保持暂停";
        } else if (unfinishedCount >= highWatermark) {
            action = "数量已到高水位但标记不是暂停，与暂停规则不符";
        } else if (unfinishedCount <= lowWatermark) {
            action = "低于低水位，保持新增";
        } else {
            action = "未到高水位，继续新增";
        }
        return action + "（未完成 " + unfinishedCount + "，高 " + highWatermark + "，低 " + lowWatermark + "）";
    }
}
