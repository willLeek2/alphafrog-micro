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
 * {@link MybatisSchedulerStateStore} 的判定接线：暂停标记从库里读出来当输入，写进去的是判定结果。
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

    @Test
    void pausedStateFromDatabaseIsTheInputOfTheDecision() {
        when(mapper.loadCapacityState(anyString())).thenReturn(state(true));
        when(mapper.applyCapacityDecision(anyString(), anyLong(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(state(true));
        SchedulerPauseDecision decision = store.decideAndRecord(100, HIGH, LOW);
        assertThat(decision.paused())
                .as("库里已经暂停且数量还在高低水位之间，必须保持暂停")
                .isTrue();
        assertThat(decision.changed()).isFalse();
        verify(mapper).applyCapacityDecision(MybatisSchedulerStateStore.GLOBAL_SCOPE, 100L, true, HIGH, LOW);
    }

    @Test
    void fallingToLowWatermarkResumesAndWritesTheFlag() {
        when(mapper.loadCapacityState(anyString())).thenReturn(state(true));
        when(mapper.applyCapacityDecision(anyString(), anyLong(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(state(false));
        SchedulerPauseDecision decision = store.decideAndRecord(10, HIGH, LOW);
        assertThat(decision.paused()).isFalse();
        assertThat(decision.changed()).isTrue();
        verify(mapper).applyCapacityDecision(MybatisSchedulerStateStore.GLOBAL_SCOPE, 10L, false, HIGH, LOW);
    }

    @Test
    void missingGlobalRowFailsClosed() {
        when(mapper.loadCapacityState(anyString())).thenReturn(null);
        assertThatThrownBy(() -> store.decideAndRecord(1, HIGH, LOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GLOBAL");
        verify(mapper, never()).applyCapacityDecision(anyString(), anyLong(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void storedValueDisagreeingWithTheDecisionFailsClosed() {
        when(mapper.loadCapacityState(anyString())).thenReturn(state(false));
        when(mapper.applyCapacityDecision(anyString(), anyLong(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(state(false));
        assertThatThrownBy(() -> store.decideAndRecord(200, HIGH, LOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void roundAdvanceReturnsTheDatabaseValue() {
        when(mapper.advanceRound(eq(SchedulerRoundScope.NODE_DISPATCH.name()))).thenReturn(12L);
        assertThat(store.advanceRound(SchedulerRoundScope.NODE_DISPATCH)).isEqualTo(12L);
        when(mapper.currentRound(eq(SchedulerRoundScope.RUN_COORDINATION.name()))).thenReturn(null);
        assertThat(store.currentRound(SchedulerRoundScope.RUN_COORDINATION))
                .as("还没有记录时按第 0 轮算")
                .isZero();
        assertThatThrownBy(() -> store.advanceRound(null))
                .isInstanceOf(IllegalArgumentException.class);
        when(mapper.advanceRound(eq(SchedulerRoundScope.RUN_COORDINATION.name()))).thenReturn(null);
        assertThatThrownBy(() -> store.advanceRound(SchedulerRoundScope.RUN_COORDINATION))
                .as("轮次记录不存在时不能猜一个轮次号")
                .isInstanceOf(IllegalStateException.class);
    }
}
