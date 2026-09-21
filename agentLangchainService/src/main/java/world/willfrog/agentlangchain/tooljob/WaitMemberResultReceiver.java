package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.RecoveryBackoff;
import world.willfrog.agentlangchain.execution.WaitMemberResultPayload;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 等待成员的结果接收：把已经转后台的 {@code executePython} 的终态接回成员行。
 *
 * <p>成员转后台之后，原来的执行线程已经交还了名额、不再守着这个工具调用，所以结果必须有别人去收。
 * 这一路按成员行的下次查询时间有界地取一批，逐个去问沙箱那个作业的终态：确认终态就把结果写成
 * 成员行的终态（与同步执行时同一份载荷），整组齐备时叫一声恢复分发器，让下一段接着跑。</p>
 *
 * <p>写成员终态之前先把归属核对齐：这条成员属于哪个组、哪个计划代际、哪个节点的哪一段，以及那一段
 * 挂起时的上下文版本与上报时的 Run 控制版本。核对写在 {@code completeMember} 那条语句里，条件不成立
 * 就一行都不写——旧 Worker、作废的计划代际、已经换段的成员都不该把等待链往前推。</p>
 *
 * <p>还没到终态就按退避推后下次查询时间：短任务几秒内就问到了，长任务稀疏下来，不让一批长任务
 * 每一轮都占满查询名额。推后只写成员行自己的时间，不影响别的成员。</p>
 *
 * <p>没落终态的成员不会被这条扫描看见（它只收执行中的），所以这里只做「接结果」这一件事。名额释放与
 * 用量结算跟着成员终态走，不在这一路里做：这条扫描够不到它们，混在一起会让「接结果」这件事被
 * 名额和账目的问题拖住。</p>
 */
@Component
@Slf4j
public class WaitMemberResultReceiver {

    /** 沙箱任务的三个规范终态，加上「结果已永久丢失」这一种：四种都算有结论。 */
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String CANCELED = "CANCELED";
    private static final String RESULT_LOST = "RESULT_LOST";
    /** 按外部作业身份回查、权威地说「这个后台作业不存在」时给成员写的错误码。 */
    private static final String TASK_NOT_FOUND = "wait_member_task_not_found";

    private final WaitGroupStore waitGroupStore;
    private final AgentRunMapper runMapper;
    private final NodeWorkItemStore nodeWorkItemStore;
    private final PythonSandboxService sandboxService;
    private final PythonSandboxTools pythonSandboxTools;
    private final DualPoolRecoveryDispatcher recoveryDispatcher;
    private final ObjectMapper objectMapper;
    private final RecoveryBackoff backoff;
    private final int batchSize;
    private final int maxBackoffStep;
    private final int maxMemberResultChars;
    private final long pollIntervalMs;

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong isolated = new AtomicLong();
    private final AtomicLong wakeups = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public WaitMemberResultReceiver(
            WaitGroupStore waitGroupStore,
            AgentRunMapper runMapper,
            NodeWorkItemStore nodeWorkItemStore,
            PythonSandboxService sandboxService,
            PythonSandboxTools pythonSandboxTools,
            DualPoolRecoveryDispatcher recoveryDispatcher,
            ObjectMapper objectMapper,
            @Value("${agent.langchain.wait-member.receiver.batch-size:8}") int batchSize,
            @Value("${agent.langchain.wait-member.receiver.backoff-base-ms:1000}") long backoffBaseMs,
            @Value("${agent.langchain.wait-member.receiver.backoff-max-ms:15000}") long backoffMaxMs,
            @Value("${agent.langchain.wait-member.receiver.max-backoff-step:6}") int maxBackoffStep,
            @Value("${agent.langchain.dual-pool.wait-group.max-member-result-chars:1048576}")
            int maxMemberResultChars,
            @Value("${agent.langchain.wait-member.receiver.poll-interval-ms:1000}") long pollIntervalMs) {
        this.waitGroupStore = waitGroupStore;
        this.runMapper = runMapper;
        this.nodeWorkItemStore = nodeWorkItemStore;
        this.sandboxService = sandboxService;
        this.pythonSandboxTools = pythonSandboxTools;
        this.recoveryDispatcher = recoveryDispatcher;
        this.objectMapper = objectMapper;
        this.backoff = new RecoveryBackoff(Duration.ofMillis(Math.max(1L, backoffBaseMs)),
                Duration.ofMillis(Math.max(Math.max(1L, backoffBaseMs), backoffMaxMs)));
        this.batchSize = Math.max(1, batchSize);
        this.maxBackoffStep = Math.max(1, maxBackoffStep);
        this.maxMemberResultChars = Math.max(1, maxMemberResultChars);
        this.pollIntervalMs = Math.max(1L, pollIntervalMs);
    }

    /** 周期接结果：按成员行的下次查询时间取一批到点的成员。 */
    @Scheduled(fixedDelayString = "${agent.langchain.wait-member.receiver.poll-interval-ms:1000}")
    public void pollDueMembers() {
        safeRound();
    }

    /** 一轮；出错只记日志，不带走调度线程。返回这一轮处理过的成员数。 */
    public int safeRound() {
        try {
            return round();
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("等待成员结果接收这一轮失败，下一轮接着来: reason={}", e.getMessage(), e);
            return 0;
        }
    }

    /** 取一批到点的成员，逐个接结果。 */
    public int round() {
        rounds.incrementAndGet();
        OffsetDateTime now = OffsetDateTime.now();
        List<WaitMember> due = waitGroupStore.scanDueMembers(now, batchSize);
        scanned.addAndGet(due.size());
        int handled = 0;
        for (WaitMember member : due) {
            try {
                collect(member, now);
                handled++;
            } catch (RuntimeException e) {
                // 一个成员上的意外不带走这一批：推后它、记一笔，别的成员照旧。
                failures.incrementAndGet();
                log.error("接这个成员的结果时出错，推后下一轮再问: group={} member={} reason={}",
                        member.getGroupId(), member.getMemberIdentity(), e.getMessage(), e);
                defer(member, now, "unexpected_error");
            }
        }
        return handled;
    }

    /** 一个成员：核对归属、问一次外部作业、按结论写终态或推后。 */
    private void collect(WaitMember member, OffsetDateTime now) {
        if (member.stateEnum() != WaitMemberState.RUNNING) {
            return;
        }
        Optional<WaitMemberDispatchProof> proofOpt =
                WaitMemberDispatchProof.fromJson(objectMapper, member.getDispatchProofJson());
        if (proofOpt.isEmpty()) {
            // 没有可用证明就不许猜：既不知道问哪个作业，也不许释放名额。
            isolate(member, now, "dispatch_proof_unreadable");
            return;
        }
        WaitMemberDispatchProof proof = proofOpt.get();

        AgentRun run = runMapper.findById(member.getRunId());
        if (run == null) {
            isolate(member, now, "run_missing");
            return;
        }
        if (run.getStatus() != AgentRunStatus.EXECUTING) {
            // Run 不在执行中：取消、暂停、终态都由它们自己的路径收尾，这里不动它。
            isolate(member, now, "run_not_executing:" + run.getStatus());
            return;
        }
        SchedulerVersion version;
        try {
            version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException e) {
            isolate(member, now, "unknown_scheduler_version");
            return;
        }
        if (!version.usesWaitGroups()) {
            isolate(member, now, "version_without_wait_groups");
            return;
        }

        WaitGroup group = waitGroupStore.findGroup(member.getGroupId()).orElse(null);
        if (group == null) {
            isolate(member, now, "group_missing");
            return;
        }
        NodeWorkItem segment = nodeWorkItemStore.findByIdentity(new NodeWorkItemIdentity(
                group.getRunId(), group.getPlanGeneration(), group.getNodeId(),
                group.getNodeAttempt(), group.getSegmentSequence())).orElse(null);
        if (segment == null || segment.getContextVersion() == null) {
            // 找不到这一段（或者它没有上下文版本）就没法核对归属，补齐之前不动成员。
            isolate(member, now, "segment_missing");
            return;
        }

        String taskId = proof.taskId();
        if (taskId == null) {
            TaskLookup lookup = lookupByOperation(proof.operationId());
            if (lookup.taskId() == null) {
                if (lookup.notFound()) {
                    // 权威地说「没建出来」：这次后台作业不存在，成员按失败落终态，
                    // 否则等待链会一直等一个永远不会有的结果。
                    finish(member, group, segment, run, null, TASK_NOT_FOUND, "task_not_found");
                    return;
                }
                defer(member, now, "task_lookup_unavailable");
                return;
            }
            taskId = lookup.taskId();
        }

        TaskStatusResponse status = queryStatus(taskId);
        if (status == null) {
            defer(member, now, "status_unavailable");
            return;
        }
        String statusName = status.getStatus();
        if (!terminal(statusName)) {
            defer(member, now, "still_running:" + statusName);
            return;
        }
        TaskResultResponse result = fetchResult(taskId, member.getRunId(), statusName);
        if (result == null) {
            defer(member, now, "result_unavailable");
            return;
        }
        finish(member, group, segment, run, new Terminal(taskId, statusName, result), null, null);
    }

    /**
     * 写成员终态并把放行交给恢复分发器。
     *
     * <p>{@code terminal} 为空表示「作业本身不存在」这一种结论：这时没有结果体，成员按失败记，
     * 错误码由 {@code failureCode} 给。两种情形用的是同一条写入语句，归属核对也同一份。</p>
     */
    private void finish(WaitMember member,
                        WaitGroup group,
                        NodeWorkItem segment,
                        AgentRun run,
                        Terminal terminal,
                        String failureCode,
                        String reason) {
        String output = terminal == null ? "" : pythonSandboxTools.formatTerminalResult(
                terminal.statusName(), terminal.result());
        // 结果太大时载荷会把它改写成失败：成员行也跟着落失败，两处结论必须一致。
        boolean success = terminal != null && SUCCEEDED.equals(terminal.statusName())
                && terminal.result().getExitCode() == 0
                && !WaitMemberResultPayload.tooLarge(output, maxMemberResultChars);
        Map<String, Object> extra = new LinkedHashMap<>();
        if (terminal != null) {
            extra.put("taskId", terminal.taskId());
        }
        if (!success) {
            extra.put("errorCode", failureCode != null ? failureCode : errorCodeOf(terminal.statusName()));
            if (reason != null) {
                extra.put("errorDetail", reason);
            }
        }
        String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                member.getToolCallId(), success, output, extra, maxMemberResultChars);

        MemberCompletionResult result = waitGroupStore.completeMember(new MemberCompletionRequest(
                member.getGroupId(),
                member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                resultJson,
                member.getExternalOperationId(),
                group.getPlanGeneration(),
                segment.getContextVersion(),
                run.getRunControlVersion()));
        if (!result.applied()) {
            // 已经被别人写过（重复上报、或者这条成员已经落过终态）：不重复放行下一段。
            duplicates.incrementAndGet();
            log.info("这个成员的结果没有写进去（多半已经落过终态）：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity());
            return;
        }
        completed.incrementAndGet();
        log.info("等待成员结果已接回：group={} member={} seq={} state={} 报告={}",
                member.getGroupId(), member.getMemberIdentity(), member.getMemberSeq(),
                result.memberState(), terminal == null ? reason : terminal.describe());
        if (result.groupBecameReady()) {
            // 组齐备了：叫一次恢复分发器，让它尽快把下一段放出去。提醒丢了也不要紧，
            // 它自己的周期补扫与启动扫描会按数据库把这条通知重新发现。
            recoveryDispatcher.wake(result.notificationId());
            wakeups.incrementAndGet();
        }
    }

    private static String errorCodeOf(String statusName) {
        if (statusName == null) {
            return "PYTHON_EXECUTION_FAILED";
        }
        if (CANCELED.equals(statusName)) {
            return "PYTHON_EXECUTION_CANCELED";
        }
        if (RESULT_LOST.equals(statusName)) {
            return "PYTHON_RESULT_LOST";
        }
        return "PYTHON_EXECUTION_FAILED";
    }

    /** 到点的成员还没结论：按退避推后下次查询时间。 */
    private void defer(WaitMember member, OffsetDateTime now, String reason) {
        long memberId = member.getId() == null ? 0L : member.getId();
        OffsetDateTime nextVisibleAt = backoff.nextVisibleAt(now, memberId, member.getCreatedAt());
        boolean pushed = waitGroupStore.rescheduleMember(member.getGroupId(), member.getMemberIdentity(),
                nextVisibleAt, maxBackoffStep);
        deferred.incrementAndGet();
        if (pushed) {
            log.debug("成员结果这一轮还没结论，推后到 {}：group={} member={} reason={}",
                    nextVisibleAt, member.getGroupId(), member.getMemberIdentity(), reason);
        } else {
            log.debug("成员已经不是执行中，推后不生效（多半刚落了终态）：group={} member={} reason={}",
                    member.getGroupId(), member.getMemberIdentity(), reason);
        }
    }

    /** 说不清归属或者拿不到证明：不动它，只留一条能查的记录，并把它推远一点，别每一轮都来问。 */
    private void isolate(WaitMember member, OffsetDateTime now, String reason) {
        isolated.incrementAndGet();
        log.warn("这条等待成员没法核对归属，先不接它的结果：group={} member={} reason={}",
                member.getGroupId(), member.getMemberIdentity(), reason);
        defer(member, now, reason);
    }

    /** 按外部作业身份回查沙箱任务号：三种结论分开，只有权威的「没建出来」才当失败。 */
    private TaskLookup lookupByOperation(String operationId) {
        try {
            GetTaskByOperationIdResponse lookup = sandboxService.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder().setOperationId(operationId).build());
            String taskId = lookup.getTaskId();
            if (taskId != null && !taskId.isBlank()) {
                return new TaskLookup(taskId, false);
            }
            return new TaskLookup(null, true);
        } catch (Exception e) {
            log.warn("按外部作业身份回查沙箱任务暂时不可用：operationId={} reason={}",
                    operationId, e.getMessage());
            return new TaskLookup(null, false);
        }
    }

    private TaskStatusResponse queryStatus(String taskId) {
        try {
            return sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        } catch (Exception e) {
            log.warn("查沙箱任务状态暂时不可用：taskId={} reason={}", taskId, e.getMessage());
            return null;
        }
    }

    private TaskResultResponse fetchResult(String taskId, String runId, String expectedStatus) {
        try {
            // 只在已知终态后拉结果，减少大响应。校验器核对任务号与终态，错配的结果不往成员行里写。
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            return ToolJobResultValidator.validate(taskId, runId, resp, expectedStatus);
        } catch (Exception e) {
            log.warn("拉沙箱任务结果失败：taskId={} reason={}", taskId, e.getMessage());
            return null;
        }
    }

    private static boolean terminal(String statusName) {
        return SUCCEEDED.equals(statusName) || "FAILED".equals(statusName)
                || CANCELED.equals(statusName) || RESULT_LOST.equals(statusName);
    }

    /** 一次已经确认终态的外部作业：任务号、终态名和结果体。 */
    private record Terminal(String taskId, String statusName, TaskResultResponse result) {
        private String describe() {
            return "task=" + taskId + " status=" + statusName;
        }
    }

    /** 按外部作业身份回查的三种结论：taskId、权威「没建出来」、暂时问不到。 */
    private record TaskLookup(String taskId, boolean notFound) {
    }

    /** 结果接收的读数：收了多少轮、问到多少成员、接回多少、推后多少、隔离多少。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("waitMemberReceiverRounds", rounds.get());
        snapshot.put("waitMemberReceiverScannedTotal", scanned.get());
        snapshot.put("waitMemberReceiverCompletedTotal", completed.get());
        snapshot.put("waitMemberReceiverDeferredTotal", deferred.get());
        snapshot.put("waitMemberReceiverDuplicateTotal", duplicates.get());
        snapshot.put("waitMemberReceiverIsolatedTotal", isolated.get());
        snapshot.put("waitMemberReceiverWakeupsTotal", wakeups.get());
        snapshot.put("waitMemberReceiverFailuresTotal", failures.get());
        snapshot.put("waitMemberReceiverBatchSize", batchSize);
        snapshot.put("waitMemberReceiverPollIntervalMs", pollIntervalMs);
        snapshot.put("waitMemberReceiverBackoff", backoff.describe());
        return snapshot;
    }
}
