package world.willfrog.agent.platform.capacity;

import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.IntSupplier;

/**
 * 读背压四个数：数据库未完成工作项数、提示队列元素数、已预留未入队数、每个 Run 的未完成工作项上限。
 *
 * <p>其中数据库那一个要查库，所以读数带一层短缓存：默认 5 秒内重复调用返回同一个快照，避免指标抓取
 * 或诊断日志把库查穿。要拿最新值就调 {@link #refresh()}。</p>
 *
 * <p>提示队列的两个数由队列实现通过 {@link HintQueueDepthSource} 提供；内存队列是可丢的唤醒通道，
 * 这两个数只用于观测与背压，不用来判断有没有活。</p>
 */
public class SchedulerBackpressureProbe {

    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(5);

    private final NodeWorkItemStore store;
    private final HintQueueDepthSource hintQueue;
    /** 每个 Run 的未完成工作项上限：按需取，不在这里冻结——这个值允许在运行期改。 */
    private final IntSupplier perRunUnfinishedLimit;
    private final Duration cacheTtl;

    private volatile SchedulerBackpressureSnapshot cached;

    public SchedulerBackpressureProbe(NodeWorkItemStore store,
                                      HintQueueDepthSource hintQueue,
                                      IntSupplier perRunUnfinishedLimit) {
        this(store, hintQueue, perRunUnfinishedLimit, DEFAULT_CACHE_TTL);
    }

    public SchedulerBackpressureProbe(NodeWorkItemStore store,
                                      HintQueueDepthSource hintQueue,
                                      IntSupplier perRunUnfinishedLimit,
                                      Duration cacheTtl) {
        if (store == null) {
            throw new IllegalArgumentException("工作项存储不能为空");
        }
        if (perRunUnfinishedLimit == null) {
            throw new IllegalArgumentException("每个 Run 的未完成工作项上限取不到");
        }
        this.store = store;
        this.hintQueue = hintQueue == null ? HintQueueDepthSource.empty() : hintQueue;
        this.perRunUnfinishedLimit = perRunUnfinishedLimit;
        this.cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
    }

    /** 读一次快照；缓存没到期就直接返回上一次的读数。 */
    public SchedulerBackpressureSnapshot snapshot() {
        SchedulerBackpressureSnapshot current = cached;
        if (current != null && current.takenAt().plus(cacheTtl).isAfter(OffsetDateTime.now())) {
            return current;
        }
        return refresh();
    }

    /** 强制重新读一次。 */
    public SchedulerBackpressureSnapshot refresh() {
        SchedulerBackpressureSnapshot fresh = new SchedulerBackpressureSnapshot(
                store.countUnfinished(),
                hintQueue.hintQueueDepth(),
                hintQueue.reservedNotEnqueued(),
                perRunUnfinishedLimit.getAsInt(),
                store.maxUnfinishedPerRun(),
                OffsetDateTime.now());
        cached = fresh;
        return fresh;
    }

    public int perRunUnfinishedLimit() {
        return perRunUnfinishedLimit.getAsInt();
    }
}
