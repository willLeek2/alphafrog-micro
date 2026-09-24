package world.willfrog.agent.platform.coordination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisRunCoordinationStore} 的入参自检、影响行数解读，以及「栅栏值真的传下去了」。
 *
 * <p>这里只能证明 Java 侧把哪些值交给了语句。条件更新在并发下到底影响几行，靠真库场景验，
 * 不靠这些桩。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisRunCoordinationStoreTest {

    /** Run 级写入要带的服务所有权凭据：这里只证明它被原样交给语句。 */
    private static final ServiceOwnershipFence FENCE = new ServiceOwnershipFence("instance-a", 7L);

    @Mock
    private RunCoordinationMapper mapper;

    private MybatisRunCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisRunCoordinationStore(mapper);
    }

    @Test
    void ensureReportsWhetherARowWasCreated() {
        when(mapper.ensure(anyString())).thenReturn(1);
        assertThat(store.ensure("run-1")).isTrue();
        when(mapper.ensure(anyString())).thenReturn(0);
        assertThat(store.ensure("run-1"))
                .as("已经有资格记录时返回 false，不重新建")
                .isFalse();
    }

    @Test
    void deferCarriesTheFencesItWasGiven() {
        OffsetDateTime nextVisibleAt = OffsetDateTime.now().plusSeconds(5);
        when(mapper.deferFor(anyString(), anyString(), any(), anyInt(), anyLong(), anyString(), anyLong())).thenReturn(1);

        assertThat(store.deferFor("run-1", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                nextVisibleAt, 3, 7L, FENCE)).isTrue();

        verify(mapper).deferFor("run-1", "PER_RUN_UNFINISHED_LIMIT", nextVisibleAt, 3, 7L,
                FENCE.ownerInstanceId(), FENCE.fencingToken());
    }

    @Test
    void deferWithoutAnEligibilityRowReportsFalse() {
        when(mapper.deferFor(anyString(), anyString(), any(), anyInt(), anyLong(), anyString(), anyLong())).thenReturn(0);
        assertThat(store.deferFor("run-1", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                OffsetDateTime.now(), 0, 0L, FENCE)).isFalse();
    }

    @Test
    void coordinationServedRoundOnlyTouchesTheCoordinationSpace() {
        when(mapper.markCoordinationServed(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenReturn(1);
        assertThat(store.markCoordinationServed("run-1", 7L, 4, FENCE)).isTrue();
        verify(mapper).markCoordinationServed("run-1", 7L, 4, FENCE.ownerInstanceId(), FENCE.fencingToken());
        // 这一路只碰协调那一组轮转位置，两个轮次空间各记各的。
        verify(mapper, never()).markDispatchServed(anyString(), anyLong(), anyInt(), anyString(), anyLong());
    }

    @Test
    void dispatchServedRoundOnlyTouchesTheDispatchSpace() {
        when(mapper.markDispatchServed(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenReturn(0);
        assertThat(store.markDispatchServed("run-1", 9L, 4, FENCE))
                .as("迟到的旧轮次写进去影响 0 行，返回 false")
                .isFalse();
        verify(mapper).markDispatchServed("run-1", 9L, 4, FENCE.ownerInstanceId(), FENCE.fencingToken());
        verify(mapper, never()).markCoordinationServed(anyString(), anyLong(), anyInt(), anyString(), anyLong());
    }

    @Test
    void missedRoundsRefreshKeepsTheTwoSpacesApartAndStaysGlobal() {
        when(mapper.refreshCoordinationMissedRounds(anyLong())).thenReturn(2);
        when(mapper.refreshDispatchMissedRounds(anyLong())).thenReturn(3);
        assertThat(store.refreshCoordinationMissedRounds(7L)).isEqualTo(2);
        assertThat(store.refreshDispatchMissedRounds(7L)).isEqualTo(3);
        // 一次全局刷新：不按调度器版本分池，版本由每条记录自己带着。
        verify(mapper).refreshCoordinationMissedRounds(7L);
        verify(mapper).refreshDispatchMissedRounds(7L);
    }

    @Test
    void scanDueIsOneGlobalPassOverAllKnownVersions() {
        RunCoordination legacy = row("run-legacy", "LEGACY");
        RunCoordination v2 = row("run-v2", "DUAL_POOL_V2");
        when(mapper.scanDue(anyInt())).thenReturn(List.of(legacy, v2));

        assertThat(store.scanDue(8))
                .as("旧版本与两个双池版本排在同一份候选里，按记录自己的版本路由")
                .containsExactly(legacy, v2);
        verify(mapper).scanDue(8);
    }

    @Test
    void syncOnlyForwardsThePlanGeneration() {
        when(mapper.syncPlanGeneration(anyString(), anyInt(), anyString(), anyLong())).thenReturn(1);
        assertThat(store.syncPlanGeneration("run-1", 2, FENCE)).isTrue();
        verify(mapper).syncPlanGeneration("run-1", 2, FENCE.ownerInstanceId(), FENCE.fencingToken());

        when(mapper.syncPlanGeneration(anyString(), anyInt(), anyString(), anyLong())).thenReturn(0);
        assertThat(store.syncPlanGeneration("run-1", 1, FENCE))
                .as("代际倒退或与 Run 主表不一致时影响 0 行")
                .isFalse();
    }

    @Test
    void invalidArgumentsNeverReachTheMapper() {
        assertThatThrownBy(() -> store.ensure(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1", null, OffsetDateTime.now(), 0, 0L, FENCE))
                .as("延期必须带原因")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1",
                RunCoordinationDeferReason.PER_ROUND_NEW_NODE_LIMIT, null, 0, 0L, FENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1",
                RunCoordinationDeferReason.PER_ROUND_NEW_NODE_LIMIT, OffsetDateTime.now(), -2, 0L, FENCE))
                .as("计划代际最小只能是 -1（还没有计划）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deferFor("run-1",
                RunCoordinationDeferReason.PER_ROUND_NEW_NODE_LIMIT, OffsetDateTime.now(), 0, -1L, FENCE))
                .as("轮次号不能是负数")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scanDue(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.markCoordinationServed("run-1", -1L, 0, FENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.markDispatchServed("run-1", 3L, -2, FENCE))
                .as("计划代际最小只能是 -1（还没有计划）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.refreshDispatchMissedRounds(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.syncPlanGeneration("run-1", -2, FENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.find(" ")).isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).scanDue(anyInt());
    }

    @Test
    void runLevelWritesRefuseToGoWithoutAnOwnershipFence() {
        assertThatThrownBy(() -> store.deferFor("run-1",
                RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT, OffsetDateTime.now(), 0, 0L, null))
                .as("Run 级推进没有所有权凭据时当场拒绝，不交给语句")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.markCoordinationServed("run-1", 1L, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.markDispatchServed("run-1", 1L, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.syncPlanGeneration("run-1", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handoffBookkeepingDoesNotCarryOwnership() {
        // 旧版本 Run 交给旧入口这一步由「派发决定」授权，不是所有权：它走单独的语句，不带凭据。
        when(mapper.markHandoffServed(anyString(), anyLong(), anyInt())).thenReturn(1);
        when(mapper.deferHandoff(anyString(), anyString(), any(), anyInt(), anyLong())).thenReturn(1);

        assertThat(store.markHandoffServed("run-legacy", 5L, 2)).isTrue();
        assertThat(store.deferHandoff("run-legacy", RunCoordinationDeferReason.SERVICE_OWNERSHIP_ELSEWHERE,
                OffsetDateTime.now(), 2, 5L)).isTrue();

        verify(mapper).markHandoffServed("run-legacy", 5L, 2);
    }

    private static RunCoordination row(String runId, String schedulerVersion) {
        RunCoordination row = new RunCoordination();
        row.setRunId(runId);
        row.setSchedulerVersion(schedulerVersion);
        return row;
    }
}
