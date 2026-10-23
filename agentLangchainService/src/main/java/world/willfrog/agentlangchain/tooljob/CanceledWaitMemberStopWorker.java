package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.observability.ObservabilityEvents;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.wait.WaitMemberStopStore;
import world.willfrog.agent.platform.wait.WaitMemberStopTask;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.dataanalysis.DurableSandboxTool;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 取消等待链后继续收回外部 Sandbox 作业，进程重启后从持久任务表续做。 */
@Component
@Slf4j
public class CanceledWaitMemberStopWorker {
    private final WaitMemberStopStore stops;
    private final RunOwnershipGateway ownership;
    /** 只用于事件里的根运行编号：已受理的 Run 内存里带着根树身份。 */
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final WaitGroupStore groups;
    private final PythonSandboxService sandbox;
    private final WaitMemberSettlement settlement;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private static final Pattern OPERATION_ID_PATTERN =
            Pattern.compile("^[^:\\s]+:[^:\\s]+:[1-9][0-9]*$");
    private static final Pattern REQUEST_FINGERPRINT_PATTERN =
            Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final int batchSize;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;
    private final String owner = UUID.randomUUID().toString();

    public CanceledWaitMemberStopWorker(WaitMemberStopStore stops, WaitGroupStore groups,
                                        PythonSandboxService sandbox, WaitMemberSettlement settlement,
                                        ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                        RunOwnershipGateway ownership,
                                        DualPoolRunAdmissionRegistry admissionRegistry,
                                        @Value("${agent.langchain.wait-member.stop.batch-size:16}") int batchSize,
                                        @Value("${agent.langchain.wait-member.stop.lease-seconds:120}") long leaseSeconds,
                                        @Value("${agent.langchain.wait-member.stop.max-attempts:8}") int maxAttempts,
                                        @Value("${agent.langchain.wait-member.stop.backoff-base-seconds:5}") long backoffBaseSeconds,
                                        @Value("${agent.langchain.wait-member.stop.backoff-max-seconds:120}") long backoffMaxSeconds) {
        if (batchSize < 1 || batchSize > 1000 || leaseSeconds < 1
                || maxAttempts < 1 || backoffBaseSeconds < 1 || backoffMaxSeconds < backoffBaseSeconds) {
            throw new IllegalArgumentException("停机 worker 参数无效");
        }
        this.stops = stops;
        this.ownership = ownership;
        this.admissionRegistry = admissionRegistry;
        this.groups = groups;
        this.sandbox = sandbox;
        this.settlement = settlement;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMaxSeconds = backoffMaxSeconds;
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
        var identity = ownership.requireIdentity();
        int processed = 0;
        for (int index = 0; index < batchSize; index++) {
            OffsetDateTime now = OffsetDateTime.now();
            String token = UUID.randomUUID().toString();
            Optional<WaitMemberStopTask> next = transaction.execute(ignored ->
                    stops.claimDue(identity.deploymentId(), identity.generationId(),
                            owner, token, now, now.plusSeconds(leaseSeconds)));
            if (next == null || next.isEmpty()) break;
            WaitMemberStopTask stop = next.get();
            try {
                AgentRun run = ownership.findOwnedRun(stop.getRunId());
                if (run == null) {
                    retryOrUnconfirm(stop, "run_ownership_unavailable");
                } else {
                    LaneScopeGateway.wrap(run, () -> process(stop)).run();
                }
            } catch (RuntimeException e) {
                log.warn("外部等待成员停机暂未收尾：stop={} reason={}", stop.getId(), e.getMessage());
                retryOrUnconfirm(stop, "unexpected_failure");
            }
            processed++;
        }
        return processed;
    }

    private void process(WaitMemberStopTask stop) {
        int attempts = stop.getAttemptCount() == null ? 0 : stop.getAttemptCount();
        if (attempts > maxAttempts) {
            // 升级遗留或重新领取后的旧记录已经超过预算：不再发任何外部请求。
            unconfirm(stop, "attempts_exhausted");
            return;
        }
        if (stop.getWaitMemberId() == null || stop.getGroupId() == null
                || stop.getOperationId() == null || stop.getOperationId().isBlank()
                || stop.getRequestFingerprint() == null || stop.getRequestFingerprint().isBlank()
                || stop.getCancelRequestId() == null || stop.getCancelRequestId().isBlank()) {
            unconfirm(stop, "stop_identity_incomplete");
            return;
        }
        WaitMember member = groups.findMemberByOperation(stop.getRunId(), stop.getOperationId()).orElse(null);
        if (member == null || !stop.getWaitMemberId().equals(member.getId())
                || !stop.getGroupId().equals(member.getGroupId())
                || (member.stateEnum() != WaitMemberState.CANCELED
                    && member.stateEnum() != WaitMemberState.LATE)
                || DurableSandboxTool.fromToolName(member.getToolName()).isEmpty()) {
            unconfirm(stop, "wait_member_identity_mismatch");
            return;
        }
        WaitMemberDispatchProof proof = WaitMemberDispatchProof.fromJson(objectMapper,
                member.getDispatchProofJson()).orElse(null);
        if (proof == null) {
            // 派发证明缺失或无法解析：不完整的凭证不能先发给外部接口。
            unconfirm(stop, "dispatch_proof_format_invalid");
            return;
        }
        if (!stop.getOperationId().equals(proof.operationId())
                || !stop.getRequestFingerprint().equals(proof.requestFingerprint())
                || stop.getTaskId() != null && proof.taskId() != null
                   && !stop.getTaskId().equals(proof.taskId())) {
            unconfirm(stop, "dispatch_proof_identity_mismatch");
            return;
        }
        // 原创建语句里的两项格式校验移到这里：不完整的凭证不能先发给外部接口。
        if (!OPERATION_ID_PATTERN.matcher(stop.getOperationId()).matches()
                || !REQUEST_FINGERPRINT_PATTERN.matcher(stop.getRequestFingerprint()).matches()) {
            unconfirm(stop, "dispatch_proof_format_invalid");
            return;
        }

        GetTaskByOperationIdResponse lookup = sandbox.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId(stop.getOperationId()).build());
        if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()) {
            retryOrUnconfirm(stop, "operation_lookup_unavailable");
            return;
        }
        String knownTaskId = stop.getTaskId() != null ? stop.getTaskId() : proof.taskId();
        if (lookup.getFound()) {
            if (lookup.getTaskId().isBlank() || lookup.getRequestFingerprint().isBlank()
                    || !stop.getRequestFingerprint().equals(lookup.getRequestFingerprint())
                    || knownTaskId != null && !knownTaskId.equals(lookup.getTaskId())) {
                unconfirm(stop, "sandbox_operation_identity_mismatch");
                return;
            }
            knownTaskId = lookup.getTaskId();
        } else if (!lookup.getTaskId().isBlank()) {
            unconfirm(stop, "sandbox_operation_absence_conflict");
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
            SandboxErrorDetail detail = cancel != null && cancel.hasErrorDetail()
                    ? cancel.getErrorDetail() : null;
            // 先看保留下来的下游状态码：405 在网关会归入未分类，不能等到后面按类别重试。
            int downstreamStatus = detail != null && detail.hasDownstreamHttpStatus()
                    ? detail.getDownstreamHttpStatus() : 0;
            if (downstreamStatus == 404 || downstreamStatus == 405) {
                // 第一次取消调用就明确回答“这个接口不存在”：能力证据已经拿到，不再重试。
                unconfirm(stop, "cancel_endpoint_unsupported");
                return;
            }
            if (detail != null && (detail.getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT
                    || detail.getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)) {
                unconfirm(stop, "cancel_rejected:" + detail.getCategory().name());
                return;
            }
            // 超时、过载、传输失败、下游 5xx、未分类：调用链暂时不可用，按不确定重试。
            retryOrUnconfirm(stop, "sandbox_cancel_unavailable");
            return;
        }
        if (cancel.getOutcome() == CancelOutcome.NOT_FOUND) {
            // 业务任务不存在（HTTP 200 的取消结果），不是接口不可用。
            unconfirm(stop, "task_not_found");
            return;
        }
        if (cancel.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED
                || cancel.getTaskId().isBlank()
                || knownTaskId != null && !knownTaskId.equals(cancel.getTaskId())) {
            unconfirm(stop, "sandbox_cancel_identity_mismatch");
            return;
        }
        String taskId = cancel.getTaskId();
        TaskStatusResponse status = sandbox.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        // 终态 FAILED 的 error 是任务失败原因，不是状态查询失败；传输失败由 errorDetail 表达。
        if (status == null || status.hasErrorDetail()) {
            retryOrUnconfirm(stop, "sandbox_status_unavailable");
            return;
        }
        if (!status.getTaskId().isBlank() && !taskId.equals(status.getTaskId())) {
            unconfirm(stop, "sandbox_status_identity_mismatch");
            return;
        }
        String terminalStatus = status.getStatus();
        if ("QUEUED".equals(terminalStatus) || "RUNNING".equals(terminalStatus)) {
            retryOrUnconfirm(stop, "sandbox_not_terminal");
            return;
        }
        if (!"SUCCEEDED".equals(terminalStatus) && !"FAILED".equals(terminalStatus)
                && !"CANCELED".equals(terminalStatus)) {
            unconfirm(stop, "sandbox_status_unknown");
            return;
        }
        TaskResultResponse result = sandbox.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
        if (result != null && !result.getTaskId().isBlank()
                && !taskId.equals(result.getTaskId())) {
            unconfirm(stop, "sandbox_result_identity_mismatch");
            return;
        }
        result = ToolJobResultValidator.validate(taskId, stop.getRunId(), result, terminalStatus);
        if (result == null) {
            retryOrUnconfirm(stop, "sandbox_terminal_result_unavailable");
            return;
        }
        String preview = !result.getStdout().isBlank() ? result.getStdout()
                : !result.getStderr().isBlank() ? result.getStderr() : result.getError();
        WaitMemberSettlement.Outcome settled = settlement.settle(member, proof,
                terminalStatus, result, preview, status.getFinishedAt());
        if (!settled.ok()) {
            retryOrUnconfirm(stop, "sandbox_settlement_incomplete");
            return;
        }
        String confirmedTaskId = taskId;
        String confirmedStatus = terminalStatus;
        Boolean confirmed = transaction.execute(ignored -> stops.confirmSandboxTerminal(
                stop.getId(), stop.getClaimToken(), confirmedTaskId, confirmedStatus));
        if (!Boolean.TRUE.equals(confirmed)) {
            retryOrUnconfirm(stop, "terminal_evidence_not_committed");
            return;
        }
        // 合同里的停止确认事件：终态证据已经提交成功，这里报一次「外部停止已确认」。
        // 逐次核对的结果不报——那是这条工作循环的热路径，报多了拖执行；关闭时是空操作。
        ObservabilityEvents.externalStopChecked()
                .rootRunId(admissionRegistry.rootRunIdIfAdmitted(stop.getRunId())
                        .orElse(stop.getRunId()))
                .operationId(stop.getOperationId())
                .decision("confirmed")
                .outcome("success")
                .summary("外部停止已确认，终态证据已提交")
                .emit();
    }

    /**
     * 所有普通重试出口共用的有限预算：领取次数已经到上限就直接结束为未确认，
     * 其余按 5、10、20、40、80、120、120 秒逐次拉长间隔。
     */
    private void retryOrUnconfirm(WaitMemberStopTask stop, String reason) {
        int attempts = stop.getAttemptCount() == null ? 0 : stop.getAttemptCount();
        if (attempts >= maxAttempts) {
            unconfirm(stop, reason + ":attempts_exhausted");
            return;
        }
        long delaySeconds = backoffBaseSeconds;
        for (int index = 1; index < attempts && delaySeconds < backoffMaxSeconds; index++) {
            delaySeconds = Math.min(backoffMaxSeconds, delaySeconds * 2);
        }
        long nextDelay = delaySeconds;
        transaction.executeWithoutResult(ignored -> stops.retry(stop.getId(), stop.getClaimToken(),
                OffsetDateTime.now().plusSeconds(nextDelay), reason));
    }

    /** 结束自动尝试并保留任务；只有条件更新确实成功后才写 ERROR 日志。 */
    private void unconfirm(WaitMemberStopTask stop, String reason) {
        String limited = reason.length() > 512 ? reason.substring(0, 512) : reason;
        Boolean updated = transaction.execute(ignored ->
                stops.unconfirm(stop.getId(), stop.getClaimToken(), limited));
        if (Boolean.TRUE.equals(updated)) {
            // 更新失败说明领取令牌已经失效，不能冒充这次未确认已经落库。
            log.error("外部作业停止未确认，已停止自动重试：run={} stop={} operation={} attempts={} reason={}",
                    stop.getRunId(), stop.getId(), stop.getOperationId(),
                    stop.getAttemptCount(), limited);
        }
    }
}
