package world.willfrog.agentlangchain.orchestration.scheduler;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 优先级感知的有界等待队列：同一优先级内 FIFO，不同优先级轮转取队首。
 *
 * <p>轮转（round-robin）而不是严格高优先级优先，从结构上保证以后引入多个
 * 优先级时低优先级不会被永久饿死。当前只有 NORMAL 一个等级，行为退化为
 * 单队列 FIFO。本类只在 scheduler 锁内使用，不需要内部并发控制。</p>
 */
public class RunPriorityQueue<E> {

    // 插入顺序即 RunPriority 枚举声明顺序；轮转游标在该顺序上循环。
    private final Map<RunPriority, ArrayDeque<E>> lanes = new LinkedHashMap<>();
    private RunPriority cursor;

    public RunPriorityQueue() {
        for (RunPriority priority : RunPriority.values()) {
            lanes.put(priority, new ArrayDeque<>());
        }
        cursor = RunPriority.values()[0];
    }

    public void enqueue(RunPriority priority, E element) {
        lanes.get(priority).addLast(element);
    }

    /** 提升被物理线程池暂时拒绝的任务时放回原车道队首，保持 FIFO 不跳队。 */
    public void enqueueFront(RunPriority priority, E element) {
        lanes.get(priority).addFirst(element);
    }

    public E poll() {
        // 从上次出队位置的下一车道开始轮转，避免高优先级独占出队权。
        for (int i = 0; i < lanes.size(); i++) {
            RunPriority candidate = laneAt((cursor.ordinal() + i) % lanes.size());
            ArrayDeque<E> lane = lanes.get(candidate);
            if (!lane.isEmpty()) {
                cursor = candidate;
                return lane.pollFirst();
            }
        }
        return null;
    }

    public E peek() {
        for (int i = 0; i < lanes.size(); i++) {
            RunPriority candidate = laneAt((cursor.ordinal() + i) % lanes.size());
            ArrayDeque<E> lane = lanes.get(candidate);
            if (!lane.isEmpty()) {
                return lane.peekFirst();
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return lanes.values().stream().allMatch(ArrayDeque::isEmpty);
    }

    public int size() {
        return lanes.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    /** 按条件移除元素（排队取消用）。返回 true 表示至少移除一个。 */
    public boolean removeIf(Predicate<E> predicate) {
        boolean removed = false;
        for (ArrayDeque<E> lane : lanes.values()) {
            removed |= lane.removeIf(predicate);
        }
        return removed;
    }

    private RunPriority laneAt(int ordinal) {
        return RunPriority.values()[ordinal];
    }
}
