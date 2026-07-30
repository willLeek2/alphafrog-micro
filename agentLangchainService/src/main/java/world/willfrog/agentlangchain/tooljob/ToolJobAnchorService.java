package world.willfrog.agentlangchain.tooljob;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * {@code tool_job_anchor_json} 的唯一业务写入口。
 *
 * <p>方法返回值都表示数据库 CAS 是否真正更新一行，而不是“调用没有抛异常”。
 * 调用方必须把 false 当作失去所有权，重新读取当前 anchor 后再决定重入或退场。</p>
 */
@Service
public class ToolJobAnchorService {

    private final AgentRunMapper agentRunMapper;

    public ToolJobAnchorService(AgentRunMapper agentRunMapper) {
        this.agentRunMapper = agentRunMapper;
    }

    /**
     * Reads the anchor for a run. Returns null when no active tool job exists.
     */
    public ToolJobAnchor loadAnchor(String runId) {
        // 每次从 PostgreSQL 真相源读取；不使用可能丢失的 Redis 热副本。
        AgentRun run = agentRunMapper.findById(runId);
        // 空 JSON 表示当前 Run 没有可恢复的外部工具任务。
        if (run == null || run.getToolJobAnchorJson() == null || run.getToolJobAnchorJson().isBlank()) {
            return null;
        }
        // 解析失败显式抛出，避免把损坏 anchor 当成“没有任务”。
        return ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
    }

    /**
     * CAS-update the anchor JSON only, requiring the run to be in {@code expectedStatus}.
     *
     * @return true if the update succeeded, false if the status had changed
     */
    public boolean updateAnchor(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
        // expectedStatus 防止终态/暂停流程被旧 finalizer 覆盖。
        int rows = agentRunMapper.updateToolJobAnchor(runId, anchor.toJson(), expectedStatus);
        // 只有恰好一行表示本调用仍拥有写权限。
        return rows == 1;
    }

    /**
     * CAS-update both the anchor JSON and the run status atomically.
     *
     * @return true if the update succeeded
     */
    @Transactional
    public boolean updateAnchorAndStatus(String runId, ToolJobAnchor anchor,
                                          AgentRunStatus newStatus, AgentRunStatus expectedStatus) {
        // anchor 与 Run status 在同一条 UPDATE 中提交，不产生“状态已变但上下文未变”的中间窗口。
        int rows = agentRunMapper.updateToolJobAnchorAndStatus(runId, anchor.toJson(), newStatus, expectedStatus);
        return rows == 1;
    }

    public boolean claimPreparing(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
        // 只有空 anchor 才能创建 PREPARING owner，重复分发会返回 false。
        return agentRunMapper.claimPreparingToolJobAnchor(
                runId, anchor.toJson(), expectedStatus) == 1;
    }

    public boolean updateActive(String runId, ToolJobAnchor anchor,
                                AgentRunStatus expectedStatus, String operationId) {
        // operationId 绑定当前 active dispatch，旧 operation 无法替换新任务。
        return agentRunMapper.updateActiveToolJobAnchor(
                runId, anchor.toJson(), expectedStatus, operationId) == 1;
    }

    public boolean updateLiveDagBlocking(
            String runId,
            ToolJobAnchor anchor,
            AgentRunStatus expectedStatus,
            String operationId,
            String ownerId,
            Instant expectedLeaseUntil) {
        // 精确旧 lease 既是续租版本，也是旧 worker 在 takeover 后不能继续写的 fencing token。
        if (expectedLeaseUntil == null) {
            return false;
        }
        return agentRunMapper.updateLiveDagBlockingToolJobAnchor(
                runId,
                anchor.toJson(),
                expectedStatus,
                operationId,
                ownerId,
                expectedLeaseUntil.toString()) == 1;
    }

    public boolean beginLiveDagBlockingPreparingAbort(
            String runId,
            ToolJobAnchor anchor,
            AgentRunStatus expectedStatus,
            String operationId,
            String ownerId,
            Instant expectedLeaseUntil) {
        if (expectedLeaseUntil == null) {
            return false;
        }
        return agentRunMapper.beginLiveDagBlockingPreparingAbort(
                runId,
                anchor.toJson(),
                expectedStatus,
                operationId,
                ownerId,
                expectedLeaseUntil.toString()) == 1;
    }

    public boolean completeLiveDagBlockingPreparingAbort(
            String runId,
            AgentRunStatus expectedStatus,
            String operationId,
            String ownerId,
            Instant expectedLeaseUntil) {
        if (expectedLeaseUntil == null) {
            return false;
        }
        return agentRunMapper.completeLiveDagBlockingPreparingAbort(
                runId,
                expectedStatus,
                operationId,
                ownerId,
                expectedLeaseUntil.toString()) == 1;
    }

    public boolean claimLiveDagBlockingPreparingAbortCleanup(
            String runId,
            ToolJobAnchor cleanupAnchor,
            String operationId,
            String expectedOwnerId,
            Instant expectedLeaseUntil) {
        if (expectedLeaseUntil == null) {
            return false;
        }
        return agentRunMapper.claimLiveDagBlockingPreparingAbortCleanup(
                runId,
                cleanupAnchor.toJson(),
                operationId,
                expectedOwnerId,
                expectedLeaseUntil.toString()) == 1;
    }

    public boolean updateActiveAndStatus(String runId, ToolJobAnchor anchor,
                                         AgentRunStatus newStatus,
                                         AgentRunStatus expectedStatus,
                                         String operationId) {
        // 同时转移 anchor 和 Run 状态，并保留 operationId 所有权条件。
        return agentRunMapper.updateToolJobAnchorAndStatusByOperation(
                runId, anchor.toJson(), newStatus, expectedStatus, operationId) == 1;
    }

    public boolean clearActive(String runId, AgentRunStatus expectedStatus, String operationId) {
        // 仅当前 operation owner 可以清空；旧回调不能删除新任务 anchor。
        return agentRunMapper.clearActiveToolJobAnchor(runId, expectedStatus, operationId) == 1;
    }

    public boolean promoteExpiredDagBlockingWorkerLost(
            String runId,
            ToolJobAnchor anchor,
            String operationId,
            String ownerId) {
        // SQL 复核数据库时间的租约过期条件；调用方本地时间只用于减少无效 CAS。
        return agentRunMapper.promoteExpiredDagBlockingWorkerLost(
                runId, anchor.toJson(), operationId, ownerId) == 1;
    }

    public boolean updateDagCleanup(
            String runId,
            ToolJobAnchor anchor,
            String operationId,
            String ownerId) {
        // cleanup 可跨业务终态重入，但不能越过 operation/owner/disposition fencing。
        return agentRunMapper.updateDagCleanupToolJobAnchor(
                runId, anchor.toJson(), operationId, ownerId) == 1;
    }

    public boolean updateDagCleanupPreparing(
            String runId,
            ToolJobAnchor anchor,
            String operationId,
            String ownerId,
            String requestFingerprint) {
        // PREPARING→ATTACHED 与 nextPoll retry 共用同一个精确旧状态 fence。
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            return false;
        }
        return agentRunMapper.updateDagCleanupPreparingToolJobAnchor(
                runId,
                anchor.toJson(),
                operationId,
                ownerId,
                requestFingerprint) == 1;
    }

    public boolean completeDagCleanupAndClear(
            String runId,
            String operationId,
            String ownerId,
            String lastError) {
        // SQL 复核全部终态证明，并按当前 status 决定失败或保留原业务终态。
        return agentRunMapper.completeDagCleanupAndClearToolJobAnchor(
                runId, operationId, ownerId, lastError) == 1;
    }

    public boolean clearSynchronouslyCompleted(
            String runId,
            AgentRunStatus expectedStatus,
            String operationId) {
        // mapper 同时校验 terminal、released reservation 与 usage proof；缺一项均保留 anchor。
        return agentRunMapper.clearSynchronouslyCompletedToolJobAnchor(
                runId, expectedStatus, operationId) == 1;
    }

    public boolean clearLiveDagBlockingSynchronouslyCompleted(
            String runId,
            String operationId,
            String ownerId,
            Instant expectedLeaseUntil) {
        if (expectedLeaseUntil == null) {
            return false;
        }
        return agentRunMapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                runId,
                operationId,
                ownerId,
                expectedLeaseUntil.toString()) == 1;
    }

    /**
     * Narrow PostgreSQL JSONB merge for checkpoint-failure ownership. It does
     * not replace reservation/terminal fields and binds the failed checkpoint
     * identity so a stale pipeline cannot poison a newer external job.
     */
    public boolean markCheckpointFailed(ToolJobCheckpointRequest request, String error) {
        // SQL 只合并 disposition/autoResume/error 三个字段，不覆盖终态或 reservation。
        return agentRunMapper.markToolJobCheckpointFailed(
                request.getRunId(), request.getOperationId(), request.getToolCallId(),
                request.getAttempt(), request.getTaskId(),
                request.getExpectedCheckpointVersion(), error) == 1;
    }

    /**
     * CAS-update only the run status.
     *
     * @return true if the status was changed by this call
     */
    public boolean casUpdateStatus(String runId, AgentRunStatus newStatus, AgentRunStatus expectedStatus) {
        int rows = agentRunMapper.casUpdateStatus(runId, newStatus, expectedStatus);
        return rows == 1;
    }

    /**
     * Lists all runs with non-empty tool job anchors in WAITING_TOOL_JOB status.
     */
    public List<AgentRun> listActive(int limit) {
        // limit 约束单轮补扫工作量，避免恢复风暴长期占用调度线程。
        return agentRunMapper.listActiveToolJobAnchors(limit);
    }

    /**
     * Lists runs with status=RECEIVED and resumeState=READY/LAUNCHING,
     * i.e. runs that were CAS-ed back to RECEIVED but may not have been
     * picked up by the resume launcher (crash recovery).
     */
    public List<AgentRun> listResumeReady(int limit) {
        // READY 与超时 LAUNCHING 都需要启动恢复扫描，具体租约判断在 ResumeService。
        return agentRunMapper.listResumeReadyAnchors(limit);
    }

    /**
     * Atomic CAS: updates the anchor JSON only if the run status, resumeState,
     * resumeToken, AND resumeLeaseVersion all match expected values.
     * Prevents dual-launch races and stale-claim replays.
     *
     * @return true if exactly one row was updated (this caller won the claim)
     */
    public boolean casResumeState(String runId, ToolJobAnchor anchor,
                                   AgentRunStatus expectedStatus, String expectedResumeState,
                                   String expectedResumeToken, long expectedLeaseVersion) {
        // state + token + leaseVersion 三重条件共同阻止双 launcher 和陈旧重放。
        int rows = agentRunMapper.casUpdateAnchorResumeState(
                runId, anchor.toJson(), expectedStatus, expectedResumeState,
                expectedResumeToken, expectedLeaseVersion);
        return rows == 1;
    }

    /**
     * Atomic checkpoint merge: merges only checkpoint fields into anchor via
     * jsonb || concat. WHERE binds identity + taskId + checkpointVersion.
     * The SQL bumps checkpointVersion atomically. Preserves reservation,
     * terminal, and finalizer fields from concurrent writes.
     * @return true if exactly one row was updated
     */
    public boolean checkpointUpdate(String runId, ToolJobAnchor anchor,
                                     AgentRunStatus expectedStatus,
                                     String todoId, int sequence,
                                     String completedTodosJson,
                                     String datasetSnapshotJson, String datasetSnapshotDigest,
                                     String datasetRefsJson, int toolCallsUsed,
                                     String estimateJson) {
        // 把所有 checkpoint 字段一次性交给单条 SQL，禁止逐字段产生半成品状态。
        int rows = agentRunMapper.updateToolJobCheckpoint(
                runId, expectedStatus,
                anchor.getOperationId(), anchor.getToolCallId(),
                anchor.getAttempt(), anchor.getTaskId(),
                anchor.getCheckpointVersion(),
                todoId, sequence,
                completedTodosJson,
                datasetSnapshotJson, datasetSnapshotDigest,
                datasetRefsJson, toolCallsUsed,
                estimateJson);
        // rows=0 表示身份、状态或版本任一已变化，调用方必须进入失败归属判断。
        return rows == 1;
    }

    /**
     * Token+state+version-gated clear: only clears if the anchor's resumeState,
     * resumeToken, and resumeLeaseVersion all match. Prevents stale consumers
     * from clearing an anchor that has been re-claimed with a new lease.
     * There is no non-token-gated clear path.
     * @return true if exactly one row was cleared
     */
    public boolean clearAnchorWithToken(String runId, String expectedResumeState,
                                         String expectedToken, long expectedLeaseVersion) {
        // 没有非 token clear 旁路；只有已消费当前 lease 的 launcher 可以清理。
        return agentRunMapper.clearToolJobAnchorWithToken(
                runId, expectedResumeState, expectedToken, expectedLeaseVersion) == 1;
    }
}
