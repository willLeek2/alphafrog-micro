package world.willfrog.agent.platform.capacity;

import java.time.OffsetDateTime;

/**
 * 背压的一次读数，四个数分开报。
 *
 * <p>这四个数不是相加关系：一个工作项可以在执行中、可以只有内存里的一条提示、也可以只有数据库里的一行，
 * 所以「数据库未完成数」不等于另外三个数之和，也不能拿其中一个去代表其余三个。</p>
 *
 * @param unfinishedWorkItemsInDb 数据库里未完成的工作项数量（唯一的领取权来源）
 * @param hintQueueDepth          内存提示队列里的元素个数（可丢、可重复）
 * @param reservedNotEnqueued     已经预留但还没入队的数量
 * @param perRunUnfinishedLimit   每个 Run 的未完成工作项上限
 * @param maxUnfinishedPerRun     当前所有 Run 里未完成工作项最多的那个数量
 */
public record SchedulerBackpressureSnapshot(
        int unfinishedWorkItemsInDb,
        int hintQueueDepth,
        int reservedNotEnqueued,
        int perRunUnfinishedLimit,
        int maxUnfinishedPerRun,
        OffsetDateTime takenAt) {

    public String describe() {
        return "数据库未完成工作项 " + unfinishedWorkItemsInDb
                + "，提示队列 " + hintQueueDepth
                + "，已预留未入队 " + reservedNotEnqueued
                + "，每个 Run 上限 " + perRunUnfinishedLimit
                + "（当前最多 " + maxUnfinishedPerRun + "）";
    }
}
