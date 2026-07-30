package world.willfrog.agentlangchain.tooljob;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务启动后的上下文切换灾后恢复入口。
 *
 * <p>先从 durable reservation 重建容量账本，再重建 Redis due/cache，最后恢复
 * READY/LAUNCHING handoff。顺序不可交换：容量状态未恢复前开放准入可能超卖。</p>
 */
@Service
public class ToolJobStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ToolJobStartupRecovery.class);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final DataAnalysisCapacityService capacityService;
    private final DataAnalysisCapacityProperties capacityProperties;
    private final ToolJobFinalizer finalizer;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;
    private final ToolJobPreparingAbortRecoveryService preparingAbortRecovery =
            new ToolJobPreparingAbortRecoveryService();

    @DubboReference
    private PythonSandboxService sandboxService;

    public ToolJobStartupRecovery(ToolJobAnchorService anchorService,
                                  ToolJobRedisCache redisCache,
                                  DataAnalysisCapacityService capacityService,
                                  DataAnalysisCapacityProperties capacityProperties,
                                  ToolJobFinalizer finalizer,
                                  ToolJobResumeService resumeService,
                                  ToolJobConfig config) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.capacityService = capacityService;
        this.capacityProperties = capacityProperties;
        this.finalizer = finalizer;
        this.resumeService = resumeService;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 等 Spring/Dubbo bean 全部 ready 后再访问数据库和 Sandbox。
        log.info("T3 startup recovery beginning");
        try {
            // 第一阶段恢复容量真相，决定 admission 能否从 RECOVERING 转 OPEN。
            recoverCapacityLedger();
            // 第二阶段恢复轮询索引、finalizer 进度和恢复 launcher。
            recoverToolJobAnchors();
            log.info("T3 startup recovery complete");
        } catch (Exception e) {
            log.error("T3 startup recovery failed", e);
        }
    }

    private void recoverCapacityLedger() {
        // 只扫描仍有 active anchor 的 Run，终态已清理 anchor 不再占用容量。
        List<AgentRun> activeRuns = anchorService.listActive(200);
        // durableReservations 会一次性提交给容量服务重建。
        List<DataAnalysisReservation> durableReservations = new ArrayList<>();
        // 无法解析/确认的 reservation 必须隔离并阻止 admission 开放。
        List<String> quarantinedRuns = new ArrayList<>();

        for (AgentRun run : activeRuns) {
            // 列表查询后重新读取最新 anchor。
            ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
            if (anchor == null || anchor.getReservationJson() == null) continue;
            try {
                // 注册 Java Time 模块以还原 acquiredAt 等时间字段。
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
                DataAnalysisReservation reservation = mapper.readValue(
                        anchor.getReservationJson(), DataAnalysisReservation.class);
                if (ToolJobRunDisposition.isDagPreparingAbort(
                        anchor.getRunDisposition())) {
                    ToolJobPreparingAbortRecoveryService.Outcome outcome =
                            preparingAbortRecovery.recover(
                                    run.getId(),
                                    anchor,
                                    capacityService,
                                    anchorService,
                                    redisCache);
                    if (outcome
                            == ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED) {
                        continue;
                    }
                    if (outcome
                            == ToolJobPreparingAbortRecoveryService.Outcome.CLEAR_PENDING) {
                        continue;
                    }
                    if (outcome
                            == ToolJobPreparingAbortRecoveryService.Outcome.OWNERSHIP_LOST) {
                        /*
                         * 另一 operation 已经成为 PG winner。不能删除或用 stale abort
                         * 重排它的 Redis 索引；容量恢复保持 fail-closed，等待下一轮新快照。
                         */
                        quarantinedRuns.add(run.getId());
                        continue;
                    }
                    quarantinedRuns.add(run.getId());
                    continue;
                }
                // PREPARING 表示进程可能在 createTask 前后崩溃，需要按 operationId 查找/重放。
                if (reservation != null
                        && reservation.state() == DataAnalysisReservationState.PREPARING) {
                    if (ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
                        /*
                         * DAG worker may still be inside createTask. A new process must first
                         * respect its durable lease instead of treating PREPARING as abandoned.
                         */
                        if (!hasDagBlockingIdentity(anchor)) {
                            quarantinedRuns.add(run.getId());
                            continue;
                        }
                        Instant recoveryNow = Instant.now();
                        boolean leaseExpired = DagBlockingWorkerLease.isExpired(
                                anchor.getBlockingLeaseUntil(), recoveryNow);
                        if (!leaseExpired) {
                            // Future lease: restore capacity and only rebuild the expiry wake-up.
                            try {
                                recoverLiveDagBlocking(run.getId(), anchor, recoveryNow);
                            } catch (Exception scheduleFailure) {
                                /*
                                 * Redis is only a wake-up index. Its outage must not turn a
                                 * proven durable reservation into a capacity quarantine.
                                 */
                                log.error("Failed to schedule live DAG lease expiry for run={}; "
                                                + "restoring durable PREPARING capacity",
                                        run.getId(), scheduleFailure);
                            }
                            durableReservations.add(reservation);
                            continue;
                        }
                        if (!recoverLiveDagBlocking(run.getId(), anchor, recoveryNow)) {
                            /*
                             * Another process won the takeover CAS. Keep the stale durable
                             * reservation counted locally; the winner owns all further writes.
                             */
                            durableReservations.add(reservation);
                            continue;
                        }
                    }
                    ToolJobPreparingDispatchResolver.Resolution resolution =
                            resolvePreparingDispatch(run, anchor, reservation);
                    if (resolution.outcome()
                            == ToolJobPreparingDispatchResolver.Outcome.RESOLVED) {
                        reservation = resolution.reservation();
                    } else if (ToolJobRunDisposition.isDagCleanupOnly(
                            anchor.getRunDisposition())
                            && resolution.outcome()
                            == ToolJobPreparingDispatchResolver.Outcome.REMOTE_UNAVAILABLE) {
                        /*
                         * takeover 已完成但远端状态暂不可决时，原 PREPARING 仍是容量真相。
                         * 继续计账并重建在线重试 due，不能因一次 RPC 故障超卖。
                         */
                        durableReservations.add(reservation);
                        scheduleDagPreparingRetry(run.getId(), anchor);
                        continue;
                    } else if (ToolJobRunDisposition.isDagCleanupOnly(
                            anchor.getRunDisposition())
                            && (resolution.outcome()
                            == ToolJobPreparingDispatchResolver.Outcome.OWNERSHIP_LOST
                            || resolution.outcome()
                            == ToolJobPreparingDispatchResolver.Outcome.DURABLE_WRITE_UNCERTAIN)) {
                        /*
                         * 另一恢复者已经推进 durable anchor。当前快照只用于保守计账，
                         * 不能再写回 PREPARING 覆盖 winner；runId due 会重新读取 PG。
                         */
                        durableReservations.add(reservation);
                        anchor.setNextPollAt(
                                Instant.now().plusMillis(config.getReconcilerIntervalMs()));
                        try {
                            redisCache.upsertDue(run.getId(), anchor);
                        } catch (Exception redisFailure) {
                            log.warn("Failed to rebuild winner-owned PREPARING due for run={}",
                                    run.getId(), redisFailure);
                        }
                        continue;
                    } else {
                        quarantinedRuns.add(run.getId());
                        continue;
                    }
                }
                // RELEASED 不再占用容量；其他状态都必须恢复进账本。
                if (reservation != null && reservation.state() != DataAnalysisReservationState.RELEASED) {
                    durableReservations.add(reservation);
                }
            } catch (Exception e) {
                log.error("Failed to parse reservation for run={} — QUARANTINED (capacity may be over-admitted)", run.getId(), e);
                quarantinedRuns.add(run.getId());
            }
        }

        // 任一 reservation 无法证明时 fail-closed，避免漏算容量后超额准入。
        if (!quarantinedRuns.isEmpty()) {
            log.error("CAPACITY QUARANTINE: {} run(s) have unparseable reservationJson — "
                    + "BLOCKING admission recovery to prevent over-admission. Runs: {}",
                    quarantinedRuns.size(), quarantinedRuns);
            // 保持 admission=RECOVERING，由运维处理隔离 Run；不能部分恢复后开放。
            return;
        }

        // 无隔离项时才按当前配置恢复账本并允许 RECOVERING→OPEN。
        int maxUnits = capacityProperties.getMaxUnits();
        int maxHeavyActive = capacityProperties.getMaxHeavyActive();
        DataAnalysisCapacityRecoveryReport report = capacityService.recover(
                durableReservations, maxUnits, maxHeavyActive);
        log.info("Capacity recovery: restored={} active={} heavyActive={} usedUnits={}/{} state={} conflicts={}",
                report.restoredReservations(), report.activeCount(),
                report.heavyActiveCount(), report.usedUnits(),
                report.configuredMaxUnits(), report.admissionState(), report.conflicts());
    }

    private void recoverToolJobAnchors() {
        // activeRuns 用于恢复轮询/finalizer；resumeReadyRuns 用于恢复 launch handoff。
        List<AgentRun> activeRuns = anchorService.listActive(200);
        List<AgentRun> resumeReadyRuns = anchorService.listResumeReady(200);

        for (AgentRun run : activeRuns) {
            // 重新读取 anchor，防止列表与处理之间被其他实例推进。
            ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
            if (anchor == null) continue;

            try {
                if (ToolJobRunDisposition.isDagPreparingAbort(
                        anchor.getRunDisposition())) {
                    recoverPreparingAbort(run.getId(), anchor);
                    continue;
                }
                // cleanup-only 可跨 EXECUTING/FAILED/CANCELED 重入；业务终态不能阻断容量收尾。
                if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                    resolveActiveAnchor(run, anchor);
                    continue;
                }
                // EXECUTING + active anchor 表示进程可能在工具已附着但 Run 尚未转 WAITING 时崩溃。
                if (run.getStatus() == AgentRunStatus.EXECUTING) {
                    if (ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
                        // 未来租约只重建 expiry due；过期租约必须通过 owner/opId CAS 才能接管。
                        if (recoverLiveDagBlocking(run.getId(), anchor)) {
                            resolveActiveAnchor(run, anchor);
                        }
                        continue;
                    }
                    if (!transferRecoveredAttached(run.getId(), anchor)) {
                        log.warn("Startup dispatch transfer remains unresolved for run={}", run.getId());
                    }
                    continue;
                }
                // 以下分支按 durable resume/finalizer 状态重建相应索引。
                String resumeState = anchor.getResumeState();
                if ("CONSUMED".equals(resumeState)) {
                    // 与在线路径相同：先 token-gated 清 DB，再清 Redis。
                    String token = anchor.getResumeToken();
                    boolean cleared = false;
                    if (token != null && !token.isBlank()) {
                        cleared = anchorService.clearAnchorWithToken(run.getId(), "CONSUMED",
                                token, anchor.getResumeLeaseVersion());
                    }
                    if (!cleared && token != null && !token.isBlank()) {
                        // durable clear 失败时保留 Redis，下一轮继续重试。
                        log.warn("Startup CONSUMED clear failed for run={}, leaving Redis for retry", run.getId());
                        continue;
                    }
                    redisCache.removeDue(run.getId());
                    redisCache.deletePendingCache(run.getId());
                    continue;
                }
                if ("READY".equals(resumeState) || "LAUNCHING".equals(resumeState)) {
                    // launch handoff 未结束，重建 due/cache；后续统一由 resumeReadyRuns 触发 tryResume。
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }
                if ("PENDING".equals(anchor.getResultFetchState())) {
                    // 终态已确认但结果体未取得，恢复有界结果拉取。
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }
                if (anchor.getFinalizerStep() != null && !anchor.getFinalizerStep().isBlank()) {
                    // finalizer 任意中间步骤都可从 anchor 重入。
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }
                // 尚无终态/恢复步骤时，向 Sandbox 查询真实任务状态并重新安排轮询。
                resolveActiveAnchor(run, anchor);
            } catch (Exception e) {
                log.error("Failed to recover anchor for run={}", run.getId(), e);
            }
        }

        for (AgentRun run : resumeReadyRuns) {
            // 单独扫描 RECEIVED + READY/LAUNCHING，覆盖 active 列表状态集合之外的恢复 Run。
            ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
            if (anchor == null) continue;
            log.info("Recovery found resume-ready run={}, resumeState={}", run.getId(), anchor.getResumeState());
            // 先重建辅助索引，再按 token/lease CAS 竞争实际恢复。
            redisCache.atomicWritePendingAndDue(run.getId(), anchor);
            resumeService.tryResume(run.getId());
        }
    }

    private void resolveActiveAnchor(AgentRun run, ToolJobAnchor anchor) {
        // taskId 缺失无法查询 Sandbox，保留 anchor 并等待人工/后续 PREPARING 修复。
        String taskId = anchor.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.warn("Anchor for run={} has no taskId, skipping", run.getId());
            return;
        }

        try {
            // 启动恢复直接查询当前 Sandbox 状态，不依赖进程崩溃前的 Redis 值。
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            if ("NOT_FOUND".equals(status)) {
                // 复用在线 finalizer 的 NOT_FOUND 有界重试，避免启动路径有另一套语义。
                finalizer.handleNotFound(run.getId(), anchor);
                return;
            }

            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status)) {
                // 已终态时拉取完整结果并做同一身份/状态验证。
                TaskResultResponse resultResp = sandboxService.getTaskResult(
                        GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
                // 与在线 reconciler 共用 validator，错误响应只重排轮询，不注入 Run。
                if (ToolJobResultValidator.validate(taskId, run.getId(), resultResp, status) == null) {
                    log.warn("Startup recovery: invalid terminal result for run={}, taskId={}, will retry",
                            run.getId(), taskId);
                    // 将重试时间先写 DB，再重建 Redis。
                    anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
                    if (persistRecoveredAnchor(run.getId(), anchor)) {
                        redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    }
                    return;
                }
                // 交给可重入 finalizer 释放容量并决定是否自动恢复。
                finalizer.handleTerminal(run.getId(), anchor, status, resultResp, anchor.isAutoResume());
                return;
            }

            // 非终态任务重新安排下一轮；暂停任务保留原 nextPollAt 语义。
            if (anchor.isAutoResume()
                    || ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
            }
            if (persistRecoveredAnchor(run.getId(), anchor)) {
                redisCache.atomicWritePendingAndDue(run.getId(), anchor);
            }

        } catch (Exception e) {
            // 启动阶段暂时无法访问 Sandbox 时保留 anchor，并重建 due 供在线 reconciler 重试。
            log.error("Failed to resolve anchor for run={}, taskId={}", run.getId(), taskId, e);
            anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
            if (persistRecoveredAnchor(run.getId(), anchor)) {
                redisCache.atomicWritePendingAndDue(run.getId(), anchor);
            }
        }
    }

    private boolean recoverLiveDagBlocking(String runId, ToolJobAnchor anchor) {
        return recoverLiveDagBlocking(runId, anchor, Instant.now());
    }

    private boolean recoverLiveDagBlocking(
            String runId,
            ToolJobAnchor anchor,
            Instant now) {
        if (!ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
            return false;
        }
        if (!hasDagBlockingIdentity(anchor)) {
            log.error("DAG blocking anchor lacks fenced identity for run={}; refusing takeover", runId);
            return false;
        }
        String operationId = anchor.getOperationId();
        String ownerId = anchor.getBlockingOwnerId();
        if (!DagBlockingWorkerLease.isExpired(anchor.getBlockingLeaseUntil(), now)) {
            // Redis is only a wake-up index. Keep the durable lease untouched.
            anchor.setNextPollAt(anchor.getBlockingLeaseUntil());
            redisCache.atomicWritePendingAndDue(runId, anchor);
            log.info("DAG blocking lease still live for run={}, owner={}, due={}",
                    runId, ownerId, anchor.getBlockingLeaseUntil());
            return false;
        }
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setAutoResume(false);
        anchor.setFinalizerError(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setNextPollAt(now);
        boolean marked = anchorService.promoteExpiredDagBlockingWorkerLost(
                runId, anchor, operationId, ownerId);
        if (marked) {
            log.warn("Startup claimed expired DAG blocking worker for run={}, operationId={}, owner={}; "
                            + "cleanup-only will not resume workflow",
                    runId, operationId, ownerId);
        } else {
            log.info("Expired DAG blocking takeover lost CAS for run={}, operationId={}, owner={}",
                    runId, operationId, ownerId);
        }
        return marked;
    }

    private boolean hasDagBlockingIdentity(ToolJobAnchor anchor) {
        return anchor.getOperationId() != null
                && !anchor.getOperationId().isBlank()
                && anchor.getBlockingOwnerId() != null
                && !anchor.getBlockingOwnerId().isBlank();
    }

    private boolean persistRecoveredAnchor(String runId, ToolJobAnchor anchor) {
        if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
            String operationId = anchor.getOperationId();
            String ownerId = anchor.getBlockingOwnerId();
            if (operationId == null || operationId.isBlank()
                    || ownerId == null || ownerId.isBlank()) {
                log.error("DAG cleanup anchor lacks fenced identity for run={}", runId);
                return false;
            }
            return anchorService.updateDagCleanup(runId, anchor, operationId, ownerId);
        }
        return anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
    }

    private ToolJobPreparingDispatchResolver.Resolution resolvePreparingDispatch(
            AgentRun run,
            ToolJobAnchor anchor,
            DataAnalysisReservation preparing) {
        /*
         * Generic PREPARING recovery remains EXECUTING-only. A fenced cleanup owner may
         * re-enter after the pipeline/control plane has already committed FAILED/CANCELED.
         */
        boolean cleanupStatus = ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())
                && (run.getStatus() == AgentRunStatus.FAILED
                || run.getStatus() == AgentRunStatus.CANCELED);
        if ((run.getStatus() != AgentRunStatus.EXECUTING && !cleanupStatus)
                || anchor.getOperationId() == null
                || anchor.getOperationId().isBlank()) {
            return ToolJobPreparingDispatchResolver.Resolution.invalidEvidence();
        }
        return ToolJobPreparingDispatchResolver.resolve(
                run.getId(), anchor, preparing, sandboxService, anchorService);
    }

    private void scheduleDagPreparingRetry(String runId, ToolJobAnchor anchor) {
        anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
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
            /*
             * CAS 失败通常表示另一恢复者已推进。只补 runId wake-up，让下一轮从 PG
             * 重新读取；不能拿当前 stale anchor 再做 durable write。
             */
            redisCache.upsertDue(runId, anchor);
        } catch (Exception persistenceFailure) {
            /*
             * takeover CAS 已把 PREPARING 和 nextPollAt 写进 PG。DB/Redis 任一短暂故障
             * 都不改变容量真相；尽力补 due，后续还有 PG rebuild。
             */
            log.error("Failed to persist PREPARING retry for run={}; rebuilding due only",
                    runId, persistenceFailure);
            try {
                redisCache.atomicWritePendingAndDue(runId, anchor);
            } catch (Exception redisFailure) {
                log.error("Failed to rebuild PREPARING retry due for run={}", runId, redisFailure);
            }
        }
    }

    private boolean transferRecoveredAttached(String runId, ToolJobAnchor anchor) {
        // 没有 durable reservation 无法证明/恢复容量，拒绝转 WAITING_TOOL_JOB。
        if (anchor.getReservationJson() == null || anchor.getReservationJson().isBlank()) {
            return false;
        }
        try {
            // 还原当前 reservation 状态，决定是否需要 TASK_ATTACHED→PENDING_TRANSFERRED。
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            DataAnalysisReservation current = mapper.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
            DataAnalysisReservation pending = current;
            if (current.state() == DataAnalysisReservationState.TASK_ATTACHED) {
                // 工具任务已附着后，把容量所有权转交给后台 pending 生命周期。
                pending = new DataAnalysisReservation(
                        current.reservationId(), current.identity(), current.resourceClass(),
                        current.capacityUnits(), DataAnalysisReservationState.PENDING_TRANSFERRED,
                        current.taskId(), current.acquiredAt());
                // 先恢复容量账本；冲突时不能推进 Run 状态。
                if (capacityService.restoreReservation(pending) == DataAnalysisRestoreOutcome.CONFLICT) {
                    return false;
                }
            }
            // 只接受可安全重入的三种状态；PREPARING 等其他状态必须先单独解析。
            if (pending.state() != DataAnalysisReservationState.PENDING_TRANSFERRED
                    && pending.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED
                    && pending.state() != DataAnalysisReservationState.RELEASED) {
                return false;
            }
            // 在内存 anchor 中保存已转交状态和 reservation 快照。
            anchor.setAnchorState("PENDING");
            anchor.setReservationJson(mapper.writeValueAsString(pending));
            // 崩溃前未安排轮询时，从当前时间建立下一次 poll。
            if (anchor.getNextPollAt() == null) {
                anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
            }
            // 单条 CAS 把 EXECUTING→WAITING_TOOL_JOB 与 anchor 更新一起提交。
            if (!anchorService.updateActiveAndStatus(
                    runId, anchor, AgentRunStatus.WAITING_TOOL_JOB,
                    AgentRunStatus.EXECUTING, anchor.getOperationId())) {
                return false;
            }
            // DB 成功后再重建 Redis，当前 Agent worker 已不再需要占用。
            redisCache.atomicWritePendingAndDue(runId, anchor);
            return true;
        } catch (Exception failure) {
            log.error("Failed to transfer recovered dispatch for run={}", runId, failure);
            return false;
        }
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
        log.error("Durable DAG PREPARING abort cannot be recovered for run={}, outcome={}; "
                        + "retaining PostgreSQL anchor without Sandbox/resume",
                runId, outcome);
    }

}
