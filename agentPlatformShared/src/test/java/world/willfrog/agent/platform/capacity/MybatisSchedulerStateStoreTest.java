package world.willfrog.agent.platform.capacity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.SchedulerStateMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisSchedulerStateStore} 的判定接线：暂停标记从库里锁定读出来当输入，写进去的是判定结果。
 *
 * <p>这里只能证明「取哪个值当输入、把哪几个值交给写入语句」。两个线程同时跨越水位时最终状态等于
 * 库里当前值，靠真库场景验。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisSchedulerStateStoreTest {

    private static final int HIGH = 128;
    private static final int LOW = 96;

    @Mock
    private SchedulerStateMapper mapper;

    private MybatisSchedulerStateStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisSchedulerStateStore(mapper);
    }

    private SchedulerCapacityState state(boolean paused) {
        SchedulerCapacityState state = new SchedulerCapacityState();
        state.setScopeKey(MybatisSchedulerStateStore.GLOBAL_SCOPE);
        state.setUnfinishedCount(10);
        state.setAddPaused(paused);
        state.setHighWatermark(HIGH);
        state.setLowWatermark(LOW);
        return state;
    }





}
