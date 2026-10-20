package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
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
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;
import world.willfrog.agentlangchain.control.LegacyRunHandoff;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private static final String TEST_INSTANCE = "agent@localhost@"
            + ProcessHandle.current().pid() + "@test-instance";
    private static final String DEPLOYMENT_ID = "beta-test";
    private static final String GENERATION = "gen-" + "a".repeat(64);

    private RunCoordinationStore coordinationStore;
    private SchedulerStateStore stateStore;
    private AgentRunMapper runMapper;
    private NodeWorkItemStore workItemStore;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private SchedulerVersionPolicy versionPolicy;
    private RunServiceLeaseStore leaseStore;
    private LegacyRunHandoff legacyHandoff;
    /** 读数用的登记表：这一份用例顺带量「节点领取有效期、两次重试与服务所有权有效期」有没有登记在用的值。 */
    private final FrozenEffectiveSettings frozenEffectiveSettings = new FrozenEffectiveSettings();

    private DatabaseDualPoolWorkHandler handler;
    private FreshRunPipeline freshRunPipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coordinationStore = Mockito.mock(RunCoordinationStore.class);
        stateStore = Mockito.mock(SchedulerStateStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        workItemStore = Mockito.mock(NodeWorkItemStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        versionPolicy = Mockito.mock(SchedulerVersionPolicy.class);
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        legacyHandoff = Mockito.mock(LegacyRunHandoff.class);
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        ObjectProvider<LegacyRunHandoff> handoffProvider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(admissionRegistry.snapshotRunIds()).thenReturn(Set.of());
        Mockito.lenient().when(stateStore.currentRound(any())).thenReturn(1L);
        Mockito.lenient().when(identity.value()).thenReturn(TEST_INSTANCE);
        Mockito.lenient().when(handoffProvider.getIfAvailable()).thenReturn(legacyHandoff);
        Mockito.lenient().when(versionPolicy.isDualPoolFamily(any(world.willfrog.agent.platform.entity.AgentRun.class)))
                .thenReturn(true);
        freshRunPipeline = Mockito.mock(FreshRunPipeline.class);
        handler = new DatabaseDualPoolWorkHandler(
                runMapper,
                freshRunPipeline,
                workItemStore,
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
                null,
                300, 1000, 5000, 120, frozenEffectiveSettings);
    }

    /**
     * 四个启动冻结的值各自登记归一化之后真正在用的数。
     *
     * <p>读数里这几项报的是这里登记的数，不是属性请求值；服务所有权有效期至少 1 秒。</p>
     */
    @Test
    void preparingInterruptedExitRetriesOriginalClaimEpochAndRestoresInterrupt() {
        NodeWorkItemIdentity original = new NodeWorkItemIdentity("run", 2, "node", 1, 0);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("DB unavailable");
            return null;
        }).when(workItemStore).acknowledgeWorkerExit(original, 3);
        Thread.currentThread().interrupt();
        try {
            handler.acknowledgePreparingInterruptedWorkerExit(original, 3);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(workItemStore, org.mockito.Mockito.times(2)).acknowledgeWorkerExit(original, 3);
            verify(workItemStore, never()).acknowledgeWorkerExit(original, 4);
            verify(workItemStore, never()).reportExecutionFailure(any(), anyInt(), anyString(), anyString());
        } finally {
            Thread.interrupted();
            world.willfrog.agent.platform.dataanalysis.MemberPreparingInterruption.consume();
        }
    }

    @Test
    void normalWorkerFinallyConsumesPreparingMarkerAndRetriesItsOriginalExitReceipt() {
        String runId = "run-interrupted-receipt";
        NodeWorkItemIdentity identity = new NodeWorkItemIdentity(runId, 0, "__dual_pool_planning__", 0, 0);
        AgentRun run = run(runId, "DUAL_POOL_V2");
        run.setStatus(AgentRunStatus.RECEIVED);
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(runId);
        item.setPlanGeneration(0);
        item.setNodeId(identity.nodeId());
        item.setNodeAttempt(0);
        item.setSegmentSequence(0);
        item.setState(NodeWorkItemState.RUNNABLE.name());
        item.setSchedulerVersion("DUAL_POOL_V2");
        item.setContextVersion(0L);
        item.setRunControlVersion(0L);
        item.setClaimEpoch(0);
        item.setPayloadJson("{\"kind\":\"PLANNING\"}");
        ServiceOwnershipFence fence = new ServiceOwnershipFence(TEST_INSTANCE, 1L);
        when(admissionRegistry.isAdmitted(runId)).thenReturn(true);
        when(admissionRegistry.currentOwnershipFence(runId)).thenReturn(Optional.of(fence));
        when(runMapper.findById(runId)).thenReturn(run);
        when(workItemStore.findByIdentity(identity)).thenReturn(Optional.of(item));
        when(workItemStore.claim(eq(identity), any(), anyString(), any(), any(), eq(fence)))
                .thenAnswer(call -> Optional.of(new world.willfrog.agent.platform.workitem.NodeWorkItemClaim(
                        identity, call.getArgument(2), 3, OffsetDateTime.now().plusMinutes(1))));
        when(workItemStore.startExecution(eq(identity), eq(3), anyString()))
                .thenReturn(world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult.success());
        when(freshRunPipeline.prepareDualPoolRun(run)).thenAnswer(call -> {
            world.willfrog.agent.platform.dataanalysis.MemberPreparingInterruption.mark();
            Thread.currentThread().interrupt();
            return null;
        });
        when(workItemStore.commitSegmentResult(eq(identity), any(), anyString(), any()))
                .thenReturn(world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult.success());
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        Mockito.doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("DB temporarily unavailable");
            return null;
        }).when(workItemStore).acknowledgeWorkerExit(identity, 3);
        try {
            handler.executeNode(identity);
            verify(workItemStore, Mockito.times(2)).acknowledgeWorkerExit(identity, 3);
            verify(workItemStore, never()).acknowledgeWorkerExit(identity, 4);
            verify(workItemStore, never()).reportExecutionFailure(any(), anyInt(), anyString(), anyString());
            verify(freshRunPipeline).clearDualPoolNodeContext(runId);
            assertThat(world.willfrog.agent.platform.dataanalysis.MemberPreparingInterruption.consume()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            world.willfrog.agent.platform.dataanalysis.MemberPreparingInterruption.consume();
        }
    }

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

    @Test
    @SuppressWarnings("unchecked")
    void serviceOwnershipTtlFloorIsOneSecond() {
        FrozenEffectiveSettings inUse = new FrozenEffectiveSettings();
        new DatabaseDualPoolWorkHandler(
                runMapper,
                freshRunPipeline,
                workItemStore,
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
                Mockito.mock(ProcessInstanceIdentity.class),
                Mockito.mock(ObjectProvider.class),
                TestSchedulerSettings.propertyOnly(),
                null,
                300, 1000, 5000, 1, inUse);

        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS))
                .containsEntry("DatabaseDualPoolWorkHandler", 1L);
    }

    /** 本进程已经握着这条 Run 的服务所有权：读一次就够，不必再写一遍。 */
    private void ownedByThisProcess(String runId) {
        RunServiceLease lease = new RunServiceLease(runId, TEST_INSTANCE, 1L,
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

    /**
     * 读到或领到的这一代要写回准入组件：只更新数据库不更新那里，本进程会带着旧代际去写，
     * 条件语句全部落空，看起来像「什么都没发生」。
     */
    @Test
    void theOwnershipThisRoundActuallyHasIsWrittenBackToTheAdmissionRegistry() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-legacy", "LEGACY")));
        when(runMapper.findById("run-legacy")).thenReturn(run("run-legacy", "LEGACY"));
        ownedByThisProcess("run-legacy");
        when(legacyHandoff.handOff("run-legacy")).thenReturn(true);

        handler.scanRunnableRuns(10);
        verify(admissionRegistry).bindFence("run-legacy", new ServiceOwnershipFence(TEST_INSTANCE, 1L));

        // 库里没有、这次现领一条：领到的代际（这里是 5）要写回，不能还留着上一轮的旧号。
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-fresh", "LEGACY")));
        when(runMapper.findById("run-fresh")).thenReturn(run("run-fresh", "LEGACY"));
        when(leaseStore.find("run-fresh")).thenReturn(Optional.empty());
        when(leaseStore.acquire(eq("run-fresh"), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(new RunServiceLease("run-fresh", TEST_INSTANCE, 5L,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(2))));
        when(legacyHandoff.handOff("run-fresh")).thenReturn(true);

        handler.scanRunnableRuns(10);
        verify(admissionRegistry).bindFence("run-fresh", new ServiceOwnershipFence(TEST_INSTANCE, 5L));
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

    @Test
    void nodeClaimUsesPersistedRunLaneAndRestoresTheCallingThread() {
        String runId = "run-in-lane";
        AgentRun run = run(runId, "DUAL_POOL_V2");
        run.setLaneTag("candidate-lane");
        NodeWorkItemIdentity workIdentity = new NodeWorkItemIdentity(runId, 0, "todo-1", 0, 0);
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(runId);
        item.setPlanGeneration(0);
        item.setNodeId("todo-1");
        item.setNodeAttempt(0);
        item.setSegmentSequence(0);
        item.setState(NodeWorkItemState.RUNNABLE.name());
        item.setSchedulerVersion("DUAL_POOL_V2");
        item.setContextVersion(0L);
        item.setRunControlVersion(0L);
        item.setClaimEpoch(0);
        ServiceOwnershipFence fence = new ServiceOwnershipFence(TEST_INSTANCE, 1L);
        when(admissionRegistry.isAdmitted(runId)).thenReturn(true);
        when(admissionRegistry.currentOwnershipFence(runId)).thenReturn(Optional.of(fence));
        when(runMapper.findById(runId)).thenReturn(run);
        when(workItemStore.findByIdentity(workIdentity)).thenReturn(Optional.of(item));
        AtomicReference<String> laneAtClaim = new AtomicReference<>();
        when(workItemStore.claim(eq(workIdentity), any(), anyString(), any(), any(), eq(fence)))
                .thenAnswer(invocation -> {
                    laneAtClaim.set(LaneScopeGateway.currentLaneTag());
                    return Optional.empty();
                });
        AgentRun caller = run("caller", "DUAL_POOL_V2");
        caller.setLaneTag("caller-lane");

        LaneScopeGateway.wrap(caller, () -> {
            handler.executeNode(workIdentity);
            assertThat(LaneScopeGateway.currentLaneTag()).isEqualTo("caller-lane");
        }).run();

        assertThat(laneAtClaim).hasValue("candidate-lane");
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
                .thenReturn(Optional.of(new RunServiceLease("run-dead-peer", TEST_INSTANCE, 2L,
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
    void otherLaneReceivedRunDoesNotAcquireOwnership() {
        AgentRunEventService events = Mockito.mock(AgentRunEventService.class);
        RunOwnershipGateway ownership = Mockito.mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION));
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly(
                DualPoolSchedulerSettings.LANE_TRAFFIC_SCOPE_ID, "lane-a");
        handler = handlerWith(ownership, events, settings);
        AgentRun run = run("run-other-lane", "DUAL_POOL_V2");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setDeploymentId(DEPLOYMENT_ID);
        run.setDeploymentGenerationId(GENERATION);
        run.setLaneTag("lane-b");
        when(coordinationStore.scanDueForDeployment(DEPLOYMENT_ID, GENERATION, "lane-a", 10))
                .thenReturn(List.of(candidate("run-other-lane", "DUAL_POOL_V2")));
        when(runMapper.findById("run-other-lane")).thenReturn(run);
        when(leaseStore.find("run-other-lane")).thenReturn(Optional.empty());

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
        verify(events, never()).closeUnacceptedCreate(anyString(), anyString(), anyString());
    }

    @Test
    void expiredOwnershipOnReceivedRunWithZeroItemsClosesUnacceptedCreate() {
        AgentRunEventService events = Mockito.mock(AgentRunEventService.class);
        RunOwnershipGateway ownership = Mockito.mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION));
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly();
        handler = handlerWith(ownership, events, settings);
        AgentRun run = run("run-incomplete", "DUAL_POOL_V2");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setDeploymentId(DEPLOYMENT_ID);
        run.setDeploymentGenerationId(GENERATION);
        run.setLaneTag(null);
        when(coordinationStore.scanDueForDeployment(eq(DEPLOYMENT_ID), eq(GENERATION), isNull(), eq(10)))
                .thenReturn(List.of(candidate("run-incomplete", "DUAL_POOL_V2")));
        when(runMapper.findById("run-incomplete")).thenReturn(run);
        when(leaseStore.find("run-incomplete")).thenReturn(Optional.of(new RunServiceLease(
                "run-incomplete", TEST_INSTANCE, 1L,
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().minusMinutes(10),
                OffsetDateTime.now().minusMinutes(1))));
        when(workItemStore.countUnfinishedByRun("run-incomplete")).thenReturn(0);

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(events).closeUnacceptedCreate("run-incomplete", "user-routing", "create_incomplete");
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
        verify(admissionRegistry, never()).takeoverLegacyRun(anyString());
    }

    @Test
    void acceptedChildWithExpiredOwnershipIsNotClosedAsIncompleteCreate() {
        AgentRunEventService events = Mockito.mock(AgentRunEventService.class);
        RunOwnershipGateway ownership = Mockito.mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION));
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly();
        handler = handlerWith(ownership, events, settings);
        AgentRun run = run("run-accepted-child", "DUAL_POOL_V2");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setDeploymentId(DEPLOYMENT_ID);
        run.setDeploymentGenerationId(GENERATION);
        when(coordinationStore.scanDueForDeployment(eq(DEPLOYMENT_ID), eq(GENERATION), isNull(), eq(10)))
                .thenReturn(List.of(candidate("run-accepted-child", "DUAL_POOL_V2")));
        when(runMapper.findById("run-accepted-child")).thenReturn(run);
        when(leaseStore.find("run-accepted-child")).thenReturn(Optional.empty());
        when(workItemStore.countUnfinishedByRun("run-accepted-child")).thenReturn(0);
        when(childRunIntents.findByChildRunId("run-accepted-child")).thenReturn(Optional.of(
                new ChildRunIntentView(9L, "root", "parent", "run-accepted-child",
                        null, 0L, null, null, 0, 0, 0L,
                        "ACCEPTED", "RECEIVED", null, OffsetDateTime.now(), null, null, null)));

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(events, never()).closeUnacceptedCreate(anyString(), anyString(), anyString());
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void closeFailureDoesNotStopTheScanRound() {
        AgentRunEventService events = Mockito.mock(AgentRunEventService.class);
        RunOwnershipGateway ownership = Mockito.mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION));
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly();
        handler = handlerWith(ownership, events, settings);
        AgentRun run = run("run-close-fails", "DUAL_POOL_V2");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setDeploymentId(DEPLOYMENT_ID);
        run.setDeploymentGenerationId(GENERATION);
        when(coordinationStore.scanDueForDeployment(eq(DEPLOYMENT_ID), eq(GENERATION), isNull(), eq(10)))
                .thenReturn(List.of(candidate("run-close-fails", "DUAL_POOL_V2")));
        when(runMapper.findById("run-close-fails")).thenReturn(run);
        when(leaseStore.find("run-close-fails")).thenReturn(Optional.empty());
        when(workItemStore.countUnfinishedByRun("run-close-fails")).thenReturn(0);
        when(events.closeUnacceptedCreate("run-close-fails", "user-routing", "create_incomplete"))
                .thenThrow(new IllegalStateException("event write failed"));

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
    }

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

    private ChildRunIntentStore childRunIntents;

    @SuppressWarnings("unchecked")
    private DatabaseDualPoolWorkHandler handlerWith(RunOwnershipGateway ownership,
                                                    AgentRunEventService events,
                                                    DualPoolSchedulerSettings settings) {
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        ObjectProvider<LegacyRunHandoff> handoffProvider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(identity.value()).thenReturn(TEST_INSTANCE);
        Mockito.lenient().when(handoffProvider.getIfAvailable()).thenReturn(legacyHandoff);
        return new DatabaseDualPoolWorkHandler(
                runMapper,
                freshRunPipeline,
                workItemStore,
                Mockito.mock(NodeWorkPlanAdapter.class),
                Mockito.mock(LangchainTodoNodeExecutor.class),
                events,
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
                settings,
                ownership,
                childRunIntents = Mockito.mock(ChildRunIntentStore.class),
                300, 1000, 5000, 120, new FrozenEffectiveSettings());
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
