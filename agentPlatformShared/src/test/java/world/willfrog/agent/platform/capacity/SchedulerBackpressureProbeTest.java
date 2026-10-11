package world.willfrog.agent.platform.capacity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 背压四个数分开报，而且读数带短缓存，不能把库查穿。
 */
@ExtendWith(MockitoExtension.class)
class SchedulerBackpressureProbeTest {

    @Mock
    private NodeWorkItemStore store;

    private static HintQueueDepthSource queue(int depth, int reserved) {
        return new HintQueueDepthSource() {
            @Override
            public int hintQueueDepth() {
                return depth;
            }

            @Override
            public int reservedNotEnqueued() {
                return reserved;
            }
        };
    }

    @Test
    void reportsFourSeparateNumbers() {
        when(store.countUnfinished()).thenReturn(3);
        when(store.maxUnfinishedPerRun()).thenReturn(2);

        SchedulerBackpressureProbe probe = new SchedulerBackpressureProbe(store, queue(1, 0), () -> 4);
        SchedulerBackpressureSnapshot snapshot = probe.snapshot();

        assertThat(snapshot.unfinishedWorkItemsInDb()).isEqualTo(3);
        assertThat(snapshot.hintQueueDepth()).isEqualTo(1);
        assertThat(snapshot.reservedNotEnqueued()).isZero();
        assertThat(snapshot.perRunUnfinishedLimit()).isEqualTo(4);
        assertThat(snapshot.maxUnfinishedPerRun()).isEqualTo(2);
        assertThat(snapshot.describe())
                .as("四个数各自写清楚，不能合并成一句队列深度")
                .contains("数据库未完成工作项 3", "提示队列 1", "已预留未入队 0", "每个 Run 上限 4");
    }

    @Test
    void secondReadInsideTtlDoesNotHitTheDatabaseAgain() {
        when(store.countUnfinished()).thenReturn(1);
        when(store.maxUnfinishedPerRun()).thenReturn(1);

        SchedulerBackpressureProbe probe = new SchedulerBackpressureProbe(store, queue(0, 0), () -> 4,
                Duration.ofMinutes(1));
        probe.snapshot();
        probe.snapshot();

        verify(store, times(1)).countUnfinished();
        verify(store, times(1)).maxUnfinishedPerRun();
    }

    @Test
    void refreshAlwaysReadsAgain() {
        when(store.countUnfinished()).thenReturn(1, 2);
        when(store.maxUnfinishedPerRun()).thenReturn(1, 2);

        SchedulerBackpressureProbe probe = new SchedulerBackpressureProbe(store, queue(0, 0), () -> 4,
                Duration.ofMinutes(1));
        assertThat(probe.snapshot().unfinishedWorkItemsInDb()).isEqualTo(1);
        assertThat(probe.refresh().unfinishedWorkItemsInDb()).isEqualTo(2);
        verify(store, times(2)).countUnfinished();
    }

    @Test
    void missingHintQueueSourceIsTreatedAsZeroNotAsAValue() {
        when(store.countUnfinished()).thenReturn(0);
        when(store.maxUnfinishedPerRun()).thenReturn(0);
        SchedulerBackpressureProbe probe = new SchedulerBackpressureProbe(store, null, () -> 4);
        assertThat(probe.snapshot().hintQueueDepth()).isZero();
        assertThat(probe.snapshot().reservedNotEnqueued()).isZero();
    }

    @Test
    void thePerRunLimitIsReadWhenItIsUsed() {
        // 上限允许在运行期改：读数取的是「现在」的值，不是构造时冻住的那个。
        int[] limit = {4};
        SchedulerBackpressureProbe probe = new SchedulerBackpressureProbe(store, queue(0, 0), () -> limit[0],
                Duration.ZERO);
        assertThat(probe.snapshot().perRunUnfinishedLimit()).isEqualTo(4);
        limit[0] = 9;
        assertThat(probe.refresh().perRunUnfinishedLimit()).isEqualTo(9);
    }

    @Test
    void aMissingPerRunLimitSourceIsRejected() {
        assertThatThrownBy(() -> new SchedulerBackpressureProbe(store, queue(0, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
