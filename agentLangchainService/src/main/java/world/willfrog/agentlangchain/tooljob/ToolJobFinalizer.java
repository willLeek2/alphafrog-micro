package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.tools.python.FinanceRecordProtoAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 外部工具终态的可重入收尾状态机。
 *
 * <p>每一步完成后先写入 durable anchor；进程在任意两步之间崩溃，下一次补扫都从
 * 第一个未完成步骤继续。顺序固定为：保存终态 envelope → 释放 Sandbox capacity →
 * 落资源用量 → 发唯一终态事件 → 把 Run CAS 回 RECEIVED → 生成恢复租约并触发重入。</p>
 */
@Service
public class ToolJobFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ToolJobFinalizer.class);

    static final String STEP_ENVELOPE = "ENVELOPE";
    static final String STEP_RELEASE = "RELEASE";
    static final String STEP_USAGE = "USAGE";
    static final String STEP_EVENT = "EVENT";
    static final String STEP_CAS_STATUS = "CAS_STATUS";
    static final String STEP_RESUME_READY = "RESUME_READY";
    static final String STEP_CANCELED = "CANCELED";

    private static final Map<String, Integer> STEP_ORDER = Map.of(
            STEP_ENVELOPE, 1, STEP_RELEASE, 2, STEP_USAGE, 3,
            STEP_EVENT, 4, STEP_CAS_STATUS, 5, STEP_RESUME_READY, 6,
            STEP_CANCELED, 7);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final DataAnalysisCapacityService capacityService;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;
    private final FinanceRecordChannelProcessor financeProcessor;
    private final FinanceRecordChannelConfigLoader configLoader;
    private final FinanceToolResultFormatter formatter;
    private final FinanceResultModelAdapter adapter;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunFinalizationService finalizationService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired(required = false)
    private ToolJobUsageHook usageHook;

    @Autowired(required = false)
    private ToolJobEventHook eventHook;

    @Autowired(required = false)
    private LangchainSchedulerMetrics schedulerMetrics;

    @Autowired
    public ToolJobFinalizer(ToolJobAnchorService anchorService,
                            ToolJobRedisCache redisCache,
                            DataAnalysisCapacityService capacityService,
                            ToolJobResumeService resumeService,
                            ToolJobConfig config,
                            FinanceRecordChannelProcessor financeProcessor,
                            FinanceRecordChannelConfigLoader configLoader,
                            FinanceToolResultFormatter formatter,
                            FinanceResultModelAdapter adapter,
                            AgentRunMapper agentRunMapper,
                            AgentRunFinalizationService finalizationService) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.capacityService = capacityService;
        this.resumeService = resumeService;
        this.config = config;
        this.financeProcessor = financeProcessor;
        this.configLoader = configLoader;
        this.formatter = formatter;
        this.adapter = adapter;
        this.agentRunMapper = agentRunMapper;
        this.finalizationService = finalizationService;
    }

    /**
     * 兼容纯单元测试和外部窄 fixture 的旧构造器。生产 Spring 装配固定走上面的完整构造器，
     * 从数据库真相源补齐 userId 后才发布 workspace 终态事件。
     */
    public ToolJobFinalizer(ToolJobAnchorService anchorService,
                            ToolJobRedisCache redisCache,
                            DataAnalysisCapacityService capacityService,
                            ToolJobResumeService resumeService,
                            ToolJobConfig config,
                            FinanceRecordChannelProcessor financeProcessor,
                            FinanceRecordChannelConfigLoader configLoader,
                            FinanceToolResultFormatter formatter,
                            FinanceResultModelAdapter adapter) {
        this(anchorService, redisCache, capacityService, resumeService, config,
                financeProcessor, configLoader, formatter, adapter, null, null);
    }

    // ========== public entry points ==========

    /**
     * @param autoResume false for paused/canceled runs (envelope+release but no CAS/READY)
     */
    public void handleTerminal(String runId, ToolJobAnchor anchor,
                                String terminalStatus, TaskResultResponse resultResp,
                                boolean autoResume) {
        // 同一轮收尾统一使用一个时间点，避免各字段在重入时产生互相矛盾的时间。
        Instant now = Instant.now();
        // 第一步：把 Sandbox 终态和有界结果摘要写入真相源。
        if (!isStepDone(anchor, STEP_ENVELOPE)) {
            // TERMINAL 是后续 release/clear 的 durable 状态证明，不只依赖进程内调用栈。
            anchor.setAnchorState("TERMINAL");
            // terminalStatus 是 reconciler 已确认的规范化终态。
            anchor.setTerminalStatus(terminalStatus);
            // 保留原始 Sandbox 状态用于事后契约核对。
            anchor.setSandboxTerminalStatus(terminalStatus);
            anchor.setTerminalAt(now);
            // resultResp 可能在 RESULT_LOST 路径为空。
            if (resultResp != null) {
                String stdout = resultResp.getStdout();
                String stderr = resultResp.getStderr();
                boolean success = "SUCCEEDED".equals(terminalStatus);
                boolean hasFinanceData = resultResp.hasFinanceRecordChannel()
                        || (stdout != null && stdout.contains(FinanceRecordDecoder.MARKER_FAMILY));

                String previewJson;
                if (success) {
                    if (hasFinanceData) {
                        if (anchor.getFinanceRecordLimitsJson() == null
                                || anchor.getFinanceRecordLimitsJson().isBlank()) {
                            log.warn("Finance data present but snapshot missing for run={}", runId);
                            anchor.setFinalizerError("finance_snapshot_missing");
                            persistFinalizerAnchor(runId, anchor);
                            return;
                        }
                        try {
                            FinanceRecordChannelConfigLoader.Snapshot snapshot = configLoader
                                    .parseFrozenSnapshot(anchor.getFinanceRecordLimitsJson());
                            var channelMeta = FinanceRecordProtoAdapter.channelMetadata(resultResp);
                            FinanceRecordExtractionRequest request = new FinanceRecordExtractionRequest(
                                    runId,
                                    "",
                                    anchor.getTodoId(),
                                    anchor.getToolCallId(),
                                    "async",
                                    anchor.getTaskId(),
                                    terminalStatus,
                                    resultResp.getExitCode(),
                                    stdout,
                                    stderr,
                                    channelMeta,
                                    FinanceRecordProtoAdapter.executionEnvironment(resultResp),
                                    snapshot.targetEnvironment(),
                                    snapshot.limits());
                            FinanceRecordExtractionResult extraction =
                                    financeProcessor.process(request);
                            FinanceResultModelAdapter.ProjectionBatch projected =
                                    adapter.project(extraction);
                            previewJson = formatter.formatSuccess(
                                    extraction.ordinaryStdout(),
                                    projected.results(),
                                    projected.notices());
                        } catch (FinanceRecordProcessingException e) {
                            log.warn("Finance processor failed for run={}: {} — "
                                    + "ENVELOPE blocked, will retry", runId, e.getCode());
                            anchor.setFinalizerError("finance_processing_failed");
                            persistFinalizerAnchor(runId, anchor);
                            return;
                        } catch (RuntimeException e) {
                            log.warn("Finance pipeline unexpected error for run={} — "
                                    + "ENVELOPE blocked, will retry", runId, e.getMessage());
                            anchor.setFinalizerError("finance_processing_failed");
                            persistFinalizerAnchor(runId, anchor);
                            return;
                        }
                    } else {
                        previewJson = formatter.formatSuccess(stdout, List.of(), List.of());
                    }
                } else {
                    // FAILED / CANCELED
                    boolean retryable = resultResp.hasRetryable() && resultResp.getRetryable();
                    String failureCode = "CANCELED".equals(terminalStatus)
                            ? "PYTHON_EXECUTION_CANCELED" : "PYTHON_EXECUTION_FAILED";
                    FinanceToolResultFormatter.FailureDetail failure =
                            new FinanceToolResultFormatter.FailureDetail(
                                    failureCode, "Sandbox " + terminalStatus, retryable,
                                    retryable ? "检查代码后重试" : "检查代码或联系管理员");

                    if (hasFinanceData) {
                        if (anchor.getFinanceRecordLimitsJson() == null
                                || anchor.getFinanceRecordLimitsJson().isBlank()) {
                            log.warn("Finance data present but snapshot missing"
                                    + " for FAILED/CANCELED run={}", runId);
                            anchor.setFinalizerError("finance_snapshot_missing");
                            persistFinalizerAnchor(runId, anchor);
                            return;
                        }
                        try {
                            FinanceRecordChannelConfigLoader.Snapshot snapshot = configLoader
                                    .parseFrozenSnapshot(anchor.getFinanceRecordLimitsJson());
                            FinanceRecordExtractionRequest request = new FinanceRecordExtractionRequest(
                                    runId, "", anchor.getTodoId(), anchor.getToolCallId(),
                                    "async", anchor.getTaskId(), terminalStatus,
                                    resultResp.getExitCode(), stdout, stderr,
                                    FinanceRecordProtoAdapter.channelMetadata(resultResp),
                                    FinanceRecordProtoAdapter.executionEnvironment(resultResp),
                                    snapshot.targetEnvironment(), snapshot.limits());
                            FinanceRecordExtractionResult extraction =
                                    financeProcessor.process(request);
                            stdout = extraction.ordinaryStdout();
                        } catch (FinanceRecordProcessingException e) {
                            log.warn("Finance de-marker failed for run={}: {} — "
                                    + "ENVELOPE blocked, will retry", runId, e.getCode());
                            anchor.setFinalizerError("finance_demarker_failed");
                            persistFinalizerAnchor(runId, anchor);
                            return;
                        }
                    }
                    // 移除 stderr 中的 finance marker 行，防止 formatter 永久拒绝
                    if (stderr != null
                            && stderr.contains(FinanceRecordDecoder.MARKER_FAMILY)) {
                        stderr = stderr.lines()
                                .filter(line -> !line.contains(FinanceRecordDecoder.MARKER_FAMILY))
                                .collect(java.util.stream.Collectors.joining("\n"));
                    }
                    previewJson = formatter.formatFailure(stdout, stderr, failure);
                }
                anchor.setTerminalResultPreview(previewJson);
                anchor.setTerminalRawRef(emptyToNull(resultResp.getDatasetDir()));
                anchor.setTerminalStderrPreview(boundedPreview(stderr));
                // error 保存结构化失败码，不用异常 message 替代。
                anchor.setTerminalErrorCode(emptyToNull(resultResp.getError()));
                anchor.setTerminalExitReason(emptyToNull(resultResp.getResourceUsage().getExitReason()));
                if (!"SUCCEEDED".equals(terminalStatus)) {
                    appendFailedPythonFingerprint(anchor);
                }
                try {
                    // 实际用量先冻结到 anchor，后续 USAGE 步骤幂等落账。
                    anchor.setTerminalUsageJson(JsonFormat.printer()
                            .omittingInsignificantWhitespace()
                            .print(resultResp.getResourceUsage()));
                } catch (Exception e) {
                    log.warn("Failed to serialize resourceUsage for run={}", runId, e);
                }
                // presence-aware 字段区分 false 与协议缺失；缺失时 release fail-closed。
                if (resultResp.hasRetryable()) {
                    anchor.setTerminalRetryable(resultResp.getRetryable());
                }
            } else if ("RESULT_LOST".equals(terminalStatus)) {
                // 结果永久丢失是明确不可重试分类，而不是未知分类。
                anchor.setTerminalRetryable(false);
                anchor.setTerminalResultPreview(formatter.formatFailure("", "",
                        new FinanceToolResultFormatter.FailureDetail(
                                "PYTHON_RESULT_LOST",
                                "沙箱结果永久丢失", false, "重新提交计算任务")));
            }
            // 先标记步骤，再连同 envelope 一起 CAS 写入，避免半步状态。
            anchor.setFinalizerStep(STEP_ENVELOPE);
            if (!persistFinalizerAnchor(runId, anchor)) {
                // CAS 失败说明别的进程已推进，当前 finalizer 立即退场。
                log.warn("ENVELOPE CAS failed for run={}", runId);
                return;
            }
        }

        // 兼容已完成 ENVELOPE 但旧版本尚未写 anchorState=TERMINAL 的可重入 anchor。
        if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())
                && isStepDone(anchor, STEP_ENVELOPE)
                && !"TERMINAL".equals(anchor.getAnchorState())) {
            anchor.setAnchorState("TERMINAL");
            if (!persistFinalizerAnchor(runId, anchor)) {
                log.warn("TERMINAL proof backfill CAS failed for run={}", runId);
                return;
            }
        }

        // 兼容终态先落 ENVELOPE、稍后重新拉取才拿到 retryable 的协议情况。
        if (anchor.getTerminalRetryable() == null && isStepDone(anchor, STEP_ENVELOPE)) {
            boolean backfilled = false;
            if (resultResp != null && resultResp.hasRetryable()) {
                anchor.setTerminalRetryable(resultResp.getRetryable());
                backfilled = true;
            } else if (resultResp == null && "RESULT_LOST".equals(terminalStatus)) {
                anchor.setTerminalRetryable(false);
                backfilled = true;
            }
            if (backfilled) {
                // 新分类已经补齐，清除先前的缺失诊断。
                anchor.setFinalizerError(null); // clear missing diagnostic
                if (!persistFinalizerAnchor(runId, anchor)) {
                    log.warn("terminalRetryable backfill CAS failed for run={}", runId);
                    return;
                }
            }
        }

        // 释放容量前必须有明确 retryable 分类；未知状态不能继续推进恢复。
        if (anchor.getTerminalRetryable() == null) {
            log.warn("terminalRetryable missing for run={}, fail-closed before RELEASE", runId);
            anchor.setFinalizerError("terminal_retryability_missing");
            persistFinalizerAnchor(runId, anchor);
            return;
        }

        // 第二步：凭 durable reservation 与终态证明释放 Sandbox capacity。
        if (!isStepDone(anchor, STEP_RELEASE)) {
            // releaseCapacity 同时处理首次释放和崩溃后 ALREADY_RELEASED。
            if (!releaseCapacity(anchor)) {
                log.warn("RELEASE failed for run={}, will retry", runId);
                return;
            }
            // 只有容量账本确认释放后才推进 STEP_RELEASE。
            anchor.setFinalizerStep(STEP_RELEASE);
            if (!persistFinalizerAnchor(runId, anchor)) return;
        }

        // 第三步：资源用量是终态真相的一部分，hook 缺失或失败都阻塞恢复。
        if (!isStepDone(anchor, STEP_USAGE)) {
            if (usageHook == null) {
                log.warn("USAGE hook not wired — blocking finalizer for run={}", runId);
                return;
            }
            // upsert 使用稳定 operation identity，重复重入不会重复计费。
            boolean ok = usageHook.upsertUsage(runId, anchor);
            if (!ok) {
                log.warn("USAGE hook failed for run={}, will retry", runId);
                return;
            }
            anchor.setUsagePersisted(true);
            anchor.setFinalizerStep(STEP_USAGE);
            if (!persistFinalizerAnchor(runId, anchor)) return;
        }

        // 第四步：发唯一逻辑终态事件；成功前不能把 Run 重新入队。
        if (!isStepDone(anchor, STEP_EVENT)) {
            if (eventHook == null) {
                log.warn("EVENT hook not wired — blocking finalizer for run={}", runId);
                return;
            }
            // eventHook 内部按 operation/toolCall/attempt 构造去重键。
            boolean ok = eventHook.emitTerminalEvent(runId, anchor);
            if (!ok) {
                log.warn("EVENT hook failed for run={}, will retry", runId);
                return;
            }
            anchor.setTerminalEventEmitted(true);
            anchor.setFinalizerStep(STEP_EVENT);
            if (!persistFinalizerAnchor(runId, anchor)) return;
        }

        if (!autoResume) {
            if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                // 原 DAG worker 已随旧进程消失；工具终态只用于收尾，不能生成 READY 或重跑 DAG。
                anchor.setFinalizerError(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
                boolean failedAndCleared = anchorService.completeDagCleanupAndClear(
                        runId,
                        anchor.getOperationId(),
                        anchor.getBlockingOwnerId(),
                        ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
                if (!failedAndCleared) {
                    log.warn("DAG cleanup-only fail+clear CAS failed for run={}, operationId={}",
                            runId, anchor.getOperationId());
                    return;
                }
                // PostgreSQL 已原子保存 FAILED/last_error 并清 anchor，随后清理可重建的 Redis 派生项。
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
                log.warn("DAG blocking worker lost; cleanup-only finalized run={} "
                        + "(EXECUTING fails, existing FAILED/CANCELED is preserved)", runId);
                return;
            }
            // checkpoint 失败由 finalizer 在完成 envelope/release/usage/event 后落 Run FAILED。
            if ("CHECKPOINT_FAILED".equals(anchor.getRunDisposition())) {
                anchor.setFinalizerError("durable_checkpoint_write_failed");
                if (!anchorService.updateAnchorAndStatus(runId, anchor,
                        AgentRunStatus.FAILED, AgentRunStatus.WAITING_TOOL_JOB)) {
                    log.warn("CHECKPOINT_FAILED terminal transition failed for run={}", runId);
                }
                return;
            }
            // canceled Run 同样必须先释放容量，再原子落 CANCELED。
            if ("CANCELED".equals(anchor.getRunDisposition())) {
                if (isStepDone(anchor, STEP_CANCELED)) {
                    redisCache.removeDue(runId);
                    redisCache.deletePendingCache(runId);
                    return;
                }
                anchor.setFinalizerStep(STEP_CANCELED);
                if (!anchorService.updateAnchorAndStatus(runId, anchor,
                        AgentRunStatus.CANCELED, AgentRunStatus.WAITING_TOOL_JOB)) {
                    log.warn("CANCELED terminal transition failed for run={}, will retry", runId);
                    return;
                }
                if (schedulerMetrics != null) {
                    schedulerMetrics.recordCompletion(AgentRunStatus.CANCELED);
                }
                publishCanceledWorkspaceFinalized(runId);
                // DB 已持久化取消终态后才清 Redis due/cache，Redis 丢失不影响真相。
                // STEP_CANCELED 排在最后，重入时所有前序步骤均视为完成。
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
                log.info("Canceled terminal finalized for run={}, capacity released", runId);
                return;
            }
            // 暂停状态保留 WAITING_TOOL_JOB，不生成 READY，等待用户明确恢复。
            log.info("Terminal handled for paused run={}, not auto-resuming", runId);
            return;
        }

        // 第五步：把 finalizerStep 与 Run 状态从 WAITING_TOOL_JOB 原子推进到 RECEIVED。
        if (!isStepDone(anchor, STEP_CAS_STATUS)) {
            anchor.setFinalizerStep(STEP_CAS_STATUS);
            if (!anchorService.updateAnchorAndStatus(runId, anchor, AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB)) {
                log.warn("CAS_STATUS atomic update failed for run={}", runId);
                return;
            }
        }

        // 第六步：原子推进 CAS_STATUS→RESUME_READY。正常 finalizer 与 backfill 恢复共享同一条入口。
        if (!isStepDone(anchor, STEP_RESUME_READY)) {
            completeResumeReady(runId, anchor);
        }
    }

    /**
     * 原子推进 CAS_STATUS→RESUME_READY。正常 finalizer 第六步和 backfill 恢复都走此入口。
     *
     * <p>内存 anchor 保持在 CAS_STATUS 旧值；SQL 使用 {@code jsonb ||} 只合并写入 5 个恢复字段，
     * claimedAt 用数据库 CURRENT_TIMESTAMP，leaseVersion 在 DB 内自增。WHERE 绑定 10 个精确旧值
     * 条件。只有 rows=1 的调用者才是胜者，才重读落地 anchor、写 Redis 并调用 tryResume。
     * 输家立即退出，不写 Redis、不启动 worker。</p>
     */
    void completeResumeReady(String runId, ToolJobAnchor anchor) {
        if (!STEP_CAS_STATUS.equals(anchor.getFinalizerStep())) {
            return;
        }
        String rs = anchor.getResumeState();
        if (rs != null && !rs.isBlank()) {
            return; // 已被其他路径推进
        }

        String opId = anchor.getOperationId();
        String tcId = anchor.getToolCallId();
        String taskId = anchor.getTaskId();
        int attempt = anchor.getAttempt();

        if (opId == null || opId.isBlank()
                || tcId == null || tcId.isBlank()
                || taskId == null || taskId.isBlank()
                || attempt <= 0
                || anchor.getResumeLeaseVersion() < 0
                || anchor.getResumeLeaseVersion() >= Long.MAX_VALUE) {
            log.error("completeResumeReady fail-closed for run={}: missing identity "
                    + "operationId={} toolCallId={} taskId={} attempt={}",
                    runId, opId, tcId, taskId, attempt);
            return;
        }

        String newToken = UUID.randomUUID().toString();

        int rows = anchorService.promoteCasStatusToResumeReady(
                runId, opId, tcId, attempt, taskId,
                anchor.getResumeLeaseVersion(),
                newToken);

        if (rows != 1) {
            log.warn("promoteCasStatusToResumeReady lost race for run={}", runId);
            return; // 输家不写 Redis，不启动 worker
        }

        // 胜者从 DB 重读落地 anchor，确保 Redis 和 tryResume 基于持久化数据
        ToolJobAnchor persisted = anchorService.loadAnchor(runId);
        if (persisted == null) {
            log.warn("completeResumeReady winner failed to reload anchor for run={}, "
                    + "leaving READY for next cycle", runId);
            return; // 不回滚 READY；下一轮既有 READY 扫描会接管
        }
        redisCache.writePendingCache(runId, persisted);
        resumeService.tryResume(runId);
    }

    /**
     * 长工具取消的 workspace 事件只能在 CANCELED CAS 成功后发布。
     * 发布/读取失败走 polling 兜底，绝不能反向回滚已经提交的终态与容量释放收口。
     */
    private void publishCanceledWorkspaceFinalized(String runId) {
        if (agentRunMapper == null || finalizationService == null) {
            return;
        }
        try {
            AgentRun run = agentRunMapper.findById(runId);
            if (run == null || run.getUserId() == null || run.getUserId().isBlank()) {
                log.warn("Workspace finalization event skipped after CANCELED CAS: "
                        + "run/user missing runId={}", runId);
                return;
            }
            finalizationService.publishFinalizedEvent(
                    runId, run.getUserId(), AgentRunStatus.CANCELED.name());
        } catch (RuntimeException e) {
            log.warn("Workspace finalization event failed after terminal CAS; polling will retry: "
                    + "runId={} status={} err={}",
                    runId, AgentRunStatus.CANCELED, e.getMessage(), e);
        }
    }

    public void handleNotFound(String runId, ToolJobAnchor anchor) {
        // getTaskResult 暂无结果体时，用有界次数与保留期限决定继续轮询或 RESULT_LOST。
        Instant now = Instant.now();
        // 已确认 Sandbox 终态后仍取不到结果，才累计“终态结果丢失”窗口。
        if (anchor.getTerminalConfirmedAt() != null) {
            long elapsed = java.time.Duration.between(anchor.getTerminalConfirmedAt(), now).toSeconds();
            int attempts = anchor.getResultFetchAttempts() + 1;
            anchor.setResultFetchAttempts(attempts);
            if (elapsed > config.getResultRetentionDeadlineSeconds()
                    || attempts >= config.getResultFetchMaxAttempts()) {
                // 超过任一上限后冻结 RESULT_LOST，再复用正常 finalizer 释放容量与落事件。
                anchor.setResultFetchState("LOST");
                anchor.setTerminalStatus("RESULT_LOST");
                anchor.setTerminalAt(now);
                log.error("Result permanently lost for run={}, taskId={}", runId, anchor.getTaskId());
                handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                return;
            }
        } else {
            // 第一次发现终态但结果体缺失，建立保留期限起点。
            anchor.setResultFetchState("PENDING");
            anchor.setTerminalConfirmedAt(now);
            anchor.setResultFetchAttempts(1);
        }
        // 未到丢失阈值时安排下一次 due；DB anchor 与 Redis 索引同时更新。
        anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
        if (persistFinalizerAnchor(runId, anchor)) {
            redisCache.upsertDue(runId, anchor);
            redisCache.writePendingCache(runId, anchor);
        }
    }

    // ========== capacity release ==========

    /** @return true if capacity was released (or already released) */
    private boolean releaseCapacity(ToolJobAnchor anchor) {
        // 没有 reservation 的兼容任务不占用 Sandbox capacity，可直接视为已释放。
        if (anchor.getReservationJson() == null || anchor.getReservationJson().isBlank()) return true;
        try {
            // 从 durable anchor 还原准入时的 reservation，不按当前配置重新估算。
            DataAnalysisReservation current = objectMapper.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
            // 已经写回 RELEASED 的重入路径直接幂等成功。
            if (current.state() == DataAnalysisReservationState.RELEASED) return true;

            // release proof 要求 reservation 先进入 TERMINAL_CONFIRMED。
            DataAnalysisReservation confirmed;
            if (current.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
                // 只改变状态，reservationId、identity、resourceClass、units 和 taskId 全部保持不变。
                confirmed = new DataAnalysisReservation(current.reservationId(), current.identity(),
                        current.resourceClass(), current.capacityUnits(),
                        DataAnalysisReservationState.TERMINAL_CONFIRMED,
                        current.taskId(), current.acquiredAt());
                // restoreReservation 把崩溃前的 reservation 恢复进当前进程容量账本。
                DataAnalysisRestoreOutcome ro = capacityService.restoreReservation(confirmed);
                if (ro == DataAnalysisRestoreOutcome.CONFLICT) {
                    // CONFLICT 可能表示上一次进程已经完成释放；直接尝试 release 识别 ALREADY_RELEASED。
                    DataAnalysisTerminalEnvelope env = buildEnvelope(confirmed, anchor);
                    if (env != null) {
                        // 终态 envelope 是 release 的证明，不能仅凭状态字符串释放。
                        DataAnalysisReleaseRequest req = new DataAnalysisReleaseRequest(confirmed,
                                new DataAnalysisReleaseProof.Terminal(env),
                                DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
                        DataAnalysisReleaseOutcome oo = capacityService.releaseReservation(req);
                        if (oo == DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                            // 把 RELEASED 状态写回 anchor，避免下次启动再次占用。
                            writeReleasedReservation(anchor, confirmed);
                            return true;
                        }
                    }
                    log.warn("Reservation restore CONFLICT for id={}, release also failed", current.reservationId());
                    return false;
                }
            } else {
                // anchor 已保存 TERMINAL_CONFIRMED 时直接继续，不重复 restore 状态转换。
                confirmed = current;
            }

            // 构造带 estimate、usage、result/error 和 terminalAt 的完整释放证明。
            DataAnalysisTerminalEnvelope envelope = buildEnvelope(confirmed, anchor);
            if (envelope == null) return false;

            // release reason 明确记录为 Sandbox 终态确认，不与取消/超时原因混淆。
            DataAnalysisReleaseRequest req = new DataAnalysisReleaseRequest(confirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            DataAnalysisReleaseOutcome oo = capacityService.releaseReservation(req);
            // 首次 RELEASED 和崩溃重入 ALREADY_RELEASED 都是幂等成功。
            boolean ok = oo == DataAnalysisReleaseOutcome.RELEASED
                    || oo == DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            if (!ok) {
                log.warn("Release outcome {} for reservationId={}", oo, confirmed.reservationId());
                return false;
            }

            // 把 RELEASED 写回 durable anchor；否则重启恢复会从旧 PENDING/CONFIRMED 快照重新占用容量。
            return writeReleasedReservation(anchor, confirmed);
        } catch (Exception e) {
            log.error("releaseCapacity failed for reservation", e);
            return false;
        }
    }

    /**
     * 把已释放 reservation 序列化回 anchor。
     * 入参保留原 reservation 身份；返回 true 表示内存 anchor 已更新，外层随后负责 CAS 落库。
     */
    private boolean writeReleasedReservation(ToolJobAnchor anchor,
                                              DataAnalysisReservation confirmed) throws Exception {
        // 只把 state 改为 RELEASED，其余容量身份完全不变。
        DataAnalysisReservation released = new DataAnalysisReservation(
                confirmed.reservationId(), confirmed.identity(),
                confirmed.resourceClass(), confirmed.capacityUnits(),
                DataAnalysisReservationState.RELEASED,
                confirmed.taskId(), confirmed.acquiredAt());
        // 外层 STEP_RELEASE 的 updateAnchor 会把该 JSON 与步骤标记一起原子写入。
        anchor.setReservationJson(objectMapper.writeValueAsString(released));
        return true;
    }

    private DataAnalysisTerminalEnvelope buildEnvelope(DataAnalysisReservation reservation, ToolJobAnchor anchor) {
        try {
            // 实际 usage 必须与 reservation.resourceClass 一致。
            DataAnalysisResourceUsage usage = buildResourceUsage(reservation.resourceClass(),
                    anchor.getTerminalUsageJson());
            // estimate 缺失/损坏时 fail-closed，不能构造不完整 release proof。
            DataAnalysisEstimate estimate = parseEstimate(anchor.getEstimateJson());
            if (estimate == null) return null;
            /*
             * 兼容 2026-07-27 之前由 PythonSandboxTools 分类漂移写出的存量 anchor：
             * 工具层曾把 libraries 当 heavy hints，先得到 HEAVY/3；构造 estimate 时却把
             * hints 清空，容量层重算后 reservation 变成 STANDARD/1。严格 envelope 校验会
             * 因二者不一致而永久阻断 RELEASE，Run 停在 WAITING_TOOL_JOB，usedUnits 也持续
             * 被占用。
             *
             * 修复上线后新任务不会再产生这种组合。这里仅识别已知的窄签名
             * HEAVY/3 + 空 hints + STANDARD/1，并用实际 reservation 修正 estimate；
             * 其他任意 mismatch 仍然 fail-closed，不能把未知数据损坏伪装成兼容迁移。
             */
            estimate = normalizeKnownLegacyEstimateMismatch(estimate, reservation, anchor);
            if (estimate == null) return null;
            // success 只接受明确 SUCCEEDED，其他终态都按失败 envelope 处理。
            String status = anchor.getTerminalStatus();
            boolean success = "SUCCEEDED".equals(status);
            String rawRef = anchor.getTerminalRawRef();
            String preview = boundedPreview(anchor.getTerminalResultPreview());
            String errorCode = anchor.getTerminalErrorCode();

            // 成功但无可见结果时提供有界占位，保持 envelope 合法并保留诊断。
            if (success && rawRef == null && preview == null) {
                log.warn("SUCCEEDED without preview/rawRef for op={}", anchor.getOperationId());
                preview = "(preview unavailable)";
            }
            // 失败至少使用终态名作为 errorCode，避免空错误证明。
            if (!success && errorCode == null) errorCode = status;

            // envelope 绑定 reservation identity 与 anchor operation/task，release 服务会再次核对。
            return new DataAnalysisTerminalEnvelope(
                    reservation.identity().runId(), reservation.identity().toolCallId(),
                    reservation.identity().attempt(), reservation.operationId(), reservation.taskId(),
                    status, success, preview, rawRef, errorCode,
                    success ? null : "sandbox " + status,
                    Boolean.TRUE.equals(anchor.getTerminalRetryable()),
                    estimate, reservation, usage, anchor.getTerminalAt(), true);
        } catch (Exception e) {
            log.error("buildEnvelope failed for reservationId={}, status={}",
                    reservation.reservationId(), anchor.getTerminalStatus(), e);
            return null;
        }
    }

    DataAnalysisResourceUsage buildResourceUsage(DataAnalysisResourceClass rc, String usageJson) {
        return ToolJobResourceUsageParser.parse(objectMapper, rc, usageJson);
    }

    /** @return parsed estimate or null (fail-closed: blocks RELEASE) */
    private DataAnalysisEstimate parseEstimate(String estimateJson) {
        if (estimateJson == null || estimateJson.isBlank()) {
            log.warn("estimateJson missing — cannot build valid envelope");
            return null;
        }
        try {
            return objectMapper.readValue(estimateJson, DataAnalysisEstimate.class);
        } catch (Exception e) {
            log.error("Failed to parse estimateJson", e);
            return null;
        }
    }

    /**
     * 只修复已知旧版本的资源分类漂移，并把修复后的 estimate 回写内存 anchor。
     *
     * <p>外层 RELEASE 步骤随后会通过同一次 anchor CAS 把 corrected estimate 与 RELEASED
     * reservation 一起持久化，因此重启后不会反复执行兼容分支。</p>
     */
    private DataAnalysisEstimate normalizeKnownLegacyEstimateMismatch(
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            ToolJobAnchor anchor) {
        if (estimate.resourceClass() == reservation.resourceClass()
                && estimate.capacityUnits() == reservation.capacityUnits()) {
            return estimate;
        }
        boolean knownLegacySignature =
                anchor.getSchemaVersion() <= 1
                        && estimate.resourceClass() == DataAnalysisResourceClass.HEAVY
                        && estimate.capacityUnits()
                        == DataAnalysisResourceClass.HEAVY.defaultCapacityUnits()
                        && estimate.heavyOperationHints().isEmpty()
                        && reservation.resourceClass() == DataAnalysisResourceClass.STANDARD
                        && reservation.capacityUnits()
                        == DataAnalysisResourceClass.STANDARD.defaultCapacityUnits();
        if (!knownLegacySignature) {
            log.error("Unknown estimate/reservation mismatch blocks release: reservationId={}, "
                            + "estimate={}/{}, reservation={}/{}",
                    reservation.reservationId(),
                    estimate.resourceClass(), estimate.capacityUnits(),
                    reservation.resourceClass(), reservation.capacityUnits());
            return null;
        }
        DataAnalysisEstimate corrected = new DataAnalysisEstimate(
                estimate.estimatedRows(),
                estimate.estimatedBytes(),
                estimate.fileCount(),
                estimate.selectedColumnRatio(),
                estimate.manifestMemberCount(),
                estimate.heavyOperationHints(),
                reservation.resourceClass(),
                reservation.capacityUnits());
        try {
            anchor.setEstimateJson(objectMapper.writeValueAsString(corrected));
        } catch (Exception serializationFailure) {
            log.error("Failed to persist normalized legacy estimate for reservationId={}",
                    reservation.reservationId(), serializationFailure);
            return null;
        }
        log.warn("Normalized legacy estimate mismatch for reservationId={} from HEAVY/{} to STANDARD/{}",
                reservation.reservationId(), estimate.capacityUnits(), reservation.capacityUnits());
        return corrected;
    }

    // ========== helpers ==========

    private boolean isStepDone(ToolJobAnchor anchor, String step) {
        String current = anchor.getFinalizerStep();
        if (current == null) return false;
        return STEP_ORDER.getOrDefault(current, 0) >= STEP_ORDER.getOrDefault(step, 0);
    }

    private boolean persistFinalizerAnchor(String runId, ToolJobAnchor anchor) {
        if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
            String operationId = anchor.getOperationId();
            String ownerId = anchor.getBlockingOwnerId();
            if (operationId == null || operationId.isBlank()
                    || ownerId == null || ownerId.isBlank()) {
                log.error("DAG cleanup anchor is missing fenced identity for run={}", runId);
                return false;
            }
            return anchorService.updateDagCleanup(runId, anchor, operationId, ownerId);
        }
        return anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static void appendFailedPythonFingerprint(ToolJobAnchor anchor) {
        String fingerprint = anchor.getPythonRequestFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        LinkedHashSet<String> history = new LinkedHashSet<>(
                anchor.getPythonFailedRequestFingerprints() == null
                        ? List.of() : anchor.getPythonFailedRequestFingerprints());
        history.add(fingerprint.trim());
        anchor.setPythonFailedRequestFingerprints(List.copyOf(history));
    }

    /** Truncate to 16KB UTF-8 respecting MAX_RESULT_PREVIEW_BYTES including suffix. */
    static String boundedPreview(String s) {
        if (s == null) return null;
        String suffix = "…(truncated)";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        int max = DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;
        if (raw.length <= max) return s;
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        int cut = max - suffixBytes.length;
        if (cut <= 0) return suffix;
        while (cut > 0 && (raw[cut] & 0xC0) == 0x80) cut--;
        return new String(raw, 0, cut, StandardCharsets.UTF_8) + suffix;
    }

}
