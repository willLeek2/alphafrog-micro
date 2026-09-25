package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.wait.WaitMemberStopStore;
import world.willfrog.agent.platform.wait.WaitMemberStopTask;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 取消等待链后继续收回外部 Sandbox 作业，进程重启后从持久任务表续做。 */
@Component
@Slf4j
public class CanceledWaitMemberStopWorker {
    private final WaitMemberStopStore stops;
    private final WaitGroupStore groups;
    private final PythonSandboxService sandbox;
    private final WaitMemberSettlement settlement;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retrySeconds;
    private final String owner = UUID.randomUUID().toString();

    public CanceledWaitMemberStopWorker(WaitMemberStopStore stops, WaitGroupStore groups,
                                        PythonSandboxService sandbox, WaitMemberSettlement settlement,
                                        ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                        @Value("${agent.langchain.wait-member.stop.batch-size:16}") int batchSize,
                                        @Value("${agent.langchain.wait-member.stop.lease-seconds:120}") long leaseSeconds,
                                        @Value("${agent.langchain.wait-member.stop.retry-seconds:5}") long retrySeconds) {
        if (batchSize < 1 || batchSize > 1000 || leaseSeconds < 1 || retrySeconds < 1) {
            throw new IllegalArgumentException("停机 worker 参数无效");
        }
        this.stops = stops;
        this.groups = groups;
        this.sandbox = sandbox;
        this.settlement = settlement;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retrySeconds = retrySeconds;
    }

    @Scheduled(fixedDelayString = "${agent.langchain.wait-member.stop.interval-ms:1000}")
    public void poll() {
        try {
            runBatch();
        } catch (RuntimeException e) {
            log.error("外部等待成员停机扫描失败，下轮重试", e);
        }
    }

    public int runBatch() {
        int processed = 0;
        for (int index = 0; index < batchSize; index++) {
            OffsetDateTime now = OffsetDateTime.now();
            String token = UUID.randomUUID().toString();
            Optional<WaitMemberStopTask> next = transaction.execute(ignored ->
                    stops.claimDue(owner, token, now, now.plusSeconds(leaseSeconds)));
            if (next == null || next.isEmpty()) break;
            WaitMemberStopTask stop = next.get();
            try {
                process(stop);
            } catch (RuntimeException e) {
                log.warn("外部等待成员停机暂未收尾：stop={} reason={}", stop.getId(), e.getMessage());
                retry(stop, "unexpected_failure");
            }
            processed++;
        }
        return processed;
    }

    private void process(WaitMemberStopTask stop) {
        if (stop.getWaitMemberId() == null || stop.getGroupId() == null
                || stop.getOperationId() == null || stop.getOperationId().isBlank()
                || stop.getRequestFingerprint() == null || stop.getRequestFingerprint().isBlank()
                || stop.getCancelRequestId() == null || stop.getCancelRequestId().isBlank()) {
            block(stop, "stop_identity_incomplete");
            return;
        }
        WaitMember member = groups.findMemberByOperation(stop.getRunId(), stop.getOperationId()).orElse(null);
        if (member == null || !stop.getWaitMemberId().equals(member.getId())
                || !stop.getGroupId().equals(member.getGroupId())
                || (member.stateEnum() != WaitMemberState.CANCELED
                    && member.stateEnum() != WaitMemberState.LATE)
                || !"executePython".equals(member.getToolName())) {
            block(stop, "wait_member_identity_mismatch");
            return;
        }
        WaitMemberDispatchProof proof = WaitMemberDispatchProof.fromJson(objectMapper,
                member.getDispatchProofJson()).orElse(null);
        if (proof == null || !stop.getOperationId().equals(proof.operationId())
                || !stop.getRequestFingerprint().equals(proof.requestFingerprint())
                || stop.getTaskId() != null && proof.taskId() != null
                   && !stop.getTaskId().equals(proof.taskId())) {
            block(stop, "dispatch_proof_identity_mismatch");
            return;
        }

        GetTaskByOperationIdResponse lookup = sandbox.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId(stop.getOperationId()).build());
        if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()) {
            retry(stop, "operation_lookup_unavailable");
            return;
        }
        String knownTaskId = stop.getTaskId() != null ? stop.getTaskId() : proof.taskId();
        if (lookup.getFound()) {
            if (lookup.getTaskId().isBlank() || lookup.getRequestFingerprint().isBlank()
                    || !stop.getRequestFingerprint().equals(lookup.getRequestFingerprint())
                    || knownTaskId != null && !knownTaskId.equals(lookup.getTaskId())) {
                block(stop, "sandbox_operation_identity_mismatch");
                return;
            }
            knownTaskId = lookup.getTaskId();
        } else if (!lookup.getTaskId().isBlank()) {
            block(stop, "sandbox_operation_absence_conflict");
            return;
        }

        CancelTaskResponse cancel = sandbox.cancelTask(CancelTaskRequest.newBuilder()
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId(stop.getOperationId())
                        .setRequestFingerprint(stop.getRequestFingerprint()))
                .setCancelRequestId(stop.getCancelRequestId())
                .setReason("RUN_CANCELED")
                .build());
        if (cancel == null || cancel.hasErrorDetail() || !cancel.getError().isBlank()) {
            if (cancel != null && cancel.hasErrorDetail()
                    && (cancel.getErrorDetail().getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT
                    || cancel.getErrorDetail().getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)) {
                block(stop, "sandbox_cancel_identity_rejected");
            } else {
                retry(stop, "sandbox_cancel_unavailable");
            }
            return;
        }
        if (cancel.getOutcome() == CancelOutcome.NOT_FOUND
                || cancel.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED
                || cancel.getTaskId().isBlank()
                || knownTaskId != null && !knownTaskId.equals(cancel.getTaskId())) {
            block(stop, "sandbox_cancel_identity_mismatch");
            return;
        }
        String taskId = cancel.getTaskId();
        TaskStatusResponse status = sandbox.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        // 终态 FAILED 的 error 是任务失败原因，不是状态查询失败；传输失败由 errorDetail 表达。
        if (status == null || status.hasErrorDetail()) {
            retry(stop, "sandbox_status_unavailable");
            return;
        }
        if (!status.getTaskId().isBlank() && !taskId.equals(status.getTaskId())) {
            block(stop, "sandbox_status_identity_mismatch");
            return;
        }
        String terminalStatus = status.getStatus();
        if ("QUEUED".equals(terminalStatus) || "RUNNING".equals(terminalStatus)) {
            retry(stop, "sandbox_not_terminal");
            return;
        }
        if (!"SUCCEEDED".equals(terminalStatus) && !"FAILED".equals(terminalStatus)
                && !"CANCELED".equals(terminalStatus)) {
            block(stop, "sandbox_status_unknown");
            return;
        }
        TaskResultResponse result = sandbox.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
        if (result != null && !result.getTaskId().isBlank()
                && !taskId.equals(result.getTaskId())) {
            block(stop, "sandbox_result_identity_mismatch");
            return;
        }
        result = ToolJobResultValidator.validate(taskId, stop.getRunId(), result, terminalStatus);
        if (result == null) {
            retry(stop, "sandbox_terminal_result_unavailable");
            return;
        }
        String preview = !result.getStdout().isBlank() ? result.getStdout()
                : !result.getStderr().isBlank() ? result.getStderr() : result.getError();
        WaitMemberSettlement.Outcome settled = settlement.settle(member, proof,
                terminalStatus, result, preview, status.getFinishedAt());
        if (!settled.ok()) {
            retry(stop, "sandbox_settlement_incomplete");
            return;
        }
        String confirmedTaskId = taskId;
        String confirmedStatus = terminalStatus;
        Boolean confirmed = transaction.execute(ignored -> stops.confirmSandboxTerminal(
                stop.getId(), stop.getClaimToken(), confirmedTaskId, confirmedStatus));
        if (!Boolean.TRUE.equals(confirmed)) {
            retry(stop, "terminal_evidence_not_committed");
        }
    }

    private void retry(WaitMemberStopTask stop, String reason) {
        transaction.executeWithoutResult(ignored -> stops.retry(stop.getId(), stop.getClaimToken(),
                OffsetDateTime.now().plusSeconds(retrySeconds), reason));
    }

    private void block(WaitMemberStopTask stop, String reason) {
        transaction.executeWithoutResult(ignored -> stops.blockProof(stop.getId(), stop.getClaimToken(), reason));
        log.error("外部等待成员停机身份不一致，已停止自动重试：stop={} reason={}", stop.getId(), reason);
    }
}
