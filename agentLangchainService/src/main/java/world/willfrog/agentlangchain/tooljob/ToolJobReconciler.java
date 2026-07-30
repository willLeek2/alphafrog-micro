package world.willfrog.agentlangchain.tooljob;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 后台工具任务的周期协调器。
 *
 * <p>Redis due 集合负责低延迟轮询，PostgreSQL anchor 周期补扫负责灾后恢复。
 * 本类只发现状态并调用 finalizer/resume service；所有持久化所有权仍由数据库 CAS 决定。</p>
 */
@Service
public class ToolJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(ToolJobReconciler.class);
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String CANCELED = "CANCELED";
    private static final String NOT_FOUND = "NOT_FOUND";

    private final ToolJobRedisCache redisCache;
    private final ToolJobAnchorService anchorService;
    private final ToolJobFinalizer finalizer;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;
    private final DataAnalysisCapacityService capacityService;
    private final ToolJobPreparingAbortRecoveryService preparingAbortRecovery =
            new ToolJobPreparingAbortRecoveryService();

    @Autowired(required = false)
    private ToolJobCheckpointFailureRecoveryService checkpointFailureRecoveryService;

    @DubboReference
    private PythonSandboxService sandboxService;

    public ToolJobReconciler(ToolJobRedisCache redisCache, ToolJobAnchorService anchorService,
                             ToolJobFinalizer finalizer, ToolJobResumeService resumeService,
                             ToolJobConfig config) {
        this(redisCache, anchorService, finalizer, resumeService, config, null);
    }

    @Autowired
    public ToolJobReconciler(ToolJobRedisCache redisCache, ToolJobAnchorService anchorService,
                             ToolJobFinalizer finalizer, ToolJobResumeService resumeService,
                             ToolJobConfig config,
                             DataAnalysisCapacityService capacityService) {
        this.redisCache = redisCache;
        this.anchorService = anchorService;
        this.finalizer = finalizer;
        this.resumeService = resumeService;
        this.config = config;
        this.capacityService = capacityService;
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.reconciler-interval-ms:5000}")
    public void reconcileFromDue() {
        try {
            // 每轮最多取 20 个到期 Run，限制单次调度耗时和 Sandbox 压力。
            Set<String> due = redisCache.fetchDue(20);
            // 每个 runId 独立处理；单项异常由 processItem 捕获，不阻塞其他 Run。
            for (String runId : due) processItem(runId);
        } catch (Exception e) {
            log.error("Reconciler due-cycle error", e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.rebuild-interval-ms:60000}")
    public void rebuildFromAnchors() {
        try {
            // 第一段从 PostgreSQL 真相源重建 pending cache 与 due 索引。
            for (AgentRun run : anchorService.listActive(100)) {
                // 再按 id 读取最新 anchor，避免列表查询后的状态漂移。
                ToolJobAnchor a = anchorService.loadAnchor(run.getId());
                if (a == null) continue;
                if (ToolJobRunDisposition.isDagPreparingAbort(
                        a.getRunDisposition())) {
                    processItem(run.getId());
                    continue;
                }
                if (run.getStatus() == AgentRunStatus.EXECUTING
                        && ToolJobRunDisposition.isLiveDagBlocking(a.getRunDisposition())) {
                    // 未来租约只重建到期唤醒；过期租约通过 owner/opId CAS 接管。
                    if (recoverLiveDagBlocking(run.getId(), a)) {
                        processItem(run.getId());
                    }
                    continue;
                }
                // Redis 丢失或重启后可由完整 anchor 重建，不承担真相源职责。
                redisCache.atomicWritePendingAndDue(run.getId(), a);
                // 已到期任务立即处理，不等待下一轮 due 定时器。
                if (a.getNextPollAt() != null && !a.getNextPollAt().isAfter(Instant.now()))
                    processItem(run.getId());
            }
        } catch (Exception e) { log.error("Reconciler rebuild error", e); }
        try {
            // 第二段专扫 READY/LAUNCHING，覆盖 finalizer 写 READY 后进程崩溃的窗口。
            for (AgentRun run : anchorService.listResumeReady(50)) {
                ToolJobAnchor a = anchorService.loadAnchor(run.getId());
                // 先补热副本，再由 ResumeService 执行 token/lease CAS claim。
                if (a != null) { redisCache.atomicWritePendingAndDue(run.getId(), a); resumeService.tryResume(run.getId()); }
            }
        } catch (Exception e) { log.error("Resume-ready scan error", e); }
    }

    private void processItem(String runId) {
        try {
            // checkpoint 写失败 marker 优先处理；未解决前不能继续轮询并恢复一个缺上下文的 Run。
            if (checkpointFailureRecoveryService != null
                    && !checkpointFailureRecoveryService.retryPending(runId)) {
                log.warn("Checkpoint-failure retry remains pending run={}", runId);
                return;
            }
            // 每轮都从 DB 读取最新 anchor，不能信任 due 成员对应的旧 Redis JSON。
            ToolJobAnchor anchor = anchorService.loadAnchor(runId);
            // DB 已无 active anchor 时清理 Redis 残留，幂等结束。
            if (anchor == null) { redisCache.removeDue(runId); redisCache.deletePendingCache(runId); return; }
            if (ToolJobRunDisposition.isDagPreparingAbort(
                    anchor.getRunDisposition())) {
                recoverPreparingAbort(runId, anchor);
                return;
            }
            if (ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
                // live worker 的 lease 未到期时只重排 expiry；过期后必须先赢 fenced CAS。
                if (!recoverLiveDagBlocking(runId, anchor)) return;
            }
            // taskId 是查询 Sandbox 的真实任务主键。
            String taskId = anchor.getTaskId();
            if (taskId == null || taskId.isBlank()) {
                if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                    if ("PREPARING".equals(anchor.getAnchorState())) {
                        resolveCleanupPreparing(runId, anchor);
                    } else {
                        log.error("DAG cleanup anchor lacks taskId outside PREPARING for run={}; "
                                + "removing hot-loop due", runId);
                        redisCache.removeDue(runId);
                    }
                }
                return;
            }

            if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                checkPausedTerminal(runId, anchor);
                return;
            }
            // 暂停/取消/checkpoint 失败仍需确认终态并释放容量，但禁止自动恢复。
            if (!anchor.isAutoResume()) { checkPausedTerminal(runId, anchor); return; }

            // 先取轻量状态，只有确认终态后才拉取可能较大的结果体。
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            // 三个规范终态进入结果拉取与 finalizer 链路。
            if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                // fetchResult 还会核对 taskId/runId/expectedStatus，拒绝错配响应。
                TaskResultResponse resultResp = fetchResult(taskId, runId, status);
                if (resultResp == null) {
                    // 状态已终态但结果体暂不可用，进入有界 RESULT_FETCH_PENDING。
                    Instant now = Instant.now();
                    if (anchor.getTerminalConfirmedAt() != null) {
                        // 从首次终态确认时间累计期限，不因进程重启重置窗口。
                        long elapsed = Duration.between(anchor.getTerminalConfirmedAt(), now).toSeconds();
                        int attempts = anchor.getResultFetchAttempts() + 1;
                        anchor.setResultFetchAttempts(attempts);
                        if (elapsed > config.getResultRetentionDeadlineSeconds()
                                || attempts >= config.getResultFetchMaxAttempts()) {
                            // 达到期限/次数后冻结 RESULT_LOST，仍由 finalizer 释放容量并恢复为失败。
                            anchor.setResultFetchState("LOST");
                            anchor.setTerminalStatus("RESULT_LOST");
                            anchor.setTerminalAt(now);
                            log.error("Result permanently lost for run={}, taskId={}", runId, taskId);
                            finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                            return;
                        }
                    } else {
                        // 第一次缺结果时持久化起点和 attempt=1。
                        anchor.setResultFetchState("PENDING");
                        anchor.setTerminalConfirmedAt(now);
                        anchor.setResultFetchAttempts(1);
                    }
                    // 计算下一次轮询时间并先 CAS 写 DB。
                    anchor.setNextPollAt(now.plusMillis(config.getPollIntervalMs()));
                    boolean updated = anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                    if (!updated) {
                        // 所有权已变化时删除本轮 due，让下一周期从 PG 重建新状态。
                        log.warn("Retry-state CAS failed for run={}, will reload from PG next cycle", runId);
                        redisCache.removeDue(runId);
                        return;
                    }
                    // DB 成功后再更新 Redis 索引与热副本。
                    redisCache.upsertDue(runId, anchor);
                    redisCache.writePendingCache(runId, anchor);
                    return;
                }
                // 结果体完整时进入可重入六步 finalizer，最终生成 READY 并重新入队。
                finalizer.handleTerminal(runId, anchor, status, resultResp, true);
            } else if (NOT_FOUND.equals(status)) {
                // NOT_FOUND 可能是传播延迟或结果保留过期，交给有界丢失判定。
                finalizer.handleNotFound(runId, anchor);
            } else {
                // 非终态只推进 nextPollAt，不占用任何 Agent worker 等待。
                anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                redisCache.upsertDue(runId, anchor);
                redisCache.writePendingCache(runId, anchor);
            }
        } catch (Exception e) {
            log.error("Reconciler error run={}", runId, e);
        }
    }

    private TaskResultResponse fetchResult(String taskId, String runId, String expectedStatus) {
        try {
            // 只在已知终态后请求结果，减少大响应和无效轮询。
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            // validator 对任务身份与终态做 fail-closed 校验，错结果不会注入另一个 Run。
            return ToolJobResultValidator.validate(taskId, runId, resp, expectedStatus);
        } catch (Exception e) {
            log.error("Failed to fetch result for taskId={}, run={}", taskId, runId, e);
            return null;
        }
    }

    private void resolveCleanupPreparing(String runId, ToolJobAnchor anchor) {
        DataAnalysisReservation preparing;
        try {
            preparing = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .readValue(anchor.getReservationJson(), DataAnalysisReservation.class);
        } catch (Exception invalidReservation) {
            log.error("DAG cleanup PREPARING reservation is invalid for run={}; "
                    + "refusing online replay", runId, invalidReservation);
            redisCache.removeDue(runId);
            return;
        }
        if (preparing == null
                || preparing.state() != DataAnalysisReservationState.PREPARING) {
            log.error("DAG cleanup anchor/reservation state mismatch for run={}; "
                    + "refusing online replay", runId);
            redisCache.removeDue(runId);
            return;
        }

        /*
         * ATTACHED CAS 一并保存下一次轮询时间。在线恢复只走 cleanup fence，
         * 不得把 Run 转 WAITING_TOOL_JOB 或触发 resume launcher。
         */
        anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
        ToolJobPreparingDispatchResolver.Resolution resolution =
                ToolJobPreparingDispatchResolver.resolve(
                        runId, anchor, preparing, sandboxService, anchorService);
        if (resolution.outcome() == ToolJobPreparingDispatchResolver.Outcome.RESOLVED) {
            redisCache.atomicWritePendingAndDue(runId, anchor);
            return;
        }
        if (resolution.outcome()
                == ToolJobPreparingDispatchResolver.Outcome.INVALID_EVIDENCE) {
            log.error("DAG cleanup PREPARING evidence is invalid for run={}; "
                    + "removing hot-loop due", runId);
            redisCache.removeDue(runId);
            return;
        }
        if (resolution.outcome()
                == ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE) {
            try {
                if (anchorService.updateDagCleanupPreparing(
                        runId,
                        anchor,
                        anchor.getOperationId(),
                        anchor.getBlockingOwnerId(),
                        anchor.getRequestFingerprint())) {
                    redisCache.atomicWritePendingAndDue(runId, anchor);
                    return;
                }
            } catch (Exception persistenceFailure) {
                log.warn("Failed to persist DAG PREPARING online retry for run={}",
                        runId, persistenceFailure);
            }
        }
        /*
         * 远端/DB 暂不可用或 CAS 已由另一恢复者推进时，只补 runId due。
         * 下一轮仍从 PG 读取最新 anchor，不信任当前对象。
         */
        redisCache.upsertDue(runId, anchor);
    }

    private void checkPausedTerminal(String runId, ToolJobAnchor anchor) {
        try {
            // paused/canceled 仍轮询真实 Sandbox 任务，以便及时释放资源。
            String taskId = anchor.getTaskId();
            if (taskId == null) return;
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();
            if (NOT_FOUND.equals(status)) {
                // 仍使用同一结果丢失期限，不因 autoResume=false 跳过资源收尾。
                finalizer.handleNotFound(runId, anchor);
            } else if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                TaskResultResponse resultResp = fetchResult(taskId, runId, status);
                if (resultResp != null) {
                    // 只执行 envelope/release/usage/event，不 CAS RECEIVED、不生成 READY。
                    finalizer.handleTerminal(runId, anchor, status, resultResp, false);
                } else {
                    // 终态已确认但结果体不可用时也要有界重试，不能永久占用 DAG/暂停容量。
                    finalizer.handleNotFound(runId, anchor);
                }
            } else if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                // cleanup-only 可跨 EXECUTING/FAILED/CANCELED 持续收尾。
                anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
                if (anchorService.updateDagCleanup(
                        runId, anchor, anchor.getOperationId(), anchor.getBlockingOwnerId())) {
                    redisCache.upsertDue(runId, anchor);
                    redisCache.writePendingCache(runId, anchor);
                }
            }
        } catch (Exception e) {
            log.error("Paused-terminal check error run={}", runId, e);
        }
    }

    private boolean recoverLiveDagBlocking(String runId, ToolJobAnchor anchor) {
        String operationId = anchor.getOperationId();
        String ownerId = anchor.getBlockingOwnerId();
        if (operationId == null || operationId.isBlank()
                || ownerId == null || ownerId.isBlank()) {
            log.error("DAG blocking anchor lacks fenced identity for run={}; refusing takeover", runId);
            redisCache.removeDue(runId);
            return false;
        }
        Instant now = Instant.now();
        if (!DagBlockingWorkerLease.isExpired(anchor.getBlockingLeaseUntil(), now)) {
            // 不写 PostgreSQL anchor；当前 live worker 仍拥有 durable lease。
            anchor.setNextPollAt(anchor.getBlockingLeaseUntil());
            redisCache.atomicWritePendingAndDue(runId, anchor);
            return false;
        }

        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setAutoResume(false);
        anchor.setFinalizerError(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setNextPollAt(now);
        boolean promoted = anchorService.promoteExpiredDagBlockingWorkerLost(
                runId, anchor, operationId, ownerId);
        if (!promoted) {
            // 其他 worker/恢复者已续租或接管；丢弃本轮旧 due，等待 PG 重建。
            redisCache.removeDue(runId);
            return false;
        }
        redisCache.atomicWritePendingAndDue(runId, anchor);
        log.warn("Reconciler claimed expired DAG blocking worker for run={}, operationId={}, owner={}",
                runId, operationId, ownerId);
        return true;
    }

    private void recoverPreparingAbort(String runId, ToolJobAnchor anchor) {
        ToolJobPreparingAbortRecoveryService.Outcome outcome =
                preparingAbortRecovery.recover(
                        runId,
                        anchor,
                        capacityService,
                        anchorService,
                        redisCache);
        if (outcome == ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED) {
            return;
        }
        if (outcome == ToolJobPreparingAbortRecoveryService.Outcome.CLEAR_PENDING
                || outcome == ToolJobPreparingAbortRecoveryService.Outcome.RETRYABLE) {
            return;
        }
        if (outcome == ToolJobPreparingAbortRecoveryService.Outcome.OWNERSHIP_LOST) {
            log.info("DAG PREPARING abort lost ownership to a new anchor for run={}; "
                    + "leaving winner Redis indexes untouched", runId);
            return;
        }
        log.error("DAG PREPARING abort retained for run={}, outcome={}; "
                        + "no Sandbox lookup or workflow resume is allowed",
                runId, outcome);
    }
}
