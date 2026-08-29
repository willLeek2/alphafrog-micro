package world.willfrog.agentlangchain.control.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link RunCapacityLedger} 的进程内实现。
 *
 * <p>usedUnits 是原子累加器，acquire 用 CAS 循环保证不会超卖；
 * maxUnits 从配置读取（默认 4），当前不做热更新。</p>
 */
@Component
public class InProcessRunCapacityLedger implements RunCapacityLedger {

    private final ConcurrentMap<Object, Integer> held = new ConcurrentHashMap<>();
    private final AtomicInteger usedUnits = new AtomicInteger();

    private final int maxUnits;

    public InProcessRunCapacityLedger(
            @Value("${agent.langchain.run.scheduler.max-capacity-units:4}") int maxUnits) {
        if (maxUnits < 1) {
            throw new IllegalArgumentException(
                    "agent.langchain.run.scheduler.max-capacity-units must be >= 1, got " + maxUnits);
        }
        this.maxUnits = maxUnits;
    }

    @Override
    public boolean tryAcquire(Object key, int weight) {
        if (key == null) {
            return false;
        }
        if (weight < 1) {
            throw new IllegalArgumentException("run weight must be >= 1, got " + weight);
        }
        if (weight > maxUnits) {
            // 单个 Run 权重超过总容量时永远无法执行；拒绝而不是死等。
            return false;
        }
        // CAS 循环：读-算-写必须原子，否则两个并发 acquire 可能同时看到同一剩余容量。
        while (true) {
            int current = usedUnits.get();
            int next = current + weight;
            if (next > maxUnits) {
                return false;
            }
            if (usedUnits.compareAndSet(current, next)) {
                Object previous = held.putIfAbsent(key, weight);
                if (previous != null) {
                    // 同一 key 重复 acquire：回滚本次计数，保持账本与持有表一致。
                    usedUnits.addAndGet(-weight);
                    return false;
                }
                return true;
            }
        }
    }

    @Override
    public void release(Object key) {
        if (key == null) {
            return;
        }
        Integer weight = held.remove(key);
        if (weight == null) {
            // 未知 key 或重复 release：幂等无害。
            return;
        }
        usedUnits.addAndGet(-weight);
    }

    @Override
    public int usedUnits() {
        return Math.max(0, usedUnits.get());
    }

    @Override
    public int maxUnits() {
        return maxUnits;
    }
}
