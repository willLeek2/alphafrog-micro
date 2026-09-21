package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务所有权续期的行为：手上的租约趁没过期先续上，续上的比该续的少就说明被别人接手了。
 *
 * <p>这里量的是三件事：平常一轮只有一条批量续期语句（不按 Run 逐条刷）；差数出现时才逐条
 * 点名（代价只在异常那一轮）；这一轮出错不带走调度线程，下一轮照跑。</p>
 */
class RunServiceLeaseKeeperTest {

    private static final String OWNER = "instance-under-test";

    private RunServiceLeaseStore leaseStore;
    private ProcessInstanceIdentity identity;
    private RunServiceLeaseKeeper keeper;

    @BeforeEach
    void setUp() {
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        identity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(identity.value()).thenReturn(OWNER);
        keeper = new RunServiceLeaseKeeper(leaseStore, identity, 120L, 512, 40_000L);
    }

    /** 平常一轮：清点几条、批量续几条，两边对上就不再逐条看。 */
    @Test
    void aNormalRoundRenewsInOneStatement() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenReturn(List.of(lease("run-a", 1L), lease("run-b", 2L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(2);

        assertThat(keeper.renewOwnedOnce()).isEqualTo(2);

        verify(leaseStore).renewOwned(eq(OWNER), any(Duration.class));
        verify(leaseStore, never()).renew(anyString(), anyString(), Mockito.anyLong(), any(Duration.class));
        assertThat(keeper.snapshot())
                .containsEntry("serviceLeaseOwnedLastRound", 2)
                .containsEntry("serviceLeaseRenewedLastRound", 2)
                .containsEntry("serviceLeaseRenewedTotal", 2L)
                .containsEntry("serviceLeaseLostTotal", 0L)
                .containsEntry("serviceLeaseRenewRounds", 1L)
                .containsEntry("serviceLeaseTtlSeconds", 120L)
                .containsEntry("serviceLeaseRenewIntervalMs", 40_000L);
    }

    /**
     * 该续两条、只续上一条：有一条已经被别人按过期接手了。
     *
     * <p>逐条再试只是为了点名是哪一个——这一轮之后派发器不会再动它，它的代际号也已经作废。</p>
     */
    @Test
    void aShortfallMeansSomeLeasesWereTakenOver() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenReturn(List.of(lease("run-mine", 1L), lease("run-taken", 3L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(1);
        when(leaseStore.renew(eq("run-mine"), eq(OWNER), eq(1L), any(Duration.class))).thenReturn(true);
        when(leaseStore.renew(eq("run-taken"), eq(OWNER), eq(3L), any(Duration.class))).thenReturn(false);

        assertThat(keeper.renewOwnedOnce()).isEqualTo(1);

        assertThat(keeper.snapshot())
                .as("被接手的那一条记在读数里，能查")
                .containsEntry("serviceLeaseOwnedLastRound", 2)
                .containsEntry("serviceLeaseRenewedLastRound", 1)
                .containsEntry("serviceLeaseLostTotal", 1L);
    }

    /** 手上没有活租约时不发那条批量续期语句：每 40 秒白写一次没意义。 */
    @Test
    void nothingOwnedWritesNothing() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512)).thenReturn(List.of());

        assertThat(keeper.renewOwnedOnce()).isZero();

        verify(leaseStore, never()).renewOwned(anyString(), any(Duration.class));
        assertThat(keeper.snapshot())
                .containsEntry("serviceLeaseOwnedLastRound", 0)
                .containsEntry("serviceLeaseRenewRounds", 1L);
    }

    /** 这一轮出错只记一笔，下一轮照跑：续期失败不能让调度停摆。 */
    @Test
    void aFailingRoundIsSwallowedAndTheNextRoundStillRuns() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenThrow(new IllegalStateException("库读不了"))
                .thenReturn(List.of(lease("run-a", 1L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(1);

        assertThat(keeper.safeRenew()).as("读库就失败了：这一轮没有续上任何一条").isZero();
        assertThat(keeper.snapshot())
                .containsEntry("serviceLeaseRenewFailuresTotal", 1L)
                .containsEntry("serviceLeaseRenewRounds", 1L);

        assertThat(keeper.safeRenew()).as("下一轮照跑").isEqualTo(1);
        assertThat(keeper.snapshot())
                .containsEntry("serviceLeaseRenewFailuresTotal", 1L)
                .containsEntry("serviceLeaseRenewRounds", 2L);
    }

    /** 上一轮还没走完时这一拍直接跳过：启动入口与周期入口不并发续同一批。 */
    @Test
    void overlappingRoundsDoNotRenewTwice() throws Exception {
        CountDownLatch inRound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512)).thenAnswer(invocation -> {
            inRound.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(lease("run-a", 1L));
        });
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(keeper::safeRenew);
            assertThat(inRound.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(keeper.safeRenew()).as("上一轮还在走：这一拍不进来").isZero();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(keeper.snapshot())
                    .containsEntry("serviceLeaseRenewSkippedRounds", 1L)
                    .containsEntry("serviceLeaseRenewRounds", 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 清点条数上限由配置给：一次只认领有界的一页，不把整张表读进内存。 */
    @Test
    void theOwnedListingIsBounded() {
        RunServiceLeaseKeeper bounded = new RunServiceLeaseKeeper(leaseStore, identity, 60L, 7, 20_000L);
        when(leaseStore.listOwnedWithLiveRun(OWNER, 7)).thenReturn(List.of());
        bounded.renewOwnedOnce();
        verify(leaseStore).listOwnedWithLiveRun(OWNER, 7);
        assertThat(bounded.snapshot())
                .containsEntry("serviceLeaseTtlSeconds", 60L)
                .containsEntry("serviceLeaseRenewIntervalMs", 20_000L);
    }

    private static RunServiceLease lease(String runId, long fencingToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunServiceLease(runId, OWNER, fencingToken, now, now, now.plusMinutes(2));
    }
}
