package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务所有权续期的行为：手上的租约趁没过期先续上，哪一条续不上就说明那一条被别人接手了。
 *
 * <p>这里量的是四件事：清点是有界的一页，整批续期负责把这一页之外的租约也续上；判定是逐条按
 * 代际号条件续期，不看条数对比；续不上的当场撤销本进程的准入；这一轮出错不带走调度线程，下一轮照跑。</p>
 */
class RunServiceLeaseKeeperTest {

    private static final String OWNER = "instance-under-test";

    private RunServiceLeaseStore leaseStore;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private ProcessInstanceIdentity identity;
    private RunServiceLeaseKeeper keeper;
    private FrozenEffectiveSettings frozenEffectiveSettings;

    @BeforeEach
    void setUp() {
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        identity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(identity.value()).thenReturn(OWNER);
        frozenEffectiveSettings = new FrozenEffectiveSettings();
        keeper = new RunServiceLeaseKeeper(leaseStore, admissionRegistry, identity, 120L, 512, 40_000L,
                frozenEffectiveSettings);
    }

    /**
     * 平常一轮：整批续一次（把有界那一页之外的租约也续上），再逐条按各自的代际号续。
     *
     * <p>条数对不上不说明问题——整批续期续的是本进程全部租约，清点的只有一页；所以判定只能逐条做。</p>
     */
    @Test
    void aNormalRoundRenewsEachOwnedLeaseByItsToken() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenReturn(List.of(lease("run-a", 1L), lease("run-b", 2L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(2);
        when(leaseStore.renew(eq("run-a"), eq(OWNER), eq(1L), any(Duration.class))).thenReturn(true);
        when(leaseStore.renew(eq("run-b"), eq(OWNER), eq(2L), any(Duration.class))).thenReturn(true);

        assertThat(keeper.renewOwnedOnce()).isEqualTo(2);

        verify(leaseStore).renewOwned(eq(OWNER), any(Duration.class));
        verify(leaseStore).renew(eq("run-a"), eq(OWNER), eq(1L), any(Duration.class));
        verify(leaseStore).renew(eq("run-b"), eq(OWNER), eq(2L), any(Duration.class));
        verify(admissionRegistry, never()).revokeOwnership(anyString(), anyLong());
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
     * 该续两条、按代际号只续上一条：没续上的那一条已经被别人按过期接手了。
     *
     * <p>点名与撤销都发生在这一刻——这一轮之后派发器不会再动它，它的代际号也已经作废。</p>
     */
    @Test
    void aLeaseThatDoesNotRenewByItsTokenWasTakenOver() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenReturn(List.of(lease("run-mine", 1L), lease("run-taken", 3L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(2);
        when(leaseStore.renew(eq("run-mine"), eq(OWNER), eq(1L), any(Duration.class))).thenReturn(true);
        when(leaseStore.renew(eq("run-taken"), eq(OWNER), eq(3L), any(Duration.class))).thenReturn(false);
        when(admissionRegistry.revokeOwnership("run-taken", 3L)).thenReturn(true);

        assertThat(keeper.renewOwnedOnce()).isEqualTo(1);

        assertThat(keeper.snapshot())
                .as("被接手的那一条记在读数里，能查")
                .containsEntry("serviceLeaseOwnedLastRound", 2)
                .containsEntry("serviceLeaseRenewedLastRound", 1)
                .containsEntry("serviceLeaseLostTotal", 1L)
                .containsEntry("serviceLeaseLostRevokedTotal", 1L);
        verify(admissionRegistry).revokeOwnership("run-taken", 3L);
        verify(admissionRegistry, never()).revokeOwnership(eq("run-mine"), anyLong());
    }

    /**
     * 撤销按代际号条件生效：本进程先被别人接手、之后又重新拿到这条 Run 时，旧代际号的撤销
     * 不该把新的生命周期删掉。
     */
    @Test
    void aRevocationThatDoesNotMatchTheCurrentTokenLeavesTheNewerLifecycleAlone() {
        when(leaseStore.listOwnedWithLiveRun(OWNER, 512))
                .thenReturn(List.of(lease("run-back", 9L)));
        when(leaseStore.renewOwned(eq(OWNER), any(Duration.class))).thenReturn(0);
        when(leaseStore.renew(eq("run-back"), eq(OWNER), eq(9L), any(Duration.class))).thenReturn(false);
        when(admissionRegistry.revokeOwnership("run-back", 9L)).thenReturn(false);

        keeper.renewOwnedOnce();

        assertThat(keeper.snapshot())
                .as("没撤掉的不算撤销数")
                .containsEntry("serviceLeaseLostRevokedTotal", 0L);
        verify(admissionRegistry).revokeOwnership("run-back", 9L);
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
        when(leaseStore.renew(eq("run-a"), eq(OWNER), eq(1L), any(Duration.class))).thenReturn(true);

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
        when(leaseStore.renew(eq("run-a"), eq(OWNER), eq(1L), any(Duration.class))).thenReturn(true);

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
        RunServiceLeaseKeeper bounded = new RunServiceLeaseKeeper(
                leaseStore, admissionRegistry, identity, 60L, 7, 20_000L, frozenEffectiveSettings);
        when(leaseStore.listOwnedWithLiveRun(OWNER, 7)).thenReturn(List.of());
        bounded.renewOwnedOnce();
        verify(leaseStore).listOwnedWithLiveRun(OWNER, 7);
        assertThat(bounded.snapshot())
                .containsEntry("serviceLeaseTtlSeconds", 60L)
                .containsEntry("serviceLeaseRenewIntervalMs", 20_000L);
    }

    /** 读数里报的是归一化之后在用的三个数，不是属性请求值。 */
    @Test
    void theEffectiveLeaseValuesAreRegisteredForTheReading() {
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS))
                .containsEntry("RunServiceLeaseKeeper", 120L);
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_OWNED_LIMIT))
                .containsEntry("RunServiceLeaseKeeper", 512);
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_RENEW_INTERVAL_MS))
                .containsEntry("RunServiceLeaseKeeper", 40_000L);

        // 属性值不合法时按 1 处理：登记的也是归一化之后的 1，读数才不会报一个这里根本没采用的数。
        FrozenEffectiveSettings illegal = new FrozenEffectiveSettings();
        new RunServiceLeaseKeeper(leaseStore, admissionRegistry, identity, 0L, 0, 0L, illegal);
        assertThat(illegal.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS))
                .containsEntry("RunServiceLeaseKeeper", 1L);
        assertThat(illegal.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_OWNED_LIMIT))
                .containsEntry("RunServiceLeaseKeeper", 1);
        assertThat(illegal.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_RENEW_INTERVAL_MS))
                .containsEntry("RunServiceLeaseKeeper", 1L);
    }

    /**
     * 定时续期取的就是这里登记的周期：属性值不合法时按 1 毫秒排，而不是各自再读一遍属性。
     *
     * <p>排期发生在这个 bean 建完之后。若再在 {@code @Scheduled} 里用 SpEL 引用自己，
     * Spring Boot 3.2 会把创建过程判成自引用循环，容器起不来。</p>
     */
    @Test
    void theScheduledRenewalUsesTheRegisteredInterval() {
        FrozenEffectiveSettings illegal = new FrozenEffectiveSettings();
        RunServiceLeaseKeeper clamped = new RunServiceLeaseKeeper(
                leaseStore, admissionRegistry, identity, 0L, 0, 0L, illegal);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new RunServiceLeaseRenewSchedule(clamped).configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        assertThat(registrar.getFixedDelayTaskList().get(0).getIntervalDuration())
                .as("定时续期的周期与读数里登记的是同一个数")
                .isEqualTo(Duration.ofMillis(1));
    }

    /**
     * 容器按构造函数创建这个 bean，同时打开定时任务处理：不能再出现创建过程中引用自己的循环。
     */
    @Test
    void springCanCreateTheKeeperWithoutASelfReferenceCycle() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RunServiceLeaseStore.class, () -> leaseStore);
            context.registerBean(DualPoolRunAdmissionRegistry.class, () -> admissionRegistry);
            context.registerBean(ProcessInstanceIdentity.class, () -> identity);
            context.registerBean(FrozenEffectiveSettings.class, FrozenEffectiveSettings::new);
            context.register(RunServiceLeaseKeeper.class, RunServiceLeaseRenewSchedule.class, SchedulingEnabler.class);
            context.refresh();

            assertThat(context.getBean(RunServiceLeaseKeeper.class).renewIntervalMillis()).isEqualTo(40_000L);
        }
    }

    /** 只为了让上面那条用例的容器打开定时任务处理。 */
    @EnableScheduling
    static class SchedulingEnabler {
    }

    private static RunServiceLease lease(String runId, long fencingToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunServiceLease(runId, OWNER, fencingToken, now, now, now.plusMinutes(2));
    }
}
