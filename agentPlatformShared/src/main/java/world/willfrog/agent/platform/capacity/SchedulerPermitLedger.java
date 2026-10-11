package world.willfrog.agent.platform.capacity;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三层名额的计数器：谁占用了就加一，交还了就减一，任何时候都读得出在用与可用。
 *
 * <p>它只管计数与读数，不管准入策略。业务准入今天由 Run 调度器决定谁进得来（权重容量那一套仍然管准入），
 * 准入通过时在这里记一次占用、Run 到终态或落库失败时记一次交还；协调许可与节点许可由两个池自己
 * 在拿到活的时候占用、交还的时候释放。三层各只有一个计数器，避免同一件事被记两遍。</p>
 *
 * <p>占用与交还必须成对：{@link #release} 减到负数会直接报错，因为那说明有人交还了两次，
 * 之后所有读数都会偏小。交还动作要放在 finally 里。</p>
 */
@Service
public class SchedulerPermitLedger {

    private final Map<SchedulerPermitLayer, AtomicInteger> inUse = new EnumMap<>(SchedulerPermitLayer.class);
    private final Map<SchedulerPermitLayer, AtomicInteger> limits = new EnumMap<>(SchedulerPermitLayer.class);

    public SchedulerPermitLedger() {
        for (SchedulerPermitLayer layer : SchedulerPermitLayer.values()) {
            inUse.put(layer, new AtomicInteger());
            limits.put(layer, new AtomicInteger(PermitUsage.UNLIMITED));
        }
    }

    /** 设一层名额的上限；{@link PermitUsage#UNLIMITED} 表示不限。 */
    public void setLimit(SchedulerPermitLayer layer, int limit) {
        if (limit != PermitUsage.UNLIMITED && limit < 0) {
            throw new IllegalArgumentException("名额上限不能是负数：" + limit);
        }
        limits.get(layer).set(limit);
    }

    /**
     * 试着占一层名额。返回 false 表示这一层已经满了——业务准入满了要对外表示忙，
     * 协调许可与节点许可满了要等下一轮扫描，都不能靠把活排进无界队列来消化。
     */
    public boolean tryAcquire(SchedulerPermitLayer layer) {
        AtomicInteger counter = inUse.get(layer);
        AtomicInteger limit = limits.get(layer);
        while (true) {
            int current = counter.get();
            int cap = limit.get();
            if (cap != PermitUsage.UNLIMITED && current >= cap) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 交还一层名额。没占用就交还会直接报错，避免把「多减一次」悄悄吃掉。 */
    public void release(SchedulerPermitLayer layer) {
        int now = inUse.get(layer).decrementAndGet();
        if (now < 0) {
            inUse.get(layer).incrementAndGet();
            throw new IllegalStateException("交还了没有占用的名额：" + layer.label() + "，占用与交还没有成对");
        }
    }

    public PermitUsage usage(SchedulerPermitLayer layer) {
        return PermitUsage.of(layer, inUse.get(layer).get(), limits.get(layer).get());
    }

    public SchedulerPermitSnapshot snapshot() {
        List<PermitUsage> list = new ArrayList<>(SchedulerPermitLayer.values().length);
        for (SchedulerPermitLayer layer : SchedulerPermitLayer.values()) {
            list.add(usage(layer));
        }
        return SchedulerPermitSnapshot.of(list);
    }
}
