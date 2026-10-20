package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.RunCoordinationHint;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentChildRunCoordinatorTest {

    @Test
    void restoreAcquiresOwnershipBeforeAdmit() {
        ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
        RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
        DualPoolRunAdmissionRegistry admission = mock(DualPoolRunAdmissionRegistry.class);
        DualPoolRunPipeline pipeline = mock(DualPoolRunPipeline.class);
        String generation = "gen-" + "a".repeat(64);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("dep", generation));
        when(intents.listAcceptedChildrenNeedingLaunchForDeployment(0L, 32, "dep", generation))
                .thenReturn(List.of(intent("child")));
        AgentRun parent = run("parent");
        AgentRun child = run("child");
        when(ownership.findOwnedRun("parent")).thenReturn(parent);
        when(ownership.findOwnedRun("child")).thenReturn(child);
        when(admission.isAdmitted("child")).thenReturn(false);
        when(admission.acquireAcceptedChildOwnership("child")).thenReturn(true);
        when(admission.admitNewRun("child", SchedulerVersion.DUAL_POOL_V2.name())).thenReturn(true);

        coordinator(intents, ownership, admission, pipeline).restoreAcceptedChildren();

        InOrder order = inOrder(admission, pipeline);
        order.verify(admission).acquireAcceptedChildOwnership("child");
        order.verify(admission).admitNewRun("child", SchedulerVersion.DUAL_POOL_V2.name());
        order.verify(pipeline).enqueue(eq(child), eq(RunCoordinationHint.Reason.NEW_RUN));
    }

    @Test
    void restoreStopsWhenOwnershipCannotBeTaken() {
        ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
        RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
        DualPoolRunAdmissionRegistry admission = mock(DualPoolRunAdmissionRegistry.class);
        DualPoolRunPipeline pipeline = mock(DualPoolRunPipeline.class);
        String generation = "gen-" + "a".repeat(64);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("dep", generation));
        when(intents.listAcceptedChildrenNeedingLaunchForDeployment(0L, 32, "dep", generation))
                .thenReturn(List.of(intent("child")));
        when(ownership.findOwnedRun("parent")).thenReturn(run("parent"));
        when(ownership.findOwnedRun("child")).thenReturn(run("child"));
        when(admission.isAdmitted("child")).thenReturn(false);
        when(admission.acquireAcceptedChildOwnership("child")).thenReturn(false);

        coordinator(intents, ownership, admission, pipeline).restoreAcceptedChildren();

        verify(admission, never()).admitNewRun("child", SchedulerVersion.DUAL_POOL_V2.name());
        verify(pipeline, never()).enqueue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static PersistentChildRunCoordinator coordinator(ChildRunIntentStore intents,
                                                             RunOwnershipGateway ownership,
                                                             DualPoolRunAdmissionRegistry admission,
                                                             DualPoolRunPipeline pipeline) {
        return new PersistentChildRunCoordinator(
                intents, mock(AgentRunEventService.class), ownership, mock(WaitGroupStore.class),
                admission, pipeline, mock(PlatformTransactionManager.class), 32);
    }

    private static ChildRunIntentView intent(String childRunId) {
        return new ChildRunIntentView(3L, "root", "parent", childRunId, null, 0L, null, null,
                0, 0, 0L, "ACCEPTED", "RECEIVED", null, OffsetDateTime.now(), null, null, null);
    }

    private static AgentRun run(String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setStatus(AgentRunStatus.RECEIVED);
        return run;
    }
}
