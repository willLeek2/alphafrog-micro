package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.facade.LangchainRunControlService;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentChildRunLifecycleReconcilerTest {
    private final ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final LangchainRunControlService controls = mock(LangchainRunControlService.class);
    private final DualPoolRunAdmissionRegistry admissions = mock(DualPoolRunAdmissionRegistry.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final PersistentChildRunLifecycleReconciler reconciler =
            new PersistentChildRunLifecycleReconciler(intents, runs, controls, admissions,
                    transactionManager);

    @Test
    void anchoredParentCancelVersionStopsBothPendingAndAcceptedChildren() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(intents.listReservedRootRunIds(null, 100)).thenReturn(List.of("parent"));
        when(intents.listUnsettledByRoot("parent", 0L, 100)).thenReturn(List.of(
                child(1L, "pending", null),
                child(2L, "accepted", OffsetDateTime.now())));
        when(runs.findById("parent")).thenReturn(run("parent", AgentRunStatus.WAITING_TOOL_JOB, 1L));
        when(runs.findById("accepted")).thenReturn(run("accepted", AgentRunStatus.EXECUTING, 0L));

        reconciler.reconcile();

        verify(intents).cancelUnacceptedIfParentChanged(1L);
        verify(intents).requestCancellation("accepted");
        verify(controls).cancelRun(org.mockito.ArgumentMatchers.argThat(request ->
                request.getId().equals("accepted") && request.getUserId().equals("user")));
    }

    @Test
    void unchangedWaitingParentDoesNotCancelAcceptedChild() {
        when(intents.listReservedRootRunIds(null, 100)).thenReturn(List.of("parent"));
        when(intents.listUnsettledByRoot("parent", 0L, 100)).thenReturn(List.of(
                child(2L, "accepted", OffsetDateTime.now())));
        when(runs.findById("parent")).thenReturn(run("parent", AgentRunStatus.WAITING_TOOL_JOB, 0L));
        when(runs.findById("accepted")).thenReturn(run("accepted", AgentRunStatus.EXECUTING, 0L));

        reconciler.reconcile();

        verify(intents, never()).requestCancellation(eq("accepted"));
        verify(controls, never()).cancelRun(any());
    }

    private static ChildRunIntentView child(long id, String childRunId, OffsetDateTime acceptedAt) {
        return new ChildRunIntentView(id, "parent", "parent", childRunId,
                "operation-" + id, 12L, "member-" + id, "call-" + id,
                0, 0, 0L, acceptedAt == null ? "PENDING" : "ACCEPTED",
                null, "RUNNING", acceptedAt, null, null, null);
    }

    private static AgentRun run(String id, AgentRunStatus status, long controlVersion) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("user");
        run.setStatus(status);
        run.setPlanGeneration(0);
        run.setRunControlVersion(controlVersion);
        return run;
    }
}
