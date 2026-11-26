package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.asynctask.AsyncToolTask;
import world.willfrog.agent.platform.asynctask.AsyncToolTaskStore;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanceledWaitMemberStopWorkerTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AsyncToolTaskStore tasks;
    private WaitGroupStore groups;
    private PythonSandboxService sandbox;
    private WaitMemberSettlement settlement;
    private RunOwnershipGateway ownership;
    private CanceledWaitMemberStopWorker worker;
    private AsyncToolTask task;
    private WaitMember member;

    @AfterEach
    void clearLane() {
        LaneContext.clear();
    }

    @BeforeEach
    void setUp() throws Exception {
        tasks = mock(AsyncToolTaskStore.class);
        groups = mock(WaitGroupStore.class);
        sandbox = mock(PythonSandboxService.class);
        settlement = mock(WaitMemberSettlement.class);
        ownership = mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(
                new DeploymentIdentity("stable", "gen-" + "a".repeat(64)));
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setLaneTag("lane-test");
        when(ownership.findOwnedRun("run-1")).thenReturn(run);
        worker = new CanceledWaitMemberStopWorker(tasks, groups, sandbox,
                settlement, objectMapper, ownership,
                mock(DualPoolRunAdmissionRegistry.class), 2, 120, 5);
        task = new AsyncToolTask();
        task.setJobId(41L);
        task.setRunId("run-1");
        task.setGroupId(7L);
        task.setMemberIdentity("call-1");
        task.setExternalOperationId("run-1:call-1:1");
        task.setTaskId("task-1");
        task.setState("CANCELED");
        task.setClaimEpoch(1L);
        task.setRequestJson(proofJson("task-1"));
        when(tasks.scanStopDue(any(), anyLong(), anyInt())).thenReturn(List.of(task));
        when(tasks.claimStop(eq(41L), any(), any(), any()))
                .thenReturn(Optional.of(task), Optional.empty());

        member = new WaitMember();
        member.setId(41L);
        member.setRunId("run-1");
        member.setGroupId(7L);
        member.setToolCallId("call-1");
        member.setMemberIdentity("call-1");
        member.setToolName("executePython");
        member.setExternalOperationId("run-1:call-1:1");
        member.setState("CANCELED");
        member.setDispatchProofJson(objectMapper.writeValueAsString(new WaitMemberDispatchProof(
                1, "run-1:call-1:1", "task-1", FINGERPRINT,
                "{}", "{}", "{}", "2026-01-01T00:00:00Z")));
        when(groups.findMemberByOperation("run-1", "run-1:call-1:1"))
                .thenReturn(Optional.of(member));
    }

    @Test
    void sandboxLookupUsesPersistedLaneAndRestoresWorkerThread() {
        LaneContext.setTrafficScopeId("previous-thread-lane");
        when(sandbox.getTaskByOperationId(any())).thenAnswer(ignored -> {
            assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
            assertThat(LaneContext.officialDubboTag()).isEqualTo("lane-test");
            return GetTaskByOperationIdResponse.newBuilder().setFound(false).build();
        });
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder().build());

        worker.runBatch();

        assertThat(LaneContext.trafficScopeId()).isEqualTo("previous-thread-lane");
        verify(tasks, atLeastOnce()).claimStop(eq(41L), any(), any(), any());
    }

    @Test
    void mainBetaStopUsesUntaggedSandboxRoute() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setLaneTag(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID);
        when(ownership.findOwnedRun("run-1")).thenReturn(run);
        when(sandbox.getTaskByOperationId(any())).thenAnswer(ignored -> {
            assertThat(LaneContext.officialDubboTag()).isNull();
            return GetTaskByOperationIdResponse.newBuilder().setFound(false).build();
        });
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder().build());

        worker.runBatch();

        verify(sandbox).getTaskByOperationId(any());
        assertThat(LaneContext.trafficScopeId()).isNull();
    }

    @Test
    void lostRunOwnershipDoesNotCallSandbox() {
        when(ownership.findOwnedRun("run-1")).thenReturn(null);

        worker.runBatch();

        org.mockito.Mockito.verifyNoInteractions(sandbox);
        verify(tasks).retryStop(eq(41L), any(), eq(1L), any(), eq("run_ownership_unavailable"));
    }

    @Test
    void terminalResultIsSettledBeforeStopConfirmation() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", FINGERPRINT));
        when(sandbox.cancelTask(any())).thenReturn(cancel(CancelOutcome.CANCELED, "CANCELED"));
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED")
                .setFinishedAt("2026-09-25T02:03:04.123456").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED").setError("canceled").build());
        when(settlement.settle(any(), any(), eq("CANCELED"), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));
        when(tasks.finishStop(eq(41L), any(), eq(1L))).thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        var order = org.mockito.Mockito.inOrder(settlement, tasks);
        order.verify(settlement).settle(any(), any(), eq("CANCELED"), any(), any(), any());
        order.verify(tasks).finishStop(eq(41L), any(), eq(1L));
        verify(sandbox).cancelTask(org.mockito.ArgumentMatchers.argThat(request ->
                request.getCancelRequestId().equals("async-task-41")
                        && request.hasByOperation()
                        && request.getByOperation().getOperationId().equals("run-1:call-1:1")
                        && request.getByOperation().getRequestFingerprint().equals(FINGERPRINT)));
        verify(tasks, never()).retryStop(anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    void lateMemberWithSameOperationAndProofStillStopsSandboxTask() {
        member.setState("LATE");
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", FINGERPRINT));
        when(sandbox.cancelTask(any())).thenReturn(cancel(CancelOutcome.CANCELED, "CANCELED"));
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED")
                .setFinishedAt("2026-09-25T02:03:04.123456").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED").setError("canceled").build());
        when(settlement.settle(any(), any(), eq("CANCELED"), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));
        when(tasks.finishStop(eq(41L), any(), eq(1L))).thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(tasks).finishStop(eq(41L), any(), eq(1L));
        verify(tasks, never()).abandonStop(anyLong(), any(), anyLong(), any());
    }

    @Test
    void authoritativeLookupAbsenceStillSendsOperationCancellationTombstone() {
        task.setTaskId(null);
        member.setDispatchProofJson(proofJson(null));
        when(sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false).build());
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId("task-1").setStatus("CANCELED").build());
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED")
                .setFinishedAt("2026-09-25T02:03:04.123456").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED").setError("canceled before create").build());
        when(settlement.settle(any(), any(), eq("CANCELED"), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));
        when(tasks.finishStop(eq(41L), any(), eq(1L))).thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(sandbox).cancelTask(any());
        verify(settlement).settle(any(), any(), eq("CANCELED"), any(), any(), any());
    }

    @Test
    void mismatchedOperationFingerprintBlocksBeforeCancellationRpc() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", "sha256:" + "b".repeat(64)));

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(tasks).abandonStop(eq(41L), any(), eq(1L), eq("sandbox_operation_identity_mismatch"));
        verify(sandbox, never()).cancelTask(any());
        verify(settlement, never()).settle(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancellationIntentAndRunningStatusKeepTaskForRetry() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", FINGERPRINT));
        when(sandbox.cancelTask(any())).thenReturn(cancel(CancelOutcome.CANCEL_INTENT_RECORDED, "RUNNING"));
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("task-1").setStatus("RUNNING").build());

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(tasks).retryStop(eq(41L), any(), eq(1L), any(), eq("sandbox_not_terminal"));
        verify(settlement, never()).settle(any(), any(), any(), any(), any(), any());
        verify(tasks, never()).finishStop(anyLong(), any(), anyLong());
    }

    @Test
    void mismatchedStatusTaskIdBlocksWithoutSettling() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", FINGERPRINT));
        when(sandbox.cancelTask(any())).thenReturn(cancel(CancelOutcome.CANCELED, "CANCELED"));
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("other-task").setStatus("CANCELED").build());

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(tasks).abandonStop(eq(41L), any(), eq(1L), eq("sandbox_status_identity_mismatch"));
        verify(settlement, never()).settle(any(), any(), any(), any(), any(), any());
    }

    @Test
    void subAgentMemberNeverCallsPythonSandbox() {
        member.setToolName("spawnSubAgent");

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(tasks).abandonStop(eq(41L), any(), eq(1L), eq("wait_member_identity_mismatch"));
        verify(sandbox, never()).cancelTask(any());
    }

    private static GetTaskByOperationIdResponse lookup(String taskId, String fingerprint) {
        return GetTaskByOperationIdResponse.newBuilder().setFound(true).setTaskId(taskId)
                .setRequestFingerprint(fingerprint).setStatus("RUNNING").build();
    }

    private static CancelTaskResponse cancel(CancelOutcome outcome, String status) {
        return CancelTaskResponse.newBuilder().setOutcome(outcome)
                .setTaskId("task-1").setStatus(status).build();
    }

    private String proofJson(String taskId) {
        try {
            return objectMapper.writeValueAsString(new WaitMemberDispatchProof(
                    1, "run-1:call-1:1", taskId, FINGERPRINT,
                    "{}", "{}", "{}", "2026-01-01T00:00:00Z"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
