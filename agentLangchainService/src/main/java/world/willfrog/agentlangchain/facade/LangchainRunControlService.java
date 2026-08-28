package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
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
 * <h2>cancel 的主要步骤</h2>
 * <ol>
 *   <li>写 Redis 状态为 CANCELING —— 执行中的 todo loop 通过
 *       {@code LangchainRunExecutionGuard} 检测到后停止工具调用</li>
 *   <li>sleep 200ms 给执行器一个窗口感知 CANCELING 状态</li>
 *   <li>forceFlush observability —— 把当前观测数据刷新到 Redis</li>
 *   <li>attachObservabilityToSnapshot —— 最终观测写入 snapshot JSON</li>
 *   <li>updateSnapshot + updateStatusWithTtl —— 写 DB 并设中断 TTL</li>
 *   <li>agentEventService.append —— 写入 CANCELED 事件</li>
 * </ol>
 *
 * <h2>resume 的特殊处理</h2>
 * <p>如果请求带有 {@code planOverrideJson}，会先清除旧 plan 再存入新 plan，
 * 然后重置 run 状态为 RECEIVED 并异步启动新一次执行。</p>
 *
 *
 * @see LangchainRunReadService 读路径，提供 requireWritableRun
 * @see LangchainLinearRunPipeline 异步执行入口
 * @see AgentRunObservabilityService observability 落盘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainRunControlService {

    private final LangchainRunReadService runReadService;
    private final AgentRunMapper runMapper;
    private final AgentRunEventService agentEventService;
    private final AgentRunStateStore stateStore;
    private final AgentRunObservabilityService agentObservabilityService;
    private final LangchainLinearRunPipeline pipeline;
    private final AgentRunCreditSettlementService creditSettlementService;
    private final ToolJobAnchorService anchorService;
    private final AgentRunFinalizationService finalizationService;

    @Autowired(required = false)
    private LangchainSchedulerMetrics schedulerMetrics;

    /**
     * 删除 run 及其关联的状态数据（Redis）。
     * 仅允许在非运行状态下删除；正在执行的 run 需要先 cancel 或 pause。
     */
    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        AgentRun run = runReadService.requireWritableRun(request.getId(), request.getUserId());
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
        AgentRun run = runReadService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String runId = run.getId();
        String userId = run.getUserId();

        // 0. 先写取消标记：取消一个正在等待长工具的 Run 时，不能直接把数据库状态改成 CANCELED。
        //    收尾组件后续还要以「等待长工具」状态为前提接管终态结果、释放沙箱容量并走完终态流程，
        //    所以这里只在进度记录里关闭自动恢复开关、记下取消意图。任何进度记录读写失败都保持
        //    Run 原状，让恢复扫描未来仍能处理；顺序必须是数据库先于 Redis。
        boolean hasActiveAnchor = false;
        try {
            ToolJobAnchor toolAnchor = anchorService.loadAnchor(runId);
            if (toolAnchor != null && toolAnchor.getOperationId() != null
                    && !toolAnchor.getOperationId().isBlank()) {
                // 窄合并写：绝不整份写回内存里的旧进度记录，防止把并发开始的第二个
                // 长工具的 PREPARING（准备中）状态和新 operationId 抹掉。operationId
                // 已变时先重读当前任务重试一次，仍失败则按既有语义拒绝执行。
                boolean persisted = anchorService.persistCancelDisposition(
                        runId, toolAnchor.getOperationId(), run.getStatus());
                if (!persisted) {
                    ToolJobAnchor current = anchorService.loadAnchor(runId);
                    if (current != null && current.getOperationId() != null
                            && !current.getOperationId().isBlank()
                            && !current.getOperationId().equals(toolAnchor.getOperationId())) {
                        persisted = anchorService.persistCancelDisposition(
                                runId, current.getOperationId(), run.getStatus());
                    }
                }
                if (!persisted) {
                    log.warn("Cancel CAS failed for run={}: unable to persist anchor disposition — "
                            + "run left untouched to prevent capacity leak. "
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
            // 失败即关闭（条件不满足就拒绝执行）：不能退化到普通取消路径；否则
            // WAITING_TOOL_JOB 被提前覆盖后，finalizer 的条件更新永远失败，
            // 外部工具的资源名额也就失去唯一的释放责任人。
            log.error("Failed to persist cancel disposition on tool-job anchor for run={} — "
                    + "cancel aborted to prevent capacity leak: {}", runId, e.getMessage());
            throw new IllegalStateException(
                    "cancel_anchor_disposition_failed: " + e.getMessage(), e);
        }

        // 1. 先写 Redis 状态为 CANCELING —— todo loop 中的 ExecutionGuard 通过轮询 Redis 检测到这个状态后自行停止
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELING.name());
        // 2. sleep 200ms 给正在执行的 todo loop 一个窗口去感知并响应 CANCELING 状态。
        //    这比硬 kill 线程更安全——正在执行的工具调用可以自然完成当前轮，避免留下半成品状态
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Cancel langchain run {} interrupted during observability flush wait", runId);
        }
        // 3. 把当前 Redis 中的 observability 数据强制刷新（运行中的累积数据可能在内存缓冲区）
        agentObservabilityService.forceFlush(runId);
        // 4. 从 Redis 读取最新 observability → scrub 敏感信息 → 写回 DB snapshot 字段作为终态存档
        String snapshot = agentObservabilityService.attachObservabilityToSnapshot(
                runId, run.getSnapshotJson(), AgentRunStatus.CANCELED);
        // 5. 更新 DB：snapshot、status、TTL（中断的 run 过期时间比正常完成的短）
        boolean canceledPersisted = false;
        if (hasActiveAnchor) {
            // 有活跃进度记录时保留数据库现状，给 finalizer 留住条件更新的前提；这里只更新可观测快照。
            // 终态事件与容量释放完成后，finalizer 才把数据库状态改成 CANCELED。
            runMapper.updateSnapshot(runId, userId, run.getStatus(), snapshot, false, null);
        } else {
            int snapshotRows = runMapper.updateSnapshot(
                    runId, userId, AgentRunStatus.CANCELED, snapshot, false, null);
            int ttlRows = runMapper.updateStatusWithTtl(
                    runId, userId, AgentRunStatus.CANCELED, agentEventService.nextInterruptedExpiresAt());
            canceledPersisted = snapshotRows == 1 && ttlRows == 1;
            if (!canceledPersisted) {
                log.warn("CANCELED persistence was not exact: runId={} snapshotRows={} ttlRows={}",
                        runId, snapshotRows, ttlRows);
            }
        }
        if (canceledPersisted && schedulerMetrics != null) {
            schedulerMetrics.recordCompletion(AgentRunStatus.CANCELED);
        }
        // 6. 发 CANCELED 事件 → 前端 SSE 收到后更新 UI 为已取消
        agentEventService.append(runId, userId, "CANCELED", Map.of(
                "run_id", runId,
                "engine", "agentLangchainService"));
        // 7. 最后再把 Redis 状态从 CANCELING 改成 CANCELED（终态）
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELED.name());
        // 活跃长工具仍由 ToolJobFinalizer 持有终态 CAS 责任；这里只给已经真正写入 DB CANCELED
        // 的普通取消发布 workspace dump 事件，不能把 WAITING_TOOL_JOB 提前伪装成持久终态。
        if (!hasActiveAnchor && canceledPersisted) {
            publishFinalizedEventSafely(runId, userId, AgentRunStatus.CANCELED);
        }
        // 8. 取消也要结算本次已产生的模型调用费用。
        try {
            creditSettlementService.settleAsync(runId, userId);
        } catch (Exception settleEx) {
            log.warn("Failed to schedule settlement on langchain cancel: runId={} err={}", runId, settleEx.getMessage());
        }
        AgentRun refreshed = runReadService.requireReadableRun(runId, userId);
        if (hasActiveAnchor) {
            // API 立即展示 CANCELED 以响应用户，但数据库暂时仍是 WAITING_TOOL_JOB：
            // 展示给用户的状态先变了，数据库里的最终状态要等 finalizer 释放容量后才写入；
            // 执行线程此时早已释放，这里只是展示态先行、持久态随后跟上。
            return AgentLangchainRunMessageMapper.toRunMessage(refreshed).toBuilder()
                    .setStatus(AgentRunStatus.CANCELED.name())
                    .build();
        }
        return AgentLangchainRunMessageMapper.toRunMessage(refreshed);
    }

    /** 工作区归档事件发送失败时走数据库轮询备用路径，不能反向破坏已经提交的取消终态。 */
    private void publishFinalizedEventSafely(String runId, String userId, AgentRunStatus status) {
        try {
            finalizationService.publishFinalizedEvent(runId, userId, status.name());
        } catch (RuntimeException e) {
            log.warn("Workspace finalization publish failed after terminal commit: "
                    + "runId={} status={} err={}", runId, status, e.getMessage(), e);
        }
    }

    /**
     * 暂停 run：状态改为 WAITING，可被 resume 恢复。
     * 已在终态的 run 直接返回（幂等）。
     * 等待长工具结果（WAITING_TOOL_JOB）的 run 暂不支持暂停：此时暂停只改状态、不处置锚点，
     * 长工具终态到达后清理链第一步的条件更新会命中 0 行，配额释放与终态事件随之丢失。
     * 需要停止这类 run 请改用取消（cancel 已对该状态做了锚点处置）。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        AgentRun run = runReadService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        if (run.getStatus() == AgentRunStatus.WAITING_TOOL_JOB) {
            throw new IllegalStateException(
                    "run is waiting for a long-running tool job; pause is temporarily unsupported in this state, use cancel instead");
        }
        String snapshot = agentObservabilityService.attachObservabilityToSnapshot(
                run.getId(), run.getSnapshotJson(), AgentRunStatus.WAITING);
        runMapper.updateSnapshot(run.getId(), run.getUserId(), AgentRunStatus.WAITING, snapshot, false, null);
        runMapper.updateStatusWithTtl(run.getId(), run.getUserId(), AgentRunStatus.WAITING,
                agentEventService.nextInterruptedExpiresAt());
        agentEventService.append(run.getId(), run.getUserId(), "PAUSED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.WAITING.name());
        return AgentLangchainRunMessageMapper.toRunMessage(runReadService.requireReadableRun(run.getId(), run.getUserId()));
    }

    /**
     * 恢复被暂停或失败的 run。如果请求带有 planOverrideJson，
     * 先清除旧 plan 再存入新 plan，然后重置状态为 RECEIVED 异步重新执行。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        AgentRun run = runReadService.requireWritableRun(request.getId(), request.getUserId());
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
        runMapper.resetForResume(run.getId(), run.getUserId(), agentEventService.nextTtlExpiresAt());
        agentEventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESUMED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.RECEIVED.name());
        AgentRun refreshed = runReadService.requireReadableRun(run.getId(), run.getUserId());
        // 这一行把 Run 重新交给全局调度闸门：线程池有空位就立刻执行，满了就进有界优先级
        // 队列排队；手动恢复和长工具自动恢复走的是同一个调度入口。调度器和队列都满时
        // 这一行会直接抛「队列已满」异常，调用方收到失败，不会阻塞等待。
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
