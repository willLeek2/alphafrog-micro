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

    @Test
    void oldLifecycleCannotReleaseNewFollowUpAdmission() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, mock(NodeWorkItemStore.class));

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
        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, mock(NodeWorkItemStore.class));

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
        when(workItems.countUnfinishedBySchedulerVersion(SchedulerVersion.DUAL_POOL_V1)).thenReturn(2);
        when(workItems.listUnfinishedBySchedulerVersion(SchedulerVersion.DUAL_POOL_V1, 2))
                .thenReturn(List.of(owner, unrelated));
        when(runs.findById("run-1")).thenReturn(run);

        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, workItems, runs);
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
                new NodeWorkItemIdentity("run-2", 3, "todo-1", 0, 1), 4, 7L, 2L);
        verify(fixture.workItems, never()).requeueAbandonedClaim(
                eq(new NodeWorkItemIdentity("run-2", 3, "todo-2", 0, 0)), anyInt(), anyLong(), anyLong());
        assertThat(fixture.registry.isAdmitted("run-2")).isTrue();
        assertThat(fixture.registry.startupRecoveredWaitGroupRunIds()).containsExactly("run-2");
        assertThat(fixture.registry.startupResidueBlockedFor(SchedulerVersion.DUAL_POOL_V2.name())).isFalse();
        assertThat(fixture.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(1);
        assertThat(fixture.registry.startupSnapshot())
                .containsEntry("recoveredWaitGroupRunCount", 1)
                .containsEntry("requeuedAbandonedClaimTotal", 1L)
                .containsEntry("isolatedRunCount", 0);
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
                any(), anyInt(), anyLong(), anyLong());
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
                any(), anyInt(), anyLong(), anyLong());
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
        when(fixture.workItems.requeueAbandonedClaim(any(), anyInt(), anyLong(), anyLong()))
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
            when(workItems.countUnfinishedBySchedulerVersion(SchedulerVersion.DUAL_POOL_V1)).thenReturn(0);
            when(workItems.countUnfinishedBySchedulerVersion(SchedulerVersion.DUAL_POOL_V2)).thenReturn(0);
            when(leaseStore.find(anyString())).thenReturn(Optional.empty());
            registry = new DualPoolRunAdmissionRegistry(permitLedger, workItems, runs, leaseStore, identity, 120L);
        }

        private void residue(SchedulerVersion version, List<NodeWorkItem> items) {
            when(workItems.countUnfinishedBySchedulerVersion(version)).thenReturn(items.size());
            when(workItems.listUnfinishedBySchedulerVersion(eq(version), anyInt())).thenReturn(items);
        }

        private void run(AgentRun run) {
            when(runs.findById(run.getId())).thenReturn(run);
        }

        private void leaseAcquired() {
            when(leaseStore.acquire(anyString(), anyString(), any())).thenReturn(
                    Optional.of(new RunServiceLease("run-2", "test-instance", 1L,
                            OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(2))));
            when(workItems.requeueAbandonedClaim(any(), anyInt(), anyLong(), anyLong()))
                    .thenReturn(NodeWorkItemMutationResult.success());
        }

        @SuppressWarnings("unchecked")
        private String isolationReasonOf(String runId) {
            Map<String, Object> versions = (Map<String, Object>) registry.startupSnapshot().get("versions");
            Map<String, Object> one = (Map<String, Object>) versions.get(SchedulerVersion.DUAL_POOL_V2.name());
            return ((Map<String, String>) one.get("isolatedReasons")).get(runId);
        }
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
