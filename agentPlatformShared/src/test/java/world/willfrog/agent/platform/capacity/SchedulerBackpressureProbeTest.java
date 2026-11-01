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






}
