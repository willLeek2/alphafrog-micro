package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DualPoolRunAdmissionRegistryTest {

    @Test
    void oldLifecycleCannotReleaseNewFollowUpAdmission() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, mock(NodeWorkItemStore.class));

        assertThat(registry.admitNewRun("run-1")).isTrue();
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

        assertThat(registry.admitNewRun("run-1")).isTrue();
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
