package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TreeFairHintQueueTest {

    @Test
    void rotatesRootsBeforeRunsWithinEachRoot() {
        TreeFairHintQueue<String> queue = new TreeFairHintQueue<>(8);
        assertThat(queue.offer("A", "parent", "A-parent-1")).isTrue();
        assertThat(queue.offer("A", "parent", "A-parent-2")).isTrue();
        assertThat(queue.offer("A", "child", "A-child-1")).isTrue();
        assertThat(queue.offer("B", "other", "B-other-1")).isTrue();
        assertThat(queue.offer("B", "other", "B-other-2")).isTrue();

        assertThat(queue.poll()).isEqualTo("A-parent-1");
        assertThat(queue.poll()).isEqualTo("B-other-1");
        assertThat(queue.poll()).isEqualTo("A-child-1");
        assertThat(queue.poll()).isEqualTo("B-other-2");
        assertThat(queue.poll()).isEqualTo("A-parent-2");
        assertThat(queue.poll()).isNull();
        assertThat(queue.runBucketCount()).isZero();
    }

    @Test
    void queueKeepsOneGlobalCapacityAcrossAllRoots() {
        TreeFairHintQueue<String> queue = new TreeFairHintQueue<>(2);
        assertThat(queue.offer("A", "one", "first")).isTrue();
        assertThat(queue.offer("B", "two", "second")).isTrue();
        assertThat(queue.offer("C", "three", "rejected")).isFalse();
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.poll()).isEqualTo("first");
        assertThat(queue.offer("C", "three", "accepted")).isTrue();
        assertThat(queue.poll()).isEqualTo("second");
        assertThat(queue.poll()).isEqualTo("accepted");
    }
}
