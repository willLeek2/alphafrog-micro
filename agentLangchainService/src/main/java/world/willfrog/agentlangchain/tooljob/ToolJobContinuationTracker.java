package world.willfrog.agentlangchain.tooljob;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内长工具续接跟踪器（跨进程恢复开关默认关闭时的唯一发现入口）。
 *
 * <p>suspend 成功后按 runId 登记 {taskId, todoId, operationId, timeoutAt}；
 * 低频轮询 Sandbox 任务状态，终态时复用现有的
 * finalizer → resume service → launcher → 有界调度器 整条流程继续原 Run。
 * 用户取消与工具超时都通过 Sandbox cancelTask RPC 传播。服务退出后本表随
 * 进程丢失，不承诺任何崩溃恢复；崩溃后的处理由工作流重启规则负责。</p>
 *
 * <p>本组件只在 {@code agent.tool-job.durable-recovery-enabled=false}（默认）时创建；
 * 开启耐久恢复时由 ToolJobReconciler 承担发现职责，两者不会同时存在。</p>
 */
@Service
@ConditionalOnProperty(
        name = "agent.tool-job.durable-recovery-enabled",
        havingValue = "false",
        matchIfMissing = true)
@Slf4j
public class ToolJobContinuationTracker {

    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String CANCELED = "CANCELED";

    private final ToolJobAnchorService anchorService;
    private final ToolJobFinalizer finalizer;
    private final AgentRunMapper runMapper;
    private final ToolJobConfig config;
    private final LangchainSchedulerMetrics metrics;

    @DubboReference
    private PythonSandboxService sandboxService;

    private final ConcurrentMap<String, ContinuationEntry> entries = new ConcurrentHashMap<>();

    public ToolJobContinuationTracker(ToolJobAnchorService anchorService,
                                      ToolJobFinalizer finalizer,
                                      AgentRunMapper runMapper,
                                      ToolJobConfig config,
                                      LangchainSchedulerMetrics metrics) {
        this.anchorService = anchorService;
        this.finalizer = finalizer;
        this.runMapper = runMapper;
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * suspend 的写库 CAS 成功后登记。anchor 必须已经处于 PENDING。
     * 之后由本 tracker 独占该 Run 的终态发现；登记失败不阻止 suspend 本身。
     */
    public void register(String runId, ToolJobAnchor anchor) {
        if (runId == null || runId.isBlank() || anchor == null) {
            return;
        }
        if (anchor.getTaskId() == null || anchor.getTaskId().isBlank()
                || anchor.getOperationId() == null || anchor.getOperationId().isBlank()) {
            log.warn("Continuation register skipped for run={}: anchor lacks taskId/operationId", runId);
            return;
        }
        ContinuationEntry entry = new ContinuationEntry(
                runId,
                anchor.getTaskId(),
                anchor.getTodoId(),
                anchor.getOperationId(),
                anchor.getTimeoutAt(),
                Instant.now(),
                null,
                0);
        entries.put(runId, entry);
        log.info("Continuation registered: run={} taskId={} todoId={} timeoutAt={}",
                runId, anchor.getTaskId(), anchor.getTodoId(), anchor.getTimeoutAt());
    }

    /** 进程内当前登记的续接数（观测/测试用）。 */
    public int registeredCount() {
        return entries.size();
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.continuation-poll-interval-ms:${agent.tool-job.poll-interval-ms:1000}}")
    public void pollPending() {
        for (ContinuationEntry entry : entries.values()) {
            try {
                processEntry(entry);
            } catch (Exception e) {
                // 单项异常不阻塞其他 Run 的轮询；失败预算耗尽会按 RESULT_LOST 走完终态处理。
                log.error("Continuation poll failed for run={}: {}", entry.runId(), e.getMessage(), e);
            }
        }
    }

    private void processEntry(ContinuationEntry entry) {
        String runId = entry.runId();

        // 每轮都从 DB 读最新 anchor；anchor 已消失或换了 operation，说明终态已由其他路径处理完。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null || !entry.operationId().equals(anchor.getOperationId())) {
            log.info("Continuation anchor gone or replaced for run={}, unregistering", runId);
            // 条件移除：只有当前登记仍是本轮条目时才删，避免误删同一 run 的新登记。
            entries.remove(runId, entry);
            return;
        }
        // resumeState 已推进说明恢复链已在执行（防御：本进程内只会由我们触发）。
        String resumeState = anchor.getResumeState();
        if (resumeState != null && !resumeState.isBlank()) {
            entries.remove(runId, entry);
            return;
        }

        // 取消意图：第一次意图即开始取消等待窗口计时（无论 RPC 成败）；窗口内每轮重试
        // cancel RPC。cancel RPC 失败同样占用轮询失败预算，防止"RPC 永远失败但
        // 状态可读"时超时任务被无限轮询。
        boolean cancelRequested = entry.cancelRequestedAt() != null;
        if (needsCancel(entry, anchor)) {
            boolean rpcOk = sendCancelRpc(runId, entry.taskId());
            if (!cancelRequested && rpcOk) {
                metrics.recordCancelled("running");
            }
            if (!rpcOk) {
                int failures = entry.consecutivePollFailures() + 1;
                if (failures >= config.getContinuationMaxConsecutivePollFailures()) {
                    log.error("Continuation cancel RPC budget exhausted for run={}, "
                            + "finalizing as RESULT_LOST", runId);
                    finalizeResultLost(runId, entry, anchor);
                    return;
                }
                entry = entry.withPollFailures(failures);
            }
            if (!cancelRequested) {
                entry = entry.withCancelRequestedAt(Instant.now());
            }
            entries.put(runId, entry);
            cancelRequested = true;
        }

        // 轮询 Sandbox 状态；RPC 连续失败计入预算，超预算按 RESULT_LOST 走完终态处理。
        TaskStatusResponse statusResp;
        try {
            statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(entry.taskId()).build());
        } catch (Exception rpcFailure) {
            int failures = entry.consecutivePollFailures() + 1;
            if (failures >= config.getContinuationMaxConsecutivePollFailures()) {
                log.error("Continuation poll RPC budget exhausted for run={}, "
                        + "finalizing as RESULT_LOST", runId);
                finalizeResultLost(runId, entry, anchor);
                return;
            }
            entries.put(runId, entry.withPollFailures(failures));
            return;
        }

        String status = statusResp.getStatus();
        if (!isTerminal(status)) {
            // 已请求取消但迟迟看不到终态：给 cancel 一个等待窗口，超窗按 RESULT_LOST 走完终态处理。
            if (cancelRequested && entry.cancelRequestedAt() != null
                    && Instant.now().isAfter(entry.cancelRequestedAt()
                    .plusSeconds(config.getTerminalRetentionSeconds()))) {
                log.warn("Continuation cancel window expired for run={}, finalizing as RESULT_LOST", runId);
                finalizeResultLost(runId, entry, anchor);
            }
            return;
        }

        // 终态：拉取并校验结果，然后交给 finalizer 走统一的终态处理流程。
        TaskResultResponse resultResp = fetchResult(entry.taskId(), runId, status);
        if (resultResp == null) {
            // 结果体暂时不可用：下轮重试，轮询失败预算同样约束这里。
            int failures = entry.consecutivePollFailures() + 1;
            if (failures >= config.getContinuationMaxConsecutivePollFailures()) {
                log.error("Continuation result fetch budget exhausted for run={}, finalizing as RESULT_LOST", runId);
                finalizeResultLost(runId, entry, anchor);
                return;
            }
            entries.put(runId, entry.withPollFailures(failures));
            return;
        }

        log.info("Continuation terminal for run={} status={}, handing to finalizer", runId, status);
        // finalizer 完成 ENVELOPE/RELEASE/USAGE/EVENT/CAS_STATUS，并在 autoResume
        // 时通过 completeResumeReady → resumeService.tryResume 触发续接入队。
        // 只有 finalizer 明确做完（done）才移除登记；没做完（带步骤与原因）或抛异常
        // 都保留登记下一轮重试，最终只形成一个终态结果由 finalizer 的可重入六步保证。
        try {
            ToolJobFinalizer.FinalizerOutcome outcome =
                    finalizer.handleTerminal(runId, anchor, status, resultResp, anchor.isAutoResume());
            if (!outcome.done()) {
                log.warn("Continuation finalizer incomplete for run={} status={} step={} reason={}; "
                        + "keeping registration for retry", runId, status, outcome.step(), outcome.reason());
                return;
            }
        } catch (Exception finalizeFailure) {
            log.error("Continuation finalizer failed for run={} status={}; "
                    + "keeping registration for retry", runId, status, finalizeFailure);
            return;
        }
        entries.remove(runId, entry);

        // 观测：确认恢复链确实推进（LAUNCHING/ACCEPTED/CONSUMED）或 Run 已终态。
        ToolJobAnchor after = anchorService.loadAnchor(runId);
        if (after != null) {
            String afterState = after.getResumeState();
            if ("LAUNCHING".equals(afterState) || "ACCEPTED".equals(afterState)
                    || "CONSUMED".equals(afterState)) {
                metrics.recordContinuationRequeued();
            }
        }
    }

    /** RESULT_LOST 统一终态处理：finalizer 明确做完才移除登记，没做完或异常保留下一轮重试。 */
    private void finalizeResultLost(String runId, ContinuationEntry entry, ToolJobAnchor anchor) {
        try {
            ToolJobFinalizer.FinalizerOutcome outcome =
                    finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
            if (!outcome.done()) {
                log.warn("Continuation RESULT_LOST finalizer incomplete for run={} step={} reason={}; "
                        + "keeping registration for retry", runId, outcome.step(), outcome.reason());
                return;
            }
        } catch (Exception finalizeFailure) {
            log.error("Continuation RESULT_LOST finalizer failed for run={}; "
                    + "keeping registration for retry", runId, finalizeFailure);
            return;
        }
        entries.remove(runId, entry);
    }

    private boolean needsCancel(ContinuationEntry entry, ToolJobAnchor anchor) {
        // 用户取消：cancelRun 已把 disposition 持久化到 anchor。
        if ("CANCELED".equals(anchor.getRunDisposition())) {
            return true;
        }
        // 工具超时：anchor 冻结的 timeoutAt 已过期。
        if (anchor.getTimeoutAt() != null && Instant.now().isAfter(anchor.getTimeoutAt())) {
            return true;
        }
        // 防御：Run 在数据库层面已进入取消终态路径。
        AgentRun run = runMapper.findById(entry.runId());
        return run != null && (run.getStatus() == AgentRunStatus.CANCELING
                || run.getStatus() == AgentRunStatus.CANCELED);
    }

    /** 发送取消 RPC；成功返回 true。计时、预算与指标由调用方推进。 */
    private boolean sendCancelRpc(String runId, String taskId) {
        try {
            sandboxService.cancelTask(CancelTaskRequest.newBuilder()
                    .setByTaskId(TaskIdCancelTarget.newBuilder().setTaskId(taskId).build())
                    .setCancelRequestId("continuation-" + runId + "-" + taskId)
                    .setReason("agent continuation timeout/cancel")
                    .build());
            log.info("Continuation cancel requested for run={} taskId={}", runId, taskId);
            return true;
        } catch (Exception e) {
            log.warn("Continuation cancelTask failed for run={} taskId={}: {}",
                    runId, taskId, e.getMessage());
            return false;
        }
    }

    private TaskResultResponse fetchResult(String taskId, String runId, String expectedStatus) {
        try {
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            return ToolJobResultValidator.validate(taskId, runId, resp, expectedStatus);
        } catch (Exception e) {
            log.error("Continuation result fetch failed for taskId={} run={}: {}",
                    taskId, runId, e.getMessage());
            return null;
        }
    }

    private static boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status);
    }

    /**
     * 进程内续接登记项。字段在 suspend 时从数据库里的 anchor 记录复制而来；
     * cancelRequestedAt 与 pollFailures 由 tracker 在轮询中推进。
     */
    record ContinuationEntry(
            String runId,
            String taskId,
            String todoId,
            String operationId,
            Instant timeoutAt,
            Instant registeredAt,
            Instant cancelRequestedAt,
            int consecutivePollFailures) {

        ContinuationEntry withCancelRequestedAt(Instant at) {
            return new ContinuationEntry(runId, taskId, todoId, operationId,
                    timeoutAt, registeredAt, at, consecutivePollFailures);
        }

        ContinuationEntry withPollFailures(int failures) {
            return new ContinuationEntry(runId, taskId, todoId, operationId,
                    timeoutAt, registeredAt, cancelRequestedAt, failures);
        }
    }
}
