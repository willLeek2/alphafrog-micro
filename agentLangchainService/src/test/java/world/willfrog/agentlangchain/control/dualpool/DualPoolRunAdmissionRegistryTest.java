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
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final String GENERATION = "gen-" + "a".repeat(64);


    /**
     * 只量名额语义的替身：服务所有权交给一条「取一次就成功」的服务所有权。受理这条路本来就要先拿到
     * 这条 Run 的服务所有权，拿不到的进程不该受理它，所以替身也必须把这一关摆出来。
     */
    private static DualPoolRunAdmissionRegistry registryThatCanOwnRuns(
            SchedulerPermitLedger permitLedger, NodeWorkItemStore workItems, AgentRunMapper runs) {
        return registryThatCanOwnRuns(permitLedger, workItems, runs, RootRunResolver.identityForTests());
    }

    private static DualPoolRunAdmissionRegistry registryThatCanOwnRuns(
            SchedulerPermitLedger permitLedger, NodeWorkItemStore workItems, AgentRunMapper runs,
            RootRunResolver resolver) {
        RunServiceLeaseStore leaseStore = mock(RunServiceLeaseStore.class);
        ProcessInstanceIdentity identity = mock(ProcessInstanceIdentity.class);
        when(identity.value()).thenReturn("test-instance");
        when(leaseStore.acquire(anyString(), anyString(), any())).thenAnswer(invocation ->
                Optional.of(liveLease(invocation.getArgument(0, String.class))));
        when(leaseStore.find(anyString())).thenAnswer(invocation ->
                Optional.of(liveLease(invocation.getArgument(0, String.class))));
        return new DualPoolRunAdmissionRegistry(permitLedger, workItems, runs, leaseStore, identity, 120L,
                new FrozenEffectiveSettings(), resolver);
    }

    private static RunServiceLease liveLease(String runId) {
        return new RunServiceLease(runId, "test-instance", 1L,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(2));
    }













    // ==================== 完整 DAG（DUAL_POOL_V2）的启动恢复 ====================












    /** 一次启动扫描的替身：两版残留都按最省事的方式答「没有」，用到哪条再单独放宽。 */
    private static final class Fixture {
        private final SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        private final NodeWorkItemStore workItems = mock(NodeWorkItemStore.class);
        private final AgentRunMapper runs = mock(AgentRunMapper.class);
        private final RunServiceLeaseStore leaseStore = mock(RunServiceLeaseStore.class);
        private final ProcessInstanceIdentity identity = mock(ProcessInstanceIdentity.class);
        private final RunOwnershipGateway ownership;
        private final DualPoolRunAdmissionRegistry registry;

        private Fixture() {
            this(null, null);
        }

        private Fixture(RunOwnershipGateway ownership, DualPoolSchedulerSettings settings) {
            this.ownership = ownership;
            permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 4);
            when(identity.value()).thenReturn("test-instance");
            if (ownership == null) {
                when(workItems.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V1)).thenReturn(0);
                when(workItems.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V2)).thenReturn(0);
            } else {
                when(workItems.countUnfinishedByRunSchedulerVersionForDeployment(
                        any(), any(), any(), any())).thenReturn(0);
            }
            when(leaseStore.find(anyString())).thenReturn(Optional.empty());
            // 默认这条 Run 的服务所有权取得得到；「在别人手上」的用例再单独把这一条改掉。
            when(leaseStore.acquire(anyString(), anyString(), any())).thenAnswer(invocation ->
                    Optional.of(liveLease(invocation.getArgument(0, String.class))));
            registry = new DualPoolRunAdmissionRegistry(permitLedger, workItems, runs, leaseStore, identity,
                    120L, new FrozenEffectiveSettings(), RootRunResolver.identityForTests(),
                    ownership, settings);
        }

        private void residue(SchedulerVersion version, List<NodeWorkItem> items) {
            if (ownership == null) {
                when(workItems.countUnfinishedByRunSchedulerVersion(version)).thenReturn(items.size());
                when(workItems.listUnfinishedByRunSchedulerVersion(eq(version), anyInt())).thenReturn(items);
                return;
            }
            when(workItems.countUnfinishedByRunSchedulerVersionForDeployment(
                    eq(version), any(), any(), any())).thenReturn(items.size());
            when(workItems.listUnfinishedByRunSchedulerVersionForDeployment(
                    eq(version), any(), any(), any(), anyInt())).thenReturn(items);
        }

        private void thisProcessOwns(String runId) {
            when(leaseStore.find(runId)).thenReturn(Optional.of(liveLease(runId)));
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
