package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemRejection;
import world.willfrog.agent.platform.workitem.NodeWorkItemRejectionReason;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualPoolRunAdmissionRegistryTest {

    /** 替身里那条活着的服务所有权：持有人 test-instance、代际 1，与 {@code leaseAcquired()} 对应。 */
    private static final ServiceOwnershipFence FENCE = new ServiceOwnershipFence("test-instance", 1L);

    /** 这条路在读服务租约时长：登记之后读数里报的是它归一化之后在用的数。 */
    @Test
    void theEffectiveLeaseTtlIsRegisteredForTheReading() {
        FrozenEffectiveSettings inUse = new FrozenEffectiveSettings();
        new DualPoolRunAdmissionRegistry(mock(SchedulerPermitLedger.class),
                mock(NodeWorkItemStore.class), mock(AgentRunMapper.class), mock(RunServiceLeaseStore.class),
                mock(ProcessInstanceIdentity.class), 120L, inUse);

        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS))
                .containsEntry("DualPoolRunAdmissionRegistry", 120L);
    }

    /**
     * 只量名额语义的替身：服务所有权交给一条「取一次就成功」的租约。受理这条路本来就要先拿到
     * 这条 Run 的服务所有权，拿不到的进程不该受理它，所以替身也必须把这一关摆出来。
     */
    private static DualPoolRunAdmissionRegistry registryThatCanOwnRuns(
            SchedulerPermitLedger permitLedger, NodeWorkItemStore workItems, AgentRunMapper runs) {
        RunServiceLeaseStore leaseStore = mock(RunServiceLeaseStore.class);
        ProcessInstanceIdentity identity = mock(ProcessInstanceIdentity.class);
        when(identity.value()).thenReturn("test-instance");
        when(leaseStore.acquire(anyString(), anyString(), any())).thenAnswer(invocation ->
                Optional.of(new RunServiceLease(invocation.getArgument(0, String.class), "test-instance", 1L,
                        OffsetDateTime.now(), OffsetDateTime.now(),
                        OffsetDateTime.now().plusMinutes(2))));
        when(leaseStore.find(anyString())).thenReturn(Optional.empty());
        return new DualPoolRunAdmissionRegistry(permitLedger, workItems, runs, leaseStore, identity, 120L,
                new FrozenEffectiveSettings());
    }

    @Test
    void oldLifecycleCannotReleaseNewFollowUpAdmission() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = registryThatCanOwnRuns(
                permitLedger, mock(NodeWorkItemStore.class), mock(AgentRunMapper.class));

        assertThat(registry.admitNewRun("run-1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();
        long oldEpoch = registry.currentAdmissionEpoch("run-1");
        assertThat(registry.admitExistingRun("run-1")).isTrue();
        long followUpEpoch = registry.currentAdmissionEpoch("run-1");
        assertThat(followUpEpoch).isGreaterThan(oldEpoch);

        AtomicBoolean oldCleanupRan = new AtomicBoolean();
        assertThat(registry.releaseBusinessPermitIfCurrent(
                "run-1", oldEpoch, () -> oldCleanupRan.set(true))).isFalse();
        assertThat(oldCleanupRan).isFalse();
        assertThat(registry.isAdmitted("run-1")).isTrue();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(1);

        AtomicBoolean currentCleanupRan = new AtomicBoolean();
        assertThat(registry.releaseBusinessPermitIfCurrent(
                "run-1", followUpEpoch, () -> currentCleanupRan.set(true))).isTrue();
        assertThat(currentCleanupRan).isTrue();
        assertThat(registry.isAdmitted("run-1")).isFalse();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
    }

    @Test
    void losingLaterReservationCannotRemoveEarlierDurableWinner() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = registryThatCanOwnRuns(
                permitLedger, mock(NodeWorkItemStore.class), mock(AgentRunMapper.class));

        assertThat(registry.admitNewRun("run-1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();
        DualPoolRunAdmissionRegistry.Admission earlier =
                registry.admitExistingRunWithLease("run-1");
        DualPoolRunAdmissionRegistry.Admission later =
                registry.admitExistingRunWithLease("run-1");

        assertThat(registry.activateReservedAdmission("run-1", earlier)).isTrue();
        assertThat(registry.rollbackReservedAdmission("run-1", later)).isTrue();
        assertThat(registry.currentAdmissionEpoch("run-1")).isEqualTo(earlier.epoch());
        assertThat(registry.isAdmitted("run-1")).isTrue();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse())
                .isEqualTo(1);
    }

    /**
     * 恢复预留没占上名额时，这一次刚取得的租约要让出去，本进程也不再以为这条 Run 归自己。
     *
     * <p>以前这里只把「本进程不认识这条 Run」记下来，凭据与库里的租约都留在手里：这条 Run 被本进程
     * 锁着（续期循环还会一直替它续），却没有一个入口能推进它，直到租约过期。</p>
     */
    @Test
    void aRejectedRecoveryReservationGivesTheFreshlyTakenLeaseBack() {
        Fixture fixture = new Fixture();
        fixture.permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        assertThat(fixture.registry.admitNewRun("run-1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();

        assertThat(fixture.registry.reserveForRecovery("run-2").admitted()).isFalse();

        assertThat(fixture.registry.holdsOwnership("run-2")).as("没受理就不该留下凭据").isFalse();
        assertThat(fixture.registry.snapshotRunIds()).containsExactly("run-1");
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse())
                .as("被拒的那一次不占名额").isEqualTo(1);
        verify(fixture.leaseStore).release("run-2", "test-instance", 1L);
    }

    /**
     * 这条 Run 的租约本来就是我们的：这一轮接手失败（分段放不回去）不能把凭据清掉。
     *
     * <p>数据库里那条租约还是我们的，凭据清掉之后本进程会一直带着空凭据去写——每一次写入都被条件
     * 语句挡回来，看起来像「什么都没发生」。</p>
     */
    @Test
    void aFailedTakeoverLeavesTheOwnershipWeAlreadyHaveInPlace() {
        Fixture fixture = new Fixture();
        NodeWorkItem claimed = item("run-2", 3, "todo-1", 0, 0);
        claimed.setState(NodeWorkItemState.CLAIMED.name());
        claimed.setClaimEpoch(2);
        claimed.setContextVersion(7L);
        claimed.setRunControlVersion(2L);
        claimed.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(claimed));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));
        when(fixture.leaseStore.find("run-2")).thenReturn(Optional.of(new RunServiceLease("run-2",
                "test-instance", 1L, OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now(),
                OffsetDateTime.now().plusMinutes(2))));
        when(fixture.workItems.requeueAbandonedClaim(any(), any(), any(), any()))
                .thenReturn(NodeWorkItemMutationResult.rejected(NodeWorkItemRejection.of(
                        NodeWorkItemRejectionReason.CONDITION_MISMATCH,
                        claimed.identity(), new NodeWorkItemVersions(7L, 2L, 2), null)));
        when(fixture.workItems.findByIdentity(claimed.identity())).thenReturn(Optional.of(claimed));

        fixture.registry.detectStartupResidue();

        assertThat(fixture.isolationReasonOf("run-2")).isEqualTo("claim_requeue_left_claimed");
        assertThat(fixture.registry.holdsOwnership("run-2"))
                .as("租约本来就在我们手上：这一轮不动它，凭据也留着").isTrue();
        verify(fixture.leaseStore, never()).release(eq("run-2"), anyString(), anyLong());
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
    }

    /**
     * 带着旧代际号回来的撤销不该动现在这一代：本进程重新取得这条 Run 之后，旧回调说的那一代已经作废。
     *
     * <p>读、比、删要是分成三步做，中间被并发插进来就会把新的生命周期删掉——那之后本进程占着名额，
     * 却因为凭据被删而写不动这条 Run。</p>
     */
    @Test
    void aRevocationCarryingAStaleTokenLeavesTheStateWeTookBackAlone() {
        Fixture fixture = new Fixture();
        assertThat(fixture.registry.admitNewRun("run-1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();
        assertThat(fixture.registry.bindFence("run-1", new ServiceOwnershipFence("test-instance", 9L))).isTrue();

        assertThat(fixture.registry.revokeOwnership("run-1", 1L)).isFalse();

        assertThat(fixture.registry.currentOwnershipFence("run-1"))
                .contains(new ServiceOwnershipFence("test-instance", 9L));
        assertThat(fixture.registry.isAdmitted("run-1")).isTrue();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(1);
    }

    /** 撤销说的就是现在这一代：凭据、准入与名额一起交还，本进程也不再认识这条 Run。 */
    @Test
    void aRevocationCarryingTheCurrentTokenDropsTheOwnershipAndThePermit() {
        Fixture fixture = new Fixture();
        assertThat(fixture.registry.admitNewRun("run-1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();
        assertThat(fixture.registry.bindFence("run-1", new ServiceOwnershipFence("test-instance", 9L))).isTrue();

        assertThat(fixture.registry.revokeOwnership("run-1", 9L)).isTrue();

        assertThat(fixture.registry.holdsOwnership("run-1")).isFalse();
        assertThat(fixture.registry.isAdmitted("run-1")).isFalse();
        assertThat(fixture.registry.isKnownInCurrentProcess("run-1")).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
    }

    @Test
    void startupRecoveryRejectsRunWithUnrelatedUnfinishedWorkItem() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 2);
        NodeWorkItemStore workItems = mock(NodeWorkItemStore.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setWorkItemPlanGeneration(3);
        anchor.setWorkItemNodeId("todo-1");
        anchor.setWorkItemNodeAttempt(0);
        anchor.setWorkItemSegmentSequence(1);
        anchor.setWorkItemContextVersion(7L);
        anchor.setWorkItemRunControlVersion(2L);
        anchor.setWorkItemClaimEpoch(4);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        run.setStatus(AgentRunStatus.WAITING_TOOL_JOB);
        run.setToolJobAnchorJson(anchor.toJson());

        NodeWorkItem owner = item("run-1", 3, "todo-1", 0, 1);
        NodeWorkItem unrelated = item("run-1", 3, "todo-2", 0, 2);
        when(workItems.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V1)).thenReturn(2);
        when(workItems.listUnfinishedByRunSchedulerVersion(eq(SchedulerVersion.DUAL_POOL_V1), anyInt()))
                .thenReturn(List.of(owner, unrelated));
        when(runs.findById("run-1")).thenReturn(run);

        DualPoolRunAdmissionRegistry registry = registryThatCanOwnRuns(permitLedger, workItems, runs);
        registry.detectStartupResidue();

        assertThat(registry.startupResidueBlocked()).isTrue();
        assertThat(registry.isKnownInCurrentProcess("run-1")).isFalse();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
    }

    // ==================== 完整 DAG（DUAL_POOL_V2）的启动恢复 ====================

    @Test
    void waitGroupResidueIsTakenOverWithLeaseThenRequeueThenAdmission() {
        Fixture fixture = new Fixture();
        NodeWorkItem executing = item("run-2", 3, "todo-1", 0, 1);
        executing.setState(NodeWorkItemState.EXECUTING.name());
        executing.setClaimEpoch(4);
        executing.setContextVersion(7L);
        executing.setRunControlVersion(2L);
        executing.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        NodeWorkItem waiting = item("run-2", 3, "todo-2", 0, 0);
        waiting.setState(NodeWorkItemState.WAITING.name());
        waiting.setClaimEpoch(0);
        waiting.setContextVersion(7L);
        waiting.setRunControlVersion(2L);
        waiting.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(executing, waiting));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));
        fixture.leaseAcquired();

        fixture.registry.detectStartupResidue();

        // 只有死在领取态的那一段被放回可领取；等待中的那一段由恢复通知那一套驱动，不动它。
        verify(fixture.workItems).requeueAbandonedClaim(
                new NodeWorkItemIdentity("run-2", 3, "todo-1", 0, 1),
                new NodeWorkItemVersions(7L, 2L, 4), FENCE, SchedulerVersion.DUAL_POOL_V2);
        verify(fixture.workItems, never()).requeueAbandonedClaim(
                eq(new NodeWorkItemIdentity("run-2", 3, "todo-2", 0, 0)), any(), any(), any());
        assertThat(fixture.registry.isAdmitted("run-2")).isTrue();
        assertThat(fixture.registry.startupRecoveredWaitGroupRunIds()).containsExactly("run-2");
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(1);
        assertThat(fixture.registry.startupSnapshot())
                .containsEntry("recoveredWaitGroupRunCount", 1)
                .containsEntry("requeuedAbandonedClaimTotal", 1L)
                .containsEntry("isolatedRunCount", 0);
    }

    /**
     * 一条 V2 的 Run 下面混着一行 V1 的未完成分段：整条 Run 隔离，那一行也不会被别的版本领走。
     *
     * <p>扫描按 Run 的版本取行（行自己的版本不参与筛选），所以这行读得出来；逐行比对时发现
     * 版本不一致，就没法证明「这条 Run 的未完成分段都属于这一版」，只能隔离这一条 Run。</p>
     */
    @Test
    void aRunWhoseResidueCarriesAnotherVersionsRowIsIsolatedAsAWhole() {
        Fixture fixture = new Fixture();
        NodeWorkItem v2Row = item("run-2", 3, "todo-1", 0, 0);
        v2Row.setState(NodeWorkItemState.RUNNABLE.name());
        v2Row.setClaimEpoch(0);
        v2Row.setContextVersion(7L);
        v2Row.setRunControlVersion(2L);
        v2Row.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        NodeWorkItem v1Row = item("run-2", 3, "todo-2", 0, 0);
        v1Row.setState(NodeWorkItemState.CLAIMED.name());
        v1Row.setClaimEpoch(1);
        v1Row.setContextVersion(7L);
        v1Row.setRunControlVersion(2L);
        v1Row.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(v2Row, v1Row));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));

        fixture.registry.detectStartupResidue();

        assertThat(fixture.isolationReasonOf("run-2")).isEqualTo("item_scheduler_version_mismatch");
        assertThat(fixture.registry.isAdmitted("run-2")).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
        assertThat(fixture.registry.reserveForRecovery("run-2").admitted()).isFalse();
        verify(fixture.workItems, never()).requeueAbandonedClaim(any(), any(), any(), any());
        verify(fixture.leaseStore, never()).acquire(anyString(), anyString(), any());
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name()))
                .as("说清不的是这一条 Run，不是整个版本：别的 Run 照常").isFalse();
    }

    /**
     * 隔离结论不落表、也不靠内存传递：同一份库事实换一个进程实例再算一遍，答案必须一样。
     *
     * <p>第一个实例把「行自己的版本与 Run 版本不一致」这条 Run 判成整条隔离之后，第二个实例
     * （新的进程身份、新的内存状态，与第一个共用同一份库事实）独立启动时要从同样的事实得出同样的
     * 结论：隔离原因、版本阻断、以及它能不能被受理，三项都要一致。这一条量的是「重启之后结论重算」，
     * 上一次进程记住了什么不参与判断。</p>
     */
    @Test
    void aSecondInstanceReachesTheSameIsolationConclusionFromTheSameFacts() {
        Fixture fixture = new Fixture();
        NodeWorkItem v2Row = item("run-2", 3, "todo-1", 0, 0);
        v2Row.setState(NodeWorkItemState.RUNNABLE.name());
        v2Row.setClaimEpoch(0);
        v2Row.setContextVersion(7L);
        v2Row.setRunControlVersion(2L);
        v2Row.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        NodeWorkItem v1Row = item("run-2", 3, "todo-2", 0, 0);
        v1Row.setState(NodeWorkItemState.CLAIMED.name());
        v1Row.setClaimEpoch(1);
        v1Row.setContextVersion(7L);
        v1Row.setRunControlVersion(2L);
        v1Row.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(v2Row, v1Row));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));
        // 第二个实例：新的进程身份与内存，共用同一份库事实（同一个 workItems / runs 替身）。
        DualPoolRunAdmissionRegistry secondInstance =
                registryThatCanOwnRuns(fixture.permitLedger, fixture.workItems, fixture.runs);

        fixture.registry.detectStartupResidue();
        secondInstance.detectStartupResidue();

        assertThat(isolationReasonOf(secondInstance, "run-2"))
                .as("第二个实例从同一份库事实算出同一个隔离原因")
                .isEqualTo(fixture.isolationReasonOf("run-2"))
                .isEqualTo("item_scheduler_version_mismatch");
        assertThat(secondInstance.isAdmitted("run-2"))
                .as("隔离的 Run 在第二个实例上同样不被受理").isFalse();
        assertThat(secondInstance.reserveForRecovery("run-2").admitted()).isFalse();
        assertThat(secondInstance.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name()))
                .as("阻断的是这一条 Run，不是整个版本：第二个实例的结论也一样").isFalse();
        verify(fixture.workItems, never()).requeueAbandonedClaim(any(), any(), any(), any());
        verify(fixture.leaseStore, never()).acquire(anyString(), anyString(), any());
    }

    /**
     * 父 Run 已经进终态、收口又没能证明那些分段也已终结：隔离这一条，不许借恢复接回执行链。
     *
     * <p>终态 Run 没有执行权。收口失败时如果落进普通恢复流程，它会被当成「说得清的遗留」重新取得
     * 租约与业务名额，接回来继续跑一张已经结束的图。</p>
     */
    @Test
    void terminalResidueThatCannotBeClosedIsIsolatedInsteadOfRecovered() {
        Fixture fixture = new Fixture();
        NodeWorkItem left = item("run-2", 3, "todo-1", 0, 0);
        left.setState(NodeWorkItemState.CLAIMED.name());
        left.setClaimEpoch(2);
        left.setContextVersion(7L);
        left.setRunControlVersion(2L);
        left.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(left));
        fixture.run(waitGroupRun(AgentRunStatus.FAILED));
        when(fixture.workItems.listUnfinishedByRun("run-2")).thenReturn(List.of(left));
        when(fixture.workItems.markStale(any(), anyLong(), anyLong(), anyString()))
                .thenReturn(NodeWorkItemMutationResult.rejected(NodeWorkItemRejection.of(
                        NodeWorkItemRejectionReason.CONDITION_MISMATCH, left.identity(),
                        new NodeWorkItemVersions(7L, 2L, 2), null)));
        when(fixture.workItems.findByIdentity(left.identity())).thenReturn(Optional.of(left));

        fixture.registry.detectStartupResidue();

        assertThat(fixture.isolationReasonOf("run-2")).isEqualTo("run_status_not_recoverable");
        assertThat(fixture.registry.isAdmitted("run-2")).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
        verify(fixture.workItems, never()).requeueAbandonedClaim(any(), any(), any(), any());
        verify(fixture.leaseStore, never()).acquire(anyString(), anyString(), any());
    }

    @Test
    void waitGroupRunWithStaleGenerationIsIsolatedWithoutTakingAPermit() {
        Fixture fixture = new Fixture();
        NodeWorkItem stale = item("run-2", 2, "todo-1", 0, 0);
        stale.setState(NodeWorkItemState.CLAIMED.name());
        stale.setClaimEpoch(1);
        stale.setContextVersion(7L);
        stale.setRunControlVersion(2L);
        stale.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(stale));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));

        fixture.registry.detectStartupResidue();

        // 证明不了就只隔离这一条：不占业务名额、不碰服务所有权、恢复受理一律拒绝，另一版本照常。
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
        assertThat(fixture.registry.isAdmitted("run-2")).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
        assertThat(fixture.registry.reserveForRecovery("run-2").admitted()).isFalse();
        verify(fixture.workItems, never()).requeueAbandonedClaim(
                any(), any(), any(), any());
        verify(fixture.leaseStore, never()).acquire(anyString(), anyString(), any());
        assertThat(fixture.isolationReasonOf("run-2")).isEqualTo("item_plan_generation_stale");
    }

    @Test
    void unattributableResidueBlocksOnlyItsOwnVersion() {
        Fixture fixture = new Fixture();
        NodeWorkItem orphan = item("run-2", 3, "todo-1", 0, 0);
        orphan.setState(NodeWorkItemState.RUNNABLE.name());
        orphan.setClaimEpoch(0);
        orphan.setContextVersion(7L);
        orphan.setRunControlVersion(2L);
        orphan.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(orphan));
        // Run 读不回来：这一行归谁都说不清。
        when(fixture.runs.findById("run-2")).thenReturn(null);

        fixture.registry.detectStartupResidue();

        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isTrue();
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V1.name())).isFalse();
        assertThat(fixture.registry.startupResidueBlocked()).isTrue();
        assertThat(fixture.registry.admitNewRun("run-new-v1", SchedulerVersion.DUAL_POOL_V1.name())).isTrue();
        assertThat(fixture.registry.admitNewRun("run-new-v2", SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
    }

    @Test
    void waitGroupRunServedByAnotherProcessIsLeftAlone() {
        Fixture fixture = new Fixture();
        NodeWorkItem claimed = item("run-2", 3, "todo-1", 0, 0);
        claimed.setState(NodeWorkItemState.CLAIMED.name());
        claimed.setClaimEpoch(2);
        claimed.setContextVersion(7L);
        claimed.setRunControlVersion(2L);
        claimed.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(claimed));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));
        when(fixture.leaseStore.acquire(eq("run-2"), anyString(), any())).thenReturn(Optional.empty());

        fixture.registry.detectStartupResidue();

        // 别的进程正活着服务它：不是失败，也不是证明不了，只是不归我们动。
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
        assertThat(fixture.registry.isAdmitted("run-2")).isFalse();
        assertThat(fixture.registry.startupSnapshot())
                .containsEntry("leaseHeldElsewhereTotal", 1L)
                .containsEntry("isolatedRunCount", 0);
        verify(fixture.workItems, never()).requeueAbandonedClaim(
                any(), any(), any(), any());
    }

    @Test
    void aSegmentThatStaysClaimedKeepsTheRunIsolatedAndGivesTheLeaseBack() {
        Fixture fixture = new Fixture();
        NodeWorkItem claimed = item("run-2", 3, "todo-1", 0, 0);
        claimed.setState(NodeWorkItemState.CLAIMED.name());
        claimed.setClaimEpoch(2);
        claimed.setContextVersion(7L);
        claimed.setRunControlVersion(2L);
        claimed.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(claimed));
        fixture.run(waitGroupRun(AgentRunStatus.EXECUTING));
        fixture.leaseAcquired();
        when(fixture.workItems.requeueAbandonedClaim(any(), any(), any(), any()))
                .thenReturn(NodeWorkItemMutationResult.rejected(NodeWorkItemRejection.of(
                        NodeWorkItemRejectionReason.CONDITION_MISMATCH,
                        claimed.identity(), new NodeWorkItemVersions(7L, 2L, 2), null)));
        when(fixture.workItems.findByIdentity(claimed.identity())).thenReturn(Optional.of(claimed));

        fixture.registry.detectStartupResidue();

        assertThat(fixture.isolationReasonOf("run-2")).isEqualTo("claim_requeue_left_claimed");
        assertThat(fixture.registry.isAdmitted("run-2")).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
        verify(fixture.leaseStore).release("run-2", "test-instance", 1L);
    }

    @Test
    void terminalRunResidueIsClosedWithoutAdmission() {
        Fixture fixture = new Fixture();
        NodeWorkItem left = item("run-2", 3, "todo-1", 0, 0);
        left.setState(NodeWorkItemState.WAITING.name());
        left.setClaimEpoch(0);
        left.setContextVersion(7L);
        left.setRunControlVersion(2L);
        left.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        fixture.residue(SchedulerVersion.DUAL_POOL_V2, List.of(left));
        fixture.run(waitGroupRun(AgentRunStatus.COMPLETED));
        when(fixture.workItems.listUnfinishedByRun("run-2")).thenReturn(List.of(left));
        when(fixture.workItems.markStale(any(), anyLong(), anyLong(), anyString()))
                .thenReturn(NodeWorkItemMutationResult.success());

        fixture.registry.detectStartupResidue();

        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
        assertThat(fixture.registry.startupSnapshot()).containsEntry("isolatedRunCount", 0);
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
        verify(fixture.workItems).markStale(eq(left.identity()), anyLong(), anyLong(),
                eq("parent_run_terminal_at_startup:COMPLETED"));
    }

    /** 一次启动扫描的替身：两版残留都按最省事的方式答「没有」，用到哪条再单独放宽。 */
    private static final class Fixture {
        private final SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        private final NodeWorkItemStore workItems = mock(NodeWorkItemStore.class);
        private final AgentRunMapper runs = mock(AgentRunMapper.class);
        private final RunServiceLeaseStore leaseStore = mock(RunServiceLeaseStore.class);
        private final ProcessInstanceIdentity identity = mock(ProcessInstanceIdentity.class);
        private final DualPoolRunAdmissionRegistry registry;

        private Fixture() {
            permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 4);
            when(identity.value()).thenReturn("test-instance");
            when(workItems.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V1)).thenReturn(0);
            when(workItems.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V2)).thenReturn(0);
            when(leaseStore.find(anyString())).thenReturn(Optional.empty());
            // 默认这条 Run 的服务所有权取得得到；「在别人手上」的用例再单独把这一条改掉。
            when(leaseStore.acquire(anyString(), anyString(), any())).thenAnswer(invocation ->
                    Optional.of(new RunServiceLease(invocation.getArgument(0, String.class), "test-instance",
                            1L, OffsetDateTime.now(), OffsetDateTime.now(),
                            OffsetDateTime.now().plusMinutes(2))));
            registry = new DualPoolRunAdmissionRegistry(permitLedger, workItems, runs, leaseStore, identity,
                    120L, new FrozenEffectiveSettings());
        }

        private void residue(SchedulerVersion version, List<NodeWorkItem> items) {
            when(workItems.countUnfinishedByRunSchedulerVersion(version)).thenReturn(items.size());
            when(workItems.listUnfinishedByRunSchedulerVersion(eq(version), anyInt())).thenReturn(items);
        }

        private void run(AgentRun run) {
            when(runs.findById(run.getId())).thenReturn(run);
        }

        /** 放回领取态这一段答「放回去了」；服务所有权取得得到已经是这个替身的默认。 */
        private void leaseAcquired() {
            when(workItems.requeueAbandonedClaim(any(), any(), any(), any()))
                    .thenReturn(NodeWorkItemMutationResult.success());
        }

        @SuppressWarnings("unchecked")
        private String isolationReasonOf(String runId) {
            return DualPoolRunAdmissionRegistryTest.isolationReasonOf(registry, runId);
        }
    }

    /** 某个实例在这条 Run 上给出的隔离原因；没有隔离就是 null。 */
    @SuppressWarnings("unchecked")
    private static String isolationReasonOf(DualPoolRunAdmissionRegistry registry, String runId) {
        Map<String, Object> versions = (Map<String, Object>) registry.startupSnapshot().get("versions");
        Map<String, Object> one = (Map<String, Object>) versions.get(SchedulerVersion.DUAL_POOL_V2.name());
        return ((Map<String, String>) one.get("isolatedReasons")).get(runId);
    }

    private static AgentRun waitGroupRun(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("run-2");
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        run.setStatus(status);
        run.setPlanGeneration(3);
        run.setRunControlVersion(2L);
        return run;
    }

    private static NodeWorkItem item(String runId, int generation, String nodeId,
                                     int attempt, int sequence) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(runId);
        item.setPlanGeneration(generation);
        item.setNodeId(nodeId);
        item.setNodeAttempt(attempt);
        item.setSegmentSequence(sequence);
        return item;
    }
}
