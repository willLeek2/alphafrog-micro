package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.util.Map;

/**
 * Agent run 的生命周期控制服务 —— 取消（cancel）、暂停（pause）、恢复（resume）、删除（delete）。
 *
 * <h2>状态机</h2>
 * <pre>
 * RECEIVED → PLANNING → EXECUTING → SUMMARIZING → COMPLETED
 *                     ↘ (cancel) → CANCELING → CANCELED
 *                     ↘ (pause)  → WAITING   → (resume) → RECEIVED（重新执行）
 *                                                               (delete)  → （清除）
 * </pre>
 *
 * <h2>cancel 的关键步骤</h2>
 * <ol>
 *   <li>写 Redis 状态为 CANCELING —— 执行中的 todo loop 通过
 *       {@code LangchainRunExecutionGuard} 检测到后停止工具调用</li>
 *   <li>sleep 200ms 给执行器一个窗口感知 CANCELING 状态</li>
 *   <li>forceFlush observability —— 把当前观测数据刷新到 Redis</li>
 *   <li>attachObservabilityToSnapshot —— 最终观测写入 snapshot JSON</li>
 *   <li>updateSnapshot + updateStatusWithTtl —— 写 DB 并设中断 TTL</li>
 *   <li>eventService.append —— 写入 CANCELED 事件</li>
 * </ol>
 *
 * <h2>resume 的特殊处理</h2>
 * <p>如果请求带有 {@code planOverrideJson}，会先清除旧 plan 再存入新 plan，
 * 然后重置 run 状态为 RECEIVED 并异步启动新一次执行。</p>
 *
 * <h2>面试常考点</h2>
 * <ul>
 *   <li>"cancel 为什么 sleep 200ms？"→ 给执行器一个窗口去感知 CANCELING 状态并自行停止，
 *       而非直接强制 kill。这比硬 kill 更安全，正在执行的工具调用可以自然完成当前轮。</li>
 *   <li>"pause 和 cancel 有什么区别？"→ pause 把状态改为 WAITING 并保留 snapshot，
 *       resume 后从原状态继续；cancel 是终态不可恢复。</li>
 * </ul>
 *
 * @see LangchainRunReadService 读路径，提供 requireWritableRun
 * @see LangchainLinearRunPipeline 异步执行入口
 * @see AgentObservabilityService observability 落盘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainRunControlService {

    private final LangchainRunReadService readService;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final LangchainLinearRunPipeline pipeline;
    private final AgentRunCreditSettlementService creditSettlementService;
    private final ToolJobAnchorService anchorService;

    /**
     * 删除 run 及其关联的状态数据（Redis）。
     * 仅允许在非运行状态下删除；正在执行的 run 需要先 cancel 或 pause。
     */
    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isRunning(run.getStatus())) {
            throw new IllegalStateException("run is running, cancel/pause first");
        }
        int deleted = runMapper.deleteByIdAndUser(run.getId(), run.getUserId());
        if (deleted <= 0) {
            throw new IllegalArgumentException("run not found");
        }
        stateStore.clear(run.getId());
        return AgentEmpty.newBuilder().build();
    }

    /**
     * 取消正在进行的 run。终态不可恢复。
     * 已在终态的 run 直接返回当前状态（幂等）。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String runId = run.getId();
        String userId = run.getUserId();
        // 1. 先写 Redis 状态为 CANCELING —— todo loop 中的 ExecutionGuard 通过轮询 Redis 检测到这个状态后自行停止
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELING.name());
        // 1a. If there's an active tool-job anchor, persist cancel disposition so the
        //     reconciler routes through checkPausedTerminal and the finalizer releases
        //     capacity. The anchor keeps WAITING_TOOL_JOB for the finalizer CAS; the
        //     finalizer transitions to CANCELED after terminal sinks are complete.
        boolean hasActiveAnchor = false;
        try {
            ToolJobAnchor toolAnchor = anchorService.loadAnchor(runId);
            if (toolAnchor != null && toolAnchor.getOperationId() != null
                    && !toolAnchor.getOperationId().isBlank()) {
                toolAnchor.setAutoResume(false);
                toolAnchor.setRunDisposition("CANCELED");
                boolean persisted = anchorService.updateAnchor(runId, toolAnchor, run.getStatus());
                if (!persisted) {
                    // CAS failure means the anchor/status changed concurrently.
                    // Fail-closed: do NOT continue with normal cancel (which would
                    // set CANCELED status and leak the reservation). Let the caller
                    // retry — the anchor may still be processable by the reconciler.
                    log.warn("Cancel CAS failed for run={}: unable to persist anchor disposition — "
                            + "run will NOT be transitioned to CANCELED to prevent capacity leak. "
                            + "The reconciler will process when sandbox terminal arrives.", runId);
                    throw new IllegalStateException(
                            "cancel_anchor_cas_failed: unable to persist cancel disposition");
                }
                hasActiveAnchor = true;
                log.info("Cancel run={} with active tool-job anchor: persisted cancel disposition", runId);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to persist cancel disposition on tool-job anchor for run={}, "
                    + "falling back to standard cancel (capacity may leak): {}", runId, e.getMessage());
        }
        // 2. sleep 200ms 给正在执行的 todo loop 一个窗口去感知并响应 CANCELING 状态。
        //    这比硬 kill 线程更安全——正在执行的工具调用可以自然完成当前轮，避免留下半成品状态
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Cancel langchain run {} interrupted during observability flush wait", runId);
        }
        // 3. 把当前 Redis 中的 observability 数据强制刷新（运行中的累积数据可能在内存缓冲区）
        observabilityService.forceFlush(runId);
        // 4. 从 Redis 读取最新 observability → scrub 敏感信息 → 写回 DB snapshot 字段作为终态存档
        String snapshot = observabilityService.attachObservabilityToSnapshot(
                runId, run.getSnapshotJson(), AgentRunStatus.CANCELED);
        // 5. 更新 DB：snapshot、status、TTL（中断的 run 过期时间比正常完成的短）
        if (hasActiveAnchor) {
            // Keep current DB status for finalizer CAS (the reconciler will finalize
            // terminal sinks and transition to CANCELED after capacity release).
            runMapper.updateSnapshot(runId, userId, run.getStatus(), snapshot, false, null);
        } else {
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.CANCELED, snapshot, false, null);
            runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.CANCELED, eventService.nextInterruptedExpiresAt());
        }
        // 6. 发 CANCELED 事件 → 前端 SSE 收到后更新 UI 为已取消
        eventService.append(runId, userId, "CANCELED", Map.of(
                "run_id", runId,
                "engine", "agentLangchainService"));
        // 7. 最后再把 Redis 状态从 CANCELING 改成 CANCELED（终态）
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELED.name());
        // 8. 260612-01-02: cancel 路径触发结算（可能已有部分 LLM 调用）
        try {
            creditSettlementService.settleAsync(runId, userId);
        } catch (Exception settleEx) {
            log.warn("Failed to schedule settlement on langchain cancel: runId={} err={}", runId, settleEx.getMessage());
        }
        AgentRun refreshed = readService.requireReadableRun(runId, userId);
        if (hasActiveAnchor) {
            // API response must show CANCELED even though DB stays WAITING_TOOL_JOB
            // for the finalizer CAS. The finalizer transitions to CANCELED after
            // terminal sinks complete.
            return AgentLangchainRunMessageMapper.toRunMessage(refreshed).toBuilder()
                    .setStatus(AgentRunStatus.CANCELED.name())
                    .build();
        }
        return AgentLangchainRunMessageMapper.toRunMessage(refreshed);
    }

    /**
     * 暂停 run：状态改为 WAITING，可被 resume 恢复。
     * 已在终态的 run 直接返回（幂等）。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String snapshot = observabilityService.attachObservabilityToSnapshot(
                run.getId(), run.getSnapshotJson(), AgentRunStatus.WAITING);
        runMapper.updateSnapshot(run.getId(), run.getUserId(), AgentRunStatus.WAITING, snapshot, false, null);
        runMapper.updateStatusWithTtl(run.getId(), run.getUserId(), AgentRunStatus.WAITING,
                eventService.nextInterruptedExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "PAUSED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.WAITING.name());
        return AgentLangchainRunMessageMapper.toRunMessage(readService.requireReadableRun(run.getId(), run.getUserId()));
    }

    /**
     * 恢复被暂停或失败的 run。如果请求带有 planOverrideJson，
     * 先清除旧 plan 再存入新 plan，然后重置状态为 RECEIVED 异步重新执行。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (run.getStatus() == AgentRunStatus.EXPIRED) {
            throw new IllegalStateException("run expired");
        }
        if (run.getStatus() != AgentRunStatus.FAILED
                && run.getStatus() != AgentRunStatus.CANCELED
                && run.getStatus() != AgentRunStatus.WAITING) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        if (request.getPlanOverrideJson() != null && !request.getPlanOverrideJson().isBlank()) {
            stateStore.clearTasks(run.getId());
            stateStore.storePlanOverride(run.getId(), request.getPlanOverrideJson());
        }
        runMapper.resetForResume(run.getId(), run.getUserId(), eventService.nextTtlExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESUMED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.RECEIVED.name());
        AgentRun refreshed = readService.requireReadableRun(run.getId(), run.getUserId());
        pipeline.launchAsync(refreshed);
        return AgentLangchainRunMessageMapper.toRunMessage(refreshed);
    }

    /** COMPLETED / PARTIAL / FAILED / CANCELED / EXPIRED 均为不可逆终态 */
    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    /** RECEIVED / PLANNING / EXECUTING / SUMMARIZING 均为运行中可中断状态 */
    private boolean isRunning(AgentRunStatus status) {
        return status == AgentRunStatus.RECEIVED
                || status == AgentRunStatus.PLANNING
                || status == AgentRunStatus.EXECUTING
                || status == AgentRunStatus.SUMMARIZING;
    }
}
