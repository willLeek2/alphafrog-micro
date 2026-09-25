package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberStopStore;
import world.willfrog.agent.platform.wait.WaitMemberStopTask;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanceledWaitMemberStopWorkerTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WaitMemberStopStore stops;
    private WaitGroupStore groups;
    private PythonSandboxService sandbox;
    private WaitMemberSettlement settlement;
    private CanceledWaitMemberStopWorker worker;
    private WaitMemberStopTask stop;
    private WaitMember member;

    @BeforeEach
    void setUp() throws Exception {
        stops = mock(WaitMemberStopStore.class);
        groups = mock(WaitGroupStore.class);
        sandbox = mock(PythonSandboxService.class);
        settlement = mock(WaitMemberSettlement.class);
        PlatformTransactionManager transaction = mock(PlatformTransactionManager.class);
        when(transaction.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        worker = new CanceledWaitMemberStopWorker(stops, groups, sandbox,
                settlement, objectMapper, transaction, 2, 120, 5);
        stop = new WaitMemberStopTask();
        stop.setId(9L);
        stop.setWaitMemberId(41L);
        stop.setRunId("run-1");
        stop.setGroupId(7L);
        stop.setOperationId("run-1:call-1:1");
        stop.setTaskId("task-1");
        stop.setRequestFingerprint(FINGERPRINT);
        stop.setCancelRequestId("wait-member-41");
        stop.setClaimToken("claim-1");
        when(stops.claimDue(any(), any(), any(), any()))
                .thenReturn(Optional.of(stop), Optional.empty());

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
        when(stops.confirmSandboxTerminal(9L, "claim-1", "task-1", "CANCELED"))
                .thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        var order = org.mockito.Mockito.inOrder(settlement, stops);
        order.verify(settlement).settle(any(), any(), eq("CANCELED"), any(), any(), any());
        order.verify(stops).confirmSandboxTerminal(9L, "claim-1", "task-1", "CANCELED");
        verify(sandbox).cancelTask(org.mockito.ArgumentMatchers.argThat(request ->
                request.getCancelRequestId().equals("wait-member-41")
                        && request.hasByOperation()
                        && request.getByOperation().getOperationId().equals("run-1:call-1:1")
                        && request.getByOperation().getRequestFingerprint().equals(FINGERPRINT)));
        verify(stops, never()).retry(anyLong(), any(), any(), any());
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
        when(stops.confirmSandboxTerminal(9L, "claim-1", "task-1", "CANCELED"))
                .thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(stops).confirmSandboxTerminal(9L, "claim-1", "task-1", "CANCELED");
        verify(stops, never()).blockProof(anyLong(), any(), any());
    }

    @Test
    void authoritativeLookupAbsenceStillSendsOperationCancellationTombstone() {
        stop.setTaskId(null);
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
        when(stops.confirmSandboxTerminal(9L, "claim-1", "task-1", "CANCELED"))
                .thenReturn(true);

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(sandbox).cancelTask(any());
        verify(settlement).settle(any(), any(), eq("CANCELED"), any(), any(), any());
    }

    @Test
    void mismatchedOperationFingerprintBlocksBeforeCancellationRpc() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", "sha256:" + "b".repeat(64)));

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(stops).blockProof(9L, "claim-1", "sandbox_operation_identity_mismatch");
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
        verify(stops).retry(eq(9L), eq("claim-1"), any(), eq("sandbox_not_terminal"));
        verify(settlement, never()).settle(any(), any(), any(), any(), any(), any());
        verify(stops, never()).confirmSandboxTerminal(anyLong(), any(), any(), any());
    }

    @Test
    void mismatchedStatusTaskIdBlocksWithoutSettling() {
        when(sandbox.getTaskByOperationId(any())).thenReturn(lookup("task-1", FINGERPRINT));
        when(sandbox.cancelTask(any())).thenReturn(cancel(CancelOutcome.CANCELED, "CANCELED"));
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId("other-task").setStatus("CANCELED").build());

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(stops).blockProof(9L, "claim-1", "sandbox_status_identity_mismatch");
        verify(settlement, never()).settle(any(), any(), any(), any(), any(), any());
    }

    @Test
    void subAgentMemberNeverCallsPythonSandbox() {
        member.setToolName("spawnSubAgent");

        assertThat(worker.runBatch()).isEqualTo(1);
        verify(stops).blockProof(9L, "claim-1", "wait_member_identity_mismatch");
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
