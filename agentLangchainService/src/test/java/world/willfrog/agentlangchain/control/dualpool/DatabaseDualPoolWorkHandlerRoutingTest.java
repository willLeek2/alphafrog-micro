package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agentlangchain.control.LegacyRunHandoff;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;

/**
 * 共享候选按行路由：三个版本排在同一份候选里，选出来之后按每一行冻结的版本分别对待。
 *
 * <p>候选共享、能不能接手不共享。旧版本的 Run 只有拿到服务所有权之后才进旧入口；所有权在别的
 * 进程手上时这一轮不接手，并按所有权原因把它推后——不推后它会一直停在候选页首，页数一满，
 * 排在它后面的双池 Run 永远看不见。版本按 Run 主表上冻结的那一个走，资格行上的只是镜像。</p>
 */
class DatabaseDualPoolWorkHandlerRoutingTest {

    private RunCoordinationStore coordinationStore;
    private SchedulerStateStore stateStore;
    private AgentRunMapper runMapper;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private SchedulerVersionPolicy versionPolicy;
    private RunServiceLeaseStore leaseStore;
    private LegacyRunHandoff legacyHandoff;
    /** 读数用的登记表：这一份用例顺带量「领取租约、两次重试与服务租约时长」有没有登记在用的值。 */
    private final FrozenEffectiveSettings frozenEffectiveSettings = new FrozenEffectiveSettings();

    private DatabaseDualPoolWorkHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coordinationStore = Mockito.mock(RunCoordinationStore.class);
        stateStore = Mockito.mock(SchedulerStateStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        versionPolicy = Mockito.mock(SchedulerVersionPolicy.class);
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        legacyHandoff = Mockito.mock(LegacyRunHandoff.class);
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        ObjectProvider<LegacyRunHandoff> handoffProvider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(admissionRegistry.snapshotRunIds()).thenReturn(Set.of());
        Mockito.lenient().when(stateStore.currentRound(any())).thenReturn(1L);
        Mockito.lenient().when(identity.value()).thenReturn("test-instance");
        Mockito.lenient().when(handoffProvider.getIfAvailable()).thenReturn(legacyHandoff);
        Mockito.lenient().when(versionPolicy.isDualPoolFamily(any(world.willfrog.agent.platform.entity.AgentRun.class)))
                .thenReturn(true);
        handler = new DatabaseDualPoolWorkHandler(
                runMapper,
                Mockito.mock(FreshRunPipeline.class),
                Mockito.mock(NodeWorkItemStore.class),
                Mockito.mock(NodeWorkPlanAdapter.class),
                Mockito.mock(LangchainTodoNodeExecutor.class),
                Mockito.mock(AgentRunEventService.class),
                new ObjectMapper(),
                Mockito.mock(DualPoolDispatcher.class),
                admissionRegistry,
                versionPolicy,
                Mockito.mock(DualPoolToolJobCoordinator.class),
                Mockito.mock(DualPoolWaitGroupNodeExecutor.class),
                coordinationStore,
                stateStore,
                leaseStore,
                identity,
                handoffProvider,
                // 上限与水位按默认值；这一份用例量的是路由，不是参数解析。
                TestSchedulerSettings.propertyOnly(),
                300, 1000, 5000, 120, frozenEffectiveSettings);
    }

    /**
     * 四个启动冻结的值各自登记归一化之后真正在用的数。
     *
     * <p>读数里这几项报的是这里登记的数，不是属性请求值；服务租约时长这一项要求至少 5 秒，所以请求
     * 1 秒时登记的是 5 秒——报出 1 秒会让验收与排查以为真的只保 1 秒。</p>
     */
    @Test
    void theEffectiveLeaseAndRetryValuesAreRegisteredForTheReading() {
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_NODE_WORKER_CLAIM_LEASE_SECONDS))
                .containsEntry("DatabaseDualPoolWorkHandler", 300L);
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_COORDINATION_DEFER_RETRY_MS))
                .containsEntry("DatabaseDualPoolWorkHandler", 1000L);
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_HINT_QUEUE_FULL_RETRY_MS))
                .containsEntry("DatabaseDualPoolWorkHandler", 5000L);
        assertThat(frozenEffectiveSettings.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS))
                .containsEntry("DatabaseDualPoolWorkHandler", 120L);
    }

    /** 本进程已经握着这条 Run 的租约：读一次就够，不必再写一遍。 */
    private void ownedByThisProcess(String runId) {
        RunServiceLease lease = new RunServiceLease(runId, "test-instance", 1L,
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now(),
                OffsetDateTime.now().plusMinutes(2));
        Mockito.lenient().when(leaseStore.find(runId)).thenReturn(Optional.of(lease));
    }

    @Test
    void aLegacyRunIsHandedOffOnceOwnershipIsOurs() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-legacy", "LEGACY")));
        when(runMapper.findById("run-legacy")).thenReturn(run("run-legacy", "LEGACY"));
        ownedByThisProcess("run-legacy");
        when(legacyHandoff.handOff("run-legacy")).thenReturn(true);

        assertThat(handler.scanRunnableRuns(10))
                .as("旧版本不交给双池那两个池")
                .isEmpty();
        verify(legacyHandoff).handOff("run-legacy");
        verify(coordinationStore).markHandoffServed(eq("run-legacy"), anyLong(), eq(0));
        verify(coordinationStore, never()).deferHandoff(anyString(), any(), any(), anyInt(), anyLong());
        assertThat(handler.routingSnapshot())
                .containsEntry("legacyHandedOffTotal", 1L)
                .containsEntry("legacyDeferredTotal", 0L)
                .containsEntry("legacyCandidatesLastRound", 1L);
    }

    @Test
    void aLegacyRunWhoseOwnershipIsElsewhereIsDeferredNotTaken() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-legacy-busy", "LEGACY")));
        when(runMapper.findById("run-legacy-busy")).thenReturn(run("run-legacy-busy", "LEGACY"));
        RunServiceLease peerLease = new RunServiceLease("run-legacy-busy", "other-instance", 3L,
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now(),
                OffsetDateTime.now().plusMinutes(2));
        when(leaseStore.find("run-legacy-busy")).thenReturn(Optional.of(peerLease));
        when(leaseStore.acquire(eq("run-legacy-busy"), anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(legacyHandoff, never()).handOff(anyString());
        verify(coordinationStore).deferHandoff(eq("run-legacy-busy"),
                eq(RunCoordinationDeferReason.SERVICE_OWNERSHIP_ELSEWHERE), any(), eq(0), eq(0L));
        assertThat(handler.routingSnapshot()).containsEntry("legacyDeferredTotal", 1L);
    }

    @Test
    void aLegacyRunTheOldEntryRefusesIsDeferredToo() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-legacy-stuck", "LEGACY")));
        when(runMapper.findById("run-legacy-stuck")).thenReturn(run("run-legacy-stuck", "LEGACY"));
        ownedByThisProcess("run-legacy-stuck");
        when(legacyHandoff.handOff("run-legacy-stuck")).thenReturn(false);

        handler.scanRunnableRuns(10);
        verify(coordinationStore).deferHandoff(eq("run-legacy-stuck"),
                eq(RunCoordinationDeferReason.SERVICE_OWNERSHIP_ELSEWHERE), any(), eq(0), eq(0L));
        assertThat(handler.routingSnapshot())
                .containsEntry("legacyHandedOffTotal", 0L)
                .containsEntry("legacyDeferredTotal", 1L);
    }

    @Test
    void unknownVersionCandidatesAreIsolated() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-unknown", "DUAL_POOL_V9")));
        when(runMapper.findById("run-unknown")).thenReturn(run("run-unknown", "DUAL_POOL_V9"));

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
        assertThat(handler.routingSnapshot())
                .containsEntry("routingIsolatedTotal", 1L)
                .containsEntry("routingIsolatedLastRound", 1L);
    }

    @Test
    void aCandidateWhoseRowDisagreesWithTheRunMasterIsRoutedByTheMaster() {
        // 资格行上写的是旧版本，Run 主表上冻结的是双池：按主表走，不按镜像。
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-mirror", "LEGACY")));
        AgentRun master = run("run-mirror", "DUAL_POOL_V2");
        when(runMapper.findById("run-mirror")).thenReturn(master);
        ownedByThisProcess("run-mirror");
        when(admissionRegistry.isAdmitted("run-mirror")).thenReturn(true);

        assertThat(handler.scanRunnableRuns(10))
                .as("按主表路由：它走双池那一路")
                .extracting(RunCoordinationHint::runId)
                .containsExactly("run-mirror");
        verify(legacyHandoff, never()).handOff(anyString());
    }

    @Test
    void admittedDualPoolCandidatesBecomeHints() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-v2", "DUAL_POOL_V2")));
        AgentRun run = run("run-v2", "DUAL_POOL_V2");
        when(runMapper.findById("run-v2")).thenReturn(run);
        ownedByThisProcess("run-v2");
        when(admissionRegistry.isAdmitted("run-v2")).thenReturn(true);

        assertThat(handler.scanRunnableRuns(10))
                .extracting(RunCoordinationHint::runId)
                .containsExactly("run-v2");
        assertThat(handler.routingSnapshot()).containsEntry("leaseNotAcquiredLastRound", 0L);
    }

    /**
     * 双池的候选被别的进程正活着持有时，这一轮什么都不做：不动它的轮次，也不接手。
     *
     * <p>滚动重叠是真实现象，两个进程看的是同一份候选；所有权是唯一能分清「谁该动它」的事实。</p>
     */
    @Test
    void dualPoolCandidatesOwnedByAnotherProcessStayPut() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-peer", "DUAL_POOL_V2")));
        when(runMapper.findById("run-peer")).thenReturn(run("run-peer", "DUAL_POOL_V2"));
        when(leaseStore.find("run-peer")).thenReturn(Optional.of(new RunServiceLease("run-peer",
                "other-instance", 7L, OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now().plusMinutes(5))));
        when(leaseStore.acquire(eq("run-peer"), anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(admissionRegistry, never()).restorePersistedToolJob(anyString());
        assertThat(handler.routingSnapshot())
                .containsEntry("leaseNotAcquiredTotal", 1L)
                .containsEntry("leaseNotAcquiredLastRound", 1L);
    }

    @Test
    void dualPoolCandidatesThisProcessNeverAdmittedStayPut() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-v2", "DUAL_POOL_V2")));
        AgentRun run = run("run-v2", "DUAL_POOL_V2");
        when(runMapper.findById("run-v2")).thenReturn(run);
        ownedByThisProcess("run-v2");
        when(admissionRegistry.isAdmitted("run-v2")).thenReturn(false);
        when(admissionRegistry.takeoverLegacyRun("run-v2")).thenReturn(false);

        assertThat(handler.scanRunnableRuns(10))
                .as("库里有资格记录、持久事实也不足以恢复：不凭一次扫描就执行")
                .isEmpty();
    }

    /**
     * 接手别的进程留下的双池 Run：所有权在自己手上、本进程没受理过，靠持久事实重新取得许可。
     */
    @Test
    void aTakenOverDualPoolRunIsRestoredThroughItsPersistedFacts() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-dead-peer", "DUAL_POOL_V2")));
        when(runMapper.findById("run-dead-peer")).thenReturn(run("run-dead-peer", "DUAL_POOL_V2"));
        when(leaseStore.acquire(eq("run-dead-peer"), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(new RunServiceLease("run-dead-peer", "test-instance", 2L,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(2))));
        when(admissionRegistry.takeoverLegacyRun("run-dead-peer")).thenReturn(true);

        assertThat(handler.scanRunnableRuns(10))
                .extracting(RunCoordinationHint::runId)
                .containsExactly("run-dead-peer");
    }

    /**
     * 本进程受理过、但主表上已经不是双池家族的 Run：交还名额，不发提示。
     *
     * <p>候选行那一路按主表版本分流，旧版本走旧入口；这条走的是本进程受理集合那一路：
     * 受理在前、版本后来不是双池了，就不该继续当双池的活来推。</p>
     */
    @Test
    void aLocallyAdmittedRunWhoseFamilyChangedIsReleasedInsteadOfHinted() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of());
        when(admissionRegistry.snapshotRunIds()).thenReturn(Set.of("run-replaced"));
        AgentRun run = run("run-replaced", "LEGACY");
        when(runMapper.findById("run-replaced")).thenReturn(run);
        when(versionPolicy.isDualPoolFamily(run)).thenReturn(false);

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(admissionRegistry).releaseBusinessPermitIfCurrent(
                Mockito.eq("run-replaced"), Mockito.anyLong(), Mockito.any());
    }

    private static RunCoordination candidate(String runId, String version) {
        RunCoordination coordination = new RunCoordination();
        coordination.setRunId(runId);
        coordination.setSchedulerVersion(version);
        coordination.setPlanGeneration(0);
        coordination.setCoordinationServedRound(0L);
        coordination.setNextVisibleAt(OffsetDateTime.now());
        return coordination;
    }

    private static AgentRun run(String runId, String version) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-routing");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setSchedulerVersion(version);
        run.setPlanGeneration(0);
        run.setRunControlVersion(0L);
        return run;
    }
}
