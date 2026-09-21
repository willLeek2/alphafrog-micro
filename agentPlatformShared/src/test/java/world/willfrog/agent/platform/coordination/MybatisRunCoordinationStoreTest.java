package world.willfrog.agent.platform.coordination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisRunCoordinationStore} 的入参自检与影响行数解读。
 */
@ExtendWith(MockitoExtension.class)
class MybatisRunCoordinationStoreTest {

    @Mock
    private RunCoordinationMapper mapper;

    private MybatisRunCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisRunCoordinationStore(mapper);
    }

    @Test
    void ensureReportsWhetherARowWasCreated() {
        when(mapper.ensure(anyString(), anyString(), anyInt())).thenReturn(1);
        assertThat(store.ensure("run-1", SchedulerVersion.DUAL_POOL_V2, 0)).isTrue();
        when(mapper.ensure(anyString(), anyString(), anyInt())).thenReturn(0);
        assertThat(store.ensure("run-1", SchedulerVersion.DUAL_POOL_V2, 0)).isFalse();
    }

    @Test
    void deferWithoutAnEligibilityRowReportsFalse() {
        when(mapper.deferFor(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        assertThat(store.deferFor("run-1", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                OffsetDateTime.now())).isFalse();
    }

    @Test
    void markCoordinationServedRecordsTheCoordinationRound() {
        when(mapper.markCoordinationServed(anyString(), anyLong())).thenReturn(1);
        assertThat(store.markCoordinationServed("run-1", 7L)).isTrue();
        verify(mapper).markCoordinationServed("run-1", 7L);
    }

    @Test
    void markDispatchServedRecordsTheDispatchRoundSeparately() {
        when(mapper.markDispatchServed(anyString(), anyLong())).thenReturn(1);
        assertThat(store.markDispatchServed("run-1", 9L)).isTrue();
        verify(mapper).markDispatchServed("run-1", 9L);
        // 派发不替协调写延期原因，两个轮次空间各记各的。
        verify(mapper, never()).markCoordinationServed(anyString(), anyLong());
    }

    @Test
    void missedRoundsRefreshKeepsTheTwoSpacesApart() {
        when(mapper.refreshCoordinationMissedRounds(anyString(), anyLong())).thenReturn(2);
        when(mapper.refreshDispatchMissedRounds(anyString(), anyLong())).thenReturn(3);
        assertThat(store.refreshCoordinationMissedRounds(SchedulerVersion.DUAL_POOL_V2, 7L))
                .isEqualTo(2);
        assertThat(store.refreshDispatchMissedRounds(SchedulerVersion.DUAL_POOL_V2, 7L))
                .isEqualTo(3);
        verify(mapper).refreshCoordinationMissedRounds("DUAL_POOL_V2", 7L);
        verify(mapper).refreshDispatchMissedRounds("DUAL_POOL_V2", 7L);
    }

    @Test
    void scanDuePassesVersionAndLimit() {
        RunCoordination row = new RunCoordination();
        row.setRunId("run-1");
        when(mapper.scanDue(anyString(), anyInt())).thenReturn(List.of(row));
        assertThat(store.scanDue(SchedulerVersion.DUAL_POOL_V2, 8))
                .as("候选 Run 按调度器版本过滤，两代版本各自轮转")
                .containsExactly(row);
        verify(mapper).scanDue("DUAL_POOL_V2", 8);
    }

    @Test
    void invalidArgumentsNeverReachTheMapper() {
        assertThatThrownBy(() -> store.ensure(" ", SchedulerVersion.DUAL_POOL_V2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.ensure("run-1", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.ensure("run-1", SchedulerVersion.DUAL_POOL_V2, -2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1", null, OffsetDateTime.now()))
                .as("延期必须带原因")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1",
                RunCoordinationDeferReason.PER_ROUND_NEW_NODE_LIMIT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scanDue(SchedulerVersion.DUAL_POOL_V2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.find(" "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).scanDue(anyString(), anyInt());
    }
}
