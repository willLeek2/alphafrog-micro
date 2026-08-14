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
 * 进程内长工具续接跟踪器（durable-recovery 默认关闭时的唯一发现入口）。
 *
 * <p>suspend 成功后按 runId 登记 {taskId, todoId, operationId, timeoutAt}；
 * 低频轮询 Sandbox 任务状态，终态时复用现有的
 * finalizer → resume service → launcher → 有界调度器 整条链继续原 Run。
 * 用户取消与工具超时都通过 Sandbox cancelTask RPC 传播。服务退出后本表随
 * 进程丢失，不承诺任何崩溃恢复；崩溃后的处理由工作流重启规则（task #118）负责。</p>
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
     * suspend 的 durable CAS 成功后登记。anchor 必须已经处于 PENDING。
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
                // 单项异常不阻塞其他 Run 的轮询；fail 预算耗尽会走 RESULT_LOST 收口。
                log.error("Continuation poll failed for run={}: {}", entry.runId(), e.getMessage(), e);
            }
        }
    }

    private void processEntry(ContinuationEntry entry) {
        String runId = entry.runId();

        // 每轮都从 DB 读最新 anchor；anchor 已消失或换了 operation，说明被其他路径收口。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null || !entry.operationId().equals(anchor.getOperationId())) {
            log.info("Continuation anchor gone or replaced for run={}, unregistering", runId);
            entries.remove(runId);
            return;
        }
        // resumeState 已推进说明恢复链已在执行（防御：本进程内只会由我们触发）。
        String resumeState = anchor.getResumeState();
        if (resumeState != null && !resumeState.isBlank()) {
            entries.remove(runId);
            return;
        }

        // 取消信号：用户取消已在 anchor 上持久化 CANCELED disposition（autoResume=false）。
        boolean cancelRequested = entry.cancelRequestedAt() != null;
        if (!cancelRequested && needsCancel(entry, anchor)) {
            requestCancel(entry);
            cancelRequested = true;
        }

        // 轮询 Sandbox 状态；RPC 连续失败计入预算，超预算按 RESULT_LOST 收口。
        TaskStatusResponse statusResp;
        try {
            statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(entry.taskId()).build());
        } catch (Exception rpcFailure) {
            int failures = entry.consecutivePollFailures() + 1;
            if (failures >= config.getContinuationMaxConsecutivePollFailures()) {
                log.error("Continuation poll RPC budget exhausted for run={}, "
                        + "finalizing as RESULT_LOST", runId);
                entries.remove(runId);
                finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                return;
            }
            entries.put(runId, entry.withPollFailures(failures));
            return;
        }

        String status = statusResp.getStatus();
        if (!isTerminal(status)) {
            // 已请求取消但迟迟看不到终态：给 cancel 一个收口窗口，超窗按 RESULT_LOST。
            if (cancelRequested && entry.cancelRequestedAt() != null
                    && Instant.now().isAfter(entry.cancelRequestedAt()
                    .plusSeconds(config.getTerminalRetentionSeconds()))) {
                log.warn("Continuation cancel window expired for run={}, finalizing as RESULT_LOST", runId);
                entries.remove(runId);
                finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
            }
            return;
        }

        // 终态：拉取并校验结果，然后交给 finalizer 走统一的收口链。
        TaskResultResponse resultResp = fetchResult(entry.taskId(), runId, status);
        if (resultResp == null) {
            // 结果体暂时不可用：下轮重试，轮询失败预算同样约束这里。
            int failures = entry.consecutivePollFailures() + 1;
            if (failures >= config.getContinuationMaxConsecutivePollFailures()) {
                log.error("Continuation result fetch budget exhausted for run={}, finalizing as RESULT_LOST", runId);
                entries.remove(runId);
                finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                return;
            }
            entries.put(runId, entry.withPollFailures(failures));
            return;
        }

        entries.remove(runId);
        log.info("Continuation terminal for run={} status={}, handing to finalizer", runId, status);
        // finalizer 完成 ENVELOPE/RELEASE/USAGE/EVENT/CAS_STATUS，并在 autoResume
        // 时通过 completeResumeReady → resumeService.tryResume 触发续接入队。
        finalizer.handleTerminal(runId, anchor, status, resultResp, anchor.isAutoResume());

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

    private void requestCancel(ContinuationEntry entry) {
        try {
            sandboxService.cancelTask(CancelTaskRequest.newBuilder()
                    .setByTaskId(TaskIdCancelTarget.newBuilder().setTaskId(entry.taskId()).build())
                    .setCancelRequestId("continuation-" + entry.runId() + "-" + entry.taskId())
                    .setReason("agent continuation timeout/cancel")
                    .build());
            entries.put(entry.runId(), entry.withCancelRequestedAt(Instant.now()));
            metrics.recordCancelled("running");
            log.info("Continuation cancel requested for run={} taskId={}",
                    entry.runId(), entry.taskId());
        } catch (Exception e) {
            // cancel RPC 失败不致命：下一轮继续尝试；结果拉取失败预算最终会 RESULT_LOST。
            log.warn("Continuation cancelTask failed for run={} taskId={}: {}",
                    entry.runId(), entry.taskId(), e.getMessage());
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
     * 进程内续接登记项。字段在 suspend 时从 durable anchor 快照而来；
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
