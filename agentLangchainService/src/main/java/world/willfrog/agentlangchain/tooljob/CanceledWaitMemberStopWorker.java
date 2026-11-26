package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.asynctask.AsyncToolTask;
import world.willfrog.agent.platform.asynctask.AsyncToolTaskStore;
import world.willfrog.agent.platform.dataanalysis.DurableSandboxTool;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.observability.ObservabilityEvents;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
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
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 取消等待链后继续收回外部沙箱作业，进程重启后从异步工具任务表续做。
 *
 * <p>停机意图记在任务行的停机字段上：待停止或已领取、租约到期的任务才进入本轮候选。
 * 停止状态只有三种：待停止、已领取、已收尾。身份对不上或没有外部作业的任务不再重试，
 * 直接收尾并把原因留在最后错误里。</p>
 *
 * <p>领取后不另发续租语句：一行最多做四次沙箱往返（回查作业、取消、查状态、取结果），
 * 租约默认 120 秒，比一次处理的实际耗时长一个数量级。改小租约或给这一行加沙箱调用之前，
 * 先在这里补续租，否则另一实例可能在处理途中接手同一行。</p>
 */
@Component
@Slf4j
public class CanceledWaitMemberStopWorker {
    private final AsyncToolTaskStore tasks;
    private final RunOwnershipGateway ownership;
    /** 只用于事件里的根运行编号：已受理的 Run 内存里带着根树身份。 */
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final WaitGroupStore groups;
    private final PythonSandboxService sandbox;
    private final WaitMemberSettlement settlement;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retrySeconds;
    /** 同一任务最多重试几次停机；用尽后停止自动重试并报警，不再每 5 秒空转。 */
    private final int maxAttempts;
    private final String owner = UUID.randomUUID().toString();

    public CanceledWaitMemberStopWorker(AsyncToolTaskStore tasks, WaitGroupStore groups,
                                        PythonSandboxService sandbox, WaitMemberSettlement settlement,
                                        ObjectMapper objectMapper,
                                        RunOwnershipGateway ownership,
                                        DualPoolRunAdmissionRegistry admissionRegistry,
                                        @Value("${agent.langchain.wait-member.stop.batch-size:16}") int batchSize,
                                        @Value("${agent.langchain.wait-member.stop.lease-seconds:120}") long leaseSeconds,
                                        @Value("${agent.langchain.wait-member.stop.retry-seconds:5}") long retrySeconds,
                                        @Value("${agent.langchain.wait-member.stop.max-attempts:30}") int maxAttempts) {
        if (batchSize < 1 || batchSize > 1000 || leaseSeconds < 1 || retrySeconds < 1
                || maxAttempts < 1) {
            throw new IllegalArgumentException("停机 worker 参数无效");
        }
        this.tasks = tasks;
        this.ownership = ownership;
        this.admissionRegistry = admissionRegistry;
        this.groups = groups;
        this.sandbox = sandbox;
        this.settlement = settlement;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retrySeconds = retrySeconds;
        this.maxAttempts = maxAttempts;
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
        ownership.requireIdentity();
        int processed = 0;
        long afterJobId = 0;
        while (processed < batchSize) {
            // 每轮扫描用自己的时刻：批内处理可能耗时，不能拿批开始时的时刻判断「到期」。
            List<AsyncToolTask> due = tasks.scanStopDue(OffsetDateTime.now(), afterJobId, batchSize);
            if (due.isEmpty()) {
                break;
            }
            boolean claimedAny = false;
            for (AsyncToolTask candidate : due) {
                afterJobId = candidate.getJobId();
                if (processed >= batchSize) {
                    break;
                }
                // 每行按领取时刻算租约；否则批内越靠后的行有效租约越短，别的实例可以提前接手。
                OffsetDateTime claimedAt = OffsetDateTime.now();
                Optional<AsyncToolTask> claimed = tasks.claimStop(
                        candidate.getJobId(), owner, claimedAt, claimedAt.plusSeconds(leaseSeconds));
                if (claimed.isEmpty()) {
                    continue;
                }
                claimedAny = true;
                AsyncToolTask task = claimed.get();
                try {
                    AgentRun run = ownership.findOwnedRun(task.getRunId());
                    if (run == null) {
                        retry(task, "run_ownership_unavailable");
                    } else {
                        LaneScopeGateway.wrap(run, () -> process(task)).run();
                    }
                } catch (RuntimeException e) {
                    log.warn("外部等待成员停机暂未收尾：job={} reason={}", task.getJobId(), e.getMessage());
                    retry(task, "unexpected_failure");
                }
                processed++;
            }
            if (!claimedAny) {
                break;
            }
        }
        return processed;
    }

    private void process(AsyncToolTask task) {
        String operationId = task.getExternalOperationId();
        if (task.getGroupId() == null || task.getMemberIdentity() == null
                || operationId == null || operationId.isBlank()) {
            abandon(task, "stop_identity_incomplete");
            return;
        }
        // 请求快照为空说明创建请求还没落库，更没有发出去，不存在要停的外部作业：
        // 直接收尾，不调沙箱。快照非空但任务编号为空时仍按操作号发一次取消，
        // 因为创建请求可能已经发出、只是结果没记下来。
        if (task.getRequestJson() == null || task.getRequestJson().isBlank()
                || "{}".equals(task.getRequestJson().trim())) {
            abandon(task, "stop_not_needed_without_request");
            return;
        }
        WaitMember member = groups.findMemberByOperation(task.getRunId(), operationId).orElse(null);
        if (member == null || !task.getJobId().equals(member.getId())
                || !task.getGroupId().equals(member.getGroupId())
                || (member.stateEnum() != WaitMemberState.CANCELED
                    && member.stateEnum() != WaitMemberState.LATE)
                || DurableSandboxTool.fromToolName(member.getToolName()).isEmpty()) {
            abandon(task, "wait_member_identity_mismatch");
            return;
        }
        WaitMemberDispatchProof proof = WaitMemberDispatchProof.fromJson(objectMapper,
                member.getDispatchProofJson()).orElse(null);
        if (proof == null || !operationId.equals(proof.operationId())
                || proof.requestFingerprint() == null || proof.requestFingerprint().isBlank()
                || task.getTaskId() != null && proof.taskId() != null
                   && !task.getTaskId().equals(proof.taskId())) {
            abandon(task, "dispatch_proof_identity_mismatch");
            return;
        }

        WaitMemberTerminalBlock.Saved persistedBlock;
        try {
            persistedBlock = WaitMemberTerminalBlock.read(objectMapper, member);
        } catch (RuntimeException unreadable) {
            // 终态阻塞原文解析不了是永久失败：重试不会变好，收尾并留下原因。
            abandon(task, "terminal_block_unreadable");
            return;
        }
        if (persistedBlock != null) {
            var settledBlock = settlement.settleBlocked(member, proof, persistedBlock.status(),
                    persistedBlock.result(), persistedBlock.finishedAt(), persistedBlock.blocked());
            if (!settledBlock.ok()) {
                retry(task, "sandbox_settlement_incomplete");
                return;
            }
            confirmSettlement(task, persistedBlock.result().getTaskId(), persistedBlock.status());
            return;
        }

        GetTaskByOperationIdResponse lookup = sandbox.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId(operationId).build());
        if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()) {
            retry(task, "operation_lookup_unavailable");
            return;
        }
        String knownTaskId = task.getTaskId() != null ? task.getTaskId() : proof.taskId();
        if (lookup.getFound()) {
            if (lookup.getTaskId().isBlank() || lookup.getRequestFingerprint().isBlank()
                    || !proof.requestFingerprint().equals(lookup.getRequestFingerprint())
                    || knownTaskId != null && !knownTaskId.equals(lookup.getTaskId())) {
                abandon(task, "sandbox_operation_identity_mismatch");
                return;
            }
            knownTaskId = lookup.getTaskId();
        } else if (!lookup.getTaskId().isBlank()) {
            abandon(task, "sandbox_operation_absence_conflict");
            return;
        }

        CancelTaskResponse cancel = sandbox.cancelTask(CancelTaskRequest.newBuilder()
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId(operationId)
                        .setRequestFingerprint(proof.requestFingerprint()))
                .setCancelRequestId("async-task-" + task.getJobId())
                .setReason("RUN_CANCELED")
                .build());
        if (cancel == null || cancel.hasErrorDetail() || !cancel.getError().isBlank()) {
            if (cancel != null && cancel.hasErrorDetail()
                    && (cancel.getErrorDetail().getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT
                    || cancel.getErrorDetail().getCategory()
                    == SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)) {
                abandon(task, "sandbox_cancel_identity_rejected");
            } else {
                retry(task, "sandbox_cancel_unavailable");
            }
            return;
        }
        if (cancel.getOutcome() == CancelOutcome.NOT_FOUND) {
            // 取消返回「查无此作业」后再查一次：防止并发重放恰好在这个窗口把创建请求送进沙箱。
            GetTaskByOperationIdResponse recheck = sandbox.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder().setOperationId(operationId).build());
            if (recheck == null || recheck.hasErrorDetail() || !recheck.getError().isBlank()) {
                retry(task, "operation_recheck_unavailable");
                return;
            }
            if (recheck.getFound() || !recheck.getTaskId().isBlank()) {
                retry(task, "operation_appeared_during_stop");
                return;
            }
            // 沙箱权威无此作业：创建请求从未生效，没有任务可停、也没有终态可结算。
            // 名额按「派发前中止」归还，任务行在归还成功后收尾。
            WaitMemberSettlement.Outcome released = settlement.settleNotStarted(member, proof);
            if (!released.ok()) {
                retry(task, "predispatch_release:" + released.reason());
                return;
            }
            confirmSettlement(task, null, "NOT_STARTED");
            return;
        }
        if (cancel.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED) {
            retry(task, "sandbox_cancel_unspecified");
            return;
        }
        if (cancel.getTaskId().isBlank()) {
            // 取消已生效但这次响应没带任务编号：下一轮按操作号回查就能拿到，不能当身份冲突。
            retry(task, "sandbox_cancel_task_id_missing");
            return;
        }
        if (knownTaskId != null && !knownTaskId.equals(cancel.getTaskId())) {
            abandon(task, "sandbox_cancel_identity_mismatch");
            return;
        }
        String taskId = cancel.getTaskId();
        TaskStatusResponse status = sandbox.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        // 终态 FAILED 的 error 是任务失败原因，不是状态查询失败；传输失败由 errorDetail 表达。
        if (status == null || status.hasErrorDetail()) {
            retry(task, "sandbox_status_unavailable");
            return;
        }
        if (!status.getTaskId().isBlank() && !taskId.equals(status.getTaskId())) {
            abandon(task, "sandbox_status_identity_mismatch");
            return;
        }
        String terminalStatus = status.getStatus();
        if ("QUEUED".equals(terminalStatus) || "RUNNING".equals(terminalStatus)) {
            retry(task, "sandbox_not_terminal");
            return;
        }
        if (!"SUCCEEDED".equals(terminalStatus) && !"FAILED".equals(terminalStatus)
                && !"CANCELED".equals(terminalStatus)) {
            abandon(task, "sandbox_status_unknown");
            return;
        }
        TaskResultResponse result = sandbox.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
        if (result != null && !result.getTaskId().isBlank()
                && !taskId.equals(result.getTaskId())) {
            abandon(task, "sandbox_result_identity_mismatch");
            return;
        }
        result = ToolJobResultValidator.validate(taskId, task.getRunId(), result, terminalStatus);
        if (result == null) {
            retry(task, "sandbox_terminal_result_unavailable");
            return;
        }
        String preview = !result.getStdout().isBlank() ? result.getStdout()
                : !result.getStderr().isBlank() ? result.getStderr() : result.getError();
        var savedBlock = WaitMemberTerminalBlock.read(objectMapper, member);
        WaitMemberSettlement.Outcome settled = savedBlock == null
                ? settlement.settle(member, proof, terminalStatus, result, preview, status.getFinishedAt())
                : settlement.settleBlocked(member, proof, savedBlock.status(), savedBlock.result(),
                        savedBlock.finishedAt(), savedBlock.blocked());
        if (!settled.ok()) {
            retry(task, "sandbox_settlement_incomplete");
            return;
        }
        confirmSettlement(task, taskId, terminalStatus);
    }

    private void confirmSettlement(AsyncToolTask task, String taskId, String terminalStatus) {
        if (!tasks.finishStop(task.getJobId(), owner, task.getClaimEpoch(), taskId)) {
            log.warn("停机收尾未命中，本轮放弃：job={} taskId={} status={}",
                    task.getJobId(), taskId, terminalStatus);
            return;
        }
        // 合同里的停止确认事件：终态证据已经提交成功，这里报一次「外部停止已确认」。
        // 逐次核对的结果不报——那是这条工作循环的热路径，报多了拖执行；关闭时是空操作。
        ObservabilityEvents.externalStopChecked()
                .rootRunId(admissionRegistry.rootRunIdIfAdmitted(task.getRunId())
                        .orElse(task.getRunId()))
                .operationId(task.getExternalOperationId())
                .decision("confirmed")
                .outcome("success")
                .summary("外部停止已确认，终态证据已提交")
                .emit();
    }

    private void retry(AsyncToolTask task, String reason) {
        int attempts = task.getStopAttemptCount() == null ? 0 : task.getStopAttemptCount();
        if (attempts + 1 >= maxAttempts) {
            log.error("外部等待成员停机重试次数用尽，停止自动重试：job={} attempts={} reason={}",
                    task.getJobId(), attempts, reason);
            abandon(task, "stop_attempts_exhausted:" + reason);
            return;
        }
        tasks.retryStop(task.getJobId(), owner, task.getClaimEpoch(),
                OffsetDateTime.now().plusSeconds(retrySeconds), reason);
    }

    /**
     * 停机无需继续：身份对不上、没有外部作业或沙箱明确拒绝。停止状态直接收尾，
     * 原因留在最后错误里，供核对与告警使用；不记成需要重试的失败。
     */
    private void abandon(AsyncToolTask task, String reason) {
        if (!tasks.abandonStop(task.getJobId(), owner, task.getClaimEpoch(), reason)) {
            log.warn("停机收尾未命中，本轮放弃：job={} reason={}", task.getJobId(), reason);
            return;
        }
        log.error("外部等待成员停机身份不一致，已停止自动重试：job={} reason={}",
                task.getJobId(), reason);
    }
}
