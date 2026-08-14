package world.willfrog.agentlangchain.orchestration.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunPriorityQueueTest {

    @Test
    void singleLanePreservesFifoOrder() {
        RunPriorityQueue<String> queue = new RunPriorityQueue<>();

        queue.enqueue(RunPriority.NORMAL, "run-1");
        queue.enqueue(RunPriority.NORMAL, "run-2");
        queue.enqueue(RunPriority.NORMAL, "run-3");

        // 同一优先级内严格 FIFO：先来先服务，不存在饥饿。
        assertThat(queue.peek()).isEqualTo("run-1");
        assertThat(queue.poll()).isEqualTo("run-1");
        assertThat(queue.poll()).isEqualTo("run-2");
        assertThat(queue.poll()).isEqualTo("run-3");
        assertThat(queue.poll()).isNull();
    }

    @Test
    void sizeAndIsEmptyTrackAllLanes() {
        RunPriorityQueue<String> queue = new RunPriorityQueue<>();

        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();

        queue.enqueue(RunPriority.NORMAL, "a");
        queue.enqueue(RunPriority.NORMAL, "b");
        assertThat(queue.isEmpty()).isFalse();
        assertThat(queue.size()).isEqualTo(2);

        queue.poll();
        queue.poll();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void peekDoesNotRemoveHead() {
        RunPriorityQueue<String> queue = new RunPriorityQueue<>();

        queue.enqueue(RunPriority.NORMAL, "run-1");
        queue.enqueue(RunPriority.NORMAL, "run-2");

        assertThat(queue.peek()).isEqualTo("run-1");
        assertThat(queue.peek()).isEqualTo("run-1");
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void removeIfRemovesMatchingEntriesAcrossLanes() {
        RunPriorityQueue<String> queue = new RunPriorityQueue<>();

        queue.enqueue(RunPriority.NORMAL, "run-1");
        queue.enqueue(RunPriority.NORMAL, "run-2");
        queue.enqueue(RunPriority.NORMAL, "run-3");

        assertThat(queue.removeIf(element -> "run-2".equals(element))).isTrue();
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.poll()).isEqualTo("run-1");
        assertThat(queue.poll()).isEqualTo("run-3");

        assertThat(queue.removeIf(element -> true)).isFalse();
    }
}
