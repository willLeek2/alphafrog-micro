package world.willfrog.agentlangchain.tooljob;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.dataanalysis.SessionQueryAdmissionException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
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
 *
 * <p>本类只处理业务字段条件（status、operationId、taskId、token/lease、checkpointVersion）。
 * 「当前进程允许处理哪些行」的跨代际归属判定由
 * {@link world.willfrog.agentlangchain.gateway.RunOwnershipGateway} 在认领与受理入口完成，
 * 这里不再按部署身份分叉 SQL。</p>
 */
@Service
public class ToolJobAnchorService {

    private static final Logger log = LoggerFactory.getLogger(ToolJobAnchorService.class);
    private final AgentRunMapper agentRunMapper;
    private final int sessionStaleSeconds;

    @Autowired(required = false)
    private AgentRunFinalizationService finalizationService;

    @Autowired
    public ToolJobAnchorService(
            AgentRunMapper agentRunMapper,
            @Value("${alphafrog.data-analysis.session-stale-seconds:600}") int sessionStaleSeconds) {
        this.agentRunMapper = agentRunMapper;
        this.sessionStaleSeconds = sessionStaleSeconds > 0 ? sessionStaleSeconds : 600;
    }

    /** 测试与手工构造：陈旧阈值用缺省 600 秒。 */
    public ToolJobAnchorService(AgentRunMapper agentRunMapper) {
        this(agentRunMapper, 600);
    }

    /**
     * Reads the anchor for a run. Returns null when no active tool job exists.
     */
    public ToolJobAnchor loadAnchor(String runId) {
        // 每次从 PostgreSQL 真相源读取；不使用可能丢失的 Redis 热副本。
        // 归属由 gateway 在认领处判定；这里只按 run id 读当前 anchor。
        AgentRun run = agentRunMapper.findById(runId);
        // 空 JSON 表示当前 Run 没有可恢复的外部工具任务。
        if (run == null || run.getToolJobAnchorJson() == null
                || run.getToolJobAnchorJson().isBlank()
                || "{}".equals(run.getToolJobAnchorJson().trim())) {
            return null;
        }
        // 解析失败显式抛出，避免把损坏 anchor 当成“没有任务”。
        return ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
    }

    /**
     * 读取 Run 创建时冻结的调度器版本。工具分发入口用它在产生外部副作用前判定协议能力。
     */
    public String loadSchedulerVersion(String runId) {
        AgentRun run = agentRunMapper.findById(runId);
        return run == null ? null : run.getSchedulerVersion();
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

    /**
     * 取消意图的窄持久化：只合并 autoResume=false 与 runDisposition=CANCELED
     * 两个字段，绑定精确 operationId，不整份写回旧锚点。
     * 第二个长工具已替换锚点时返回 false，调用方应重读当前任务，再决定重试或放弃本次取消。
     */
    public boolean persistCancelDisposition(String runId, String operationId,
                                             AgentRunStatus expectedStatus) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        return agentRunMapper.persistCancelDisposition(runId, expectedStatus, operationId) == 1;
    }

    /**
     * 暂停意图的持久化，与 persistCancelDisposition 对称：只合并 autoResume=false 与
     * runDisposition=PAUSED 两个字段，绑定精确 operationId，不整份写回旧锚点。
     * 锚点已有处置（取消/检查点失败/DAG 系）或任务已被替换时返回 false，
     * 调用方必须失败关闭本次暂停——先落库的处置优先，Run 保持原状。
     */
    public boolean persistPauseDisposition(String runId, String operationId,
                                            AgentRunStatus expectedStatus) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        return agentRunMapper.persistPauseDisposition(runId, expectedStatus, operationId) == 1;
    }

    /**
     * 手动恢复前清掉已收尾的暂停锚点（清成空对象）。栅栏仍满足才清；
     * 返回 false 表示并发处置已改变状态，调用方必须放弃本次恢复。
     */
    public boolean clearPausedAnchor(String runId, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        return agentRunMapper.clearPausedToolJobAnchor(runId, operationId) == 1;
    }

    /**
     * 只合并修复计数的专项更新：只改 {@code repairAttempts[toolName]}，绑定精确 operationId，
     * 不整份写回旧锚点。第二个长工具已替换锚点时返回 false。
     */
    public boolean persistRepairAttempt(String runId, String operationId,
                                        AgentRunStatus expectedStatus,
                                        String toolName, int attempt,
                                        boolean pending, boolean exhausted) {
        if (operationId == null || operationId.isBlank()
                || toolName == null || toolName.isBlank()) {
            return false;
        }
        return agentRunMapper.persistRepairAttempt(
                runId, expectedStatus, operationId, toolName, Math.max(0, attempt),
                pending, exhausted) == 1;
    }

    @Transactional
    public boolean claimPreparing(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
        // 只有空 anchor 才能创建 PREPARING owner，重复分发会返回 false。
        guardExecuteQuerySession(runId, anchor);
        return agentRunMapper.claimPreparingToolJobAnchor(
                runId, anchor.toJson(), expectedStatus) == 1;
    }

    @Transactional
    public boolean claimPreparingFromResume(String runId,
                                             ToolJobAnchor anchor,
                                             String expectedResumeToken,
                                             long expectedResumeLeaseVersion) {
        if (expectedResumeToken == null || expectedResumeToken.isBlank()
                || expectedResumeLeaseVersion <= 0) {
            return false;
        }
        guardExecuteQuerySession(runId, anchor);
        return agentRunMapper.claimPreparingToolJobAnchorFromResume(
                runId, anchor.toJson(), expectedResumeToken, expectedResumeLeaseVersion) == 1;
    }

    /**
     * 同一用户同一时刻只允许一条 executeQuery 在途。锁和计数必须与随后的 claim UPDATE
     * 在同一事务里，否则咨询锁在语句提交时就会释放。
     *
     * <p>守卫按锚点上的 {@code toolName} 判断，只对 {@link ToolJobAnchor#EXECUTE_QUERY_TOOL}
     * 生效。executePython 走同一 {@code persistPreparing} 入口，但 toolName 不是
     * executeQuery，这里直接返回，不取锁、不计数、不抛
     * {@code SESSION_QUERY_IN_PROGRESS}。</p>
     */
    private void guardExecuteQuerySession(String runId, ToolJobAnchor anchor) {
        if (anchor == null || !ToolJobAnchor.EXECUTE_QUERY_TOOL.equals(anchor.getToolName())) {
            return;
        }
        AgentRun run = agentRunMapper.findById(runId);
        String userId = run == null ? null : run.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new SessionQueryAdmissionException(
                    "SESSION_USER_ID_MISSING",
                    "alphafrog_agent_run.user_id is missing; cannot serialize executeQuery by session",
                    false);
        }
        agentRunMapper.lockExecuteQuerySession(userId);
        // 同 Run 的等待成员也必须串行。取锁后重读，不能使用取锁前的空锚点快照。
        AgentRun lockedRun = agentRunMapper.findById(runId);
        String activeJson = lockedRun == null ? null : lockedRun.getToolJobAnchorJson();
        ToolJobAnchor active = activeJson == null || activeJson.isBlank() || "{}".equals(activeJson.trim())
                ? null : ToolJobAnchor.fromJson(activeJson);
        // 结果已被恢复 worker 接收的终态任务不再在途；后续 UPDATE 仍核验恢复 token/version。
        boolean consumedTerminal = active != null && "TERMINAL".equals(active.getAnchorState())
                && active.isResultConsumed();
        if (active != null && !consumedTerminal && ToolJobAnchor.EXECUTE_QUERY_TOOL.equals(active.getToolName())
                && !java.util.Objects.equals(active.getOperationId(), anchor.getOperationId())) {
            throw new SessionQueryAdmissionException(
                    "SESSION_QUERY_IN_PROGRESS",
                    "another executeQuery is already running in this session; wait for it to finish or retry shortly",
                    true);
        }
        int inFlight = agentRunMapper.countInFlightExecuteQueryByUser(
                userId, runId, ToolJobAnchor.EXECUTE_QUERY_TOOL, sessionStaleSeconds);
        if (inFlight > 0) {
            throw new SessionQueryAdmissionException(
                    "SESSION_QUERY_IN_PROGRESS",
                    "another executeQuery is already running in this session; wait for it to finish or retry shortly",
                    true);
        }
    }

    public boolean updateActive(String runId, ToolJobAnchor anchor,
                                AgentRunStatus expectedStatus, String operationId) {
        // operationId 绑定当前 active dispatch，旧 operation 无法替换新任务。
        return agentRunMapper.updateActiveToolJobAnchor(
                runId, anchor.toJson(), expectedStatus, operationId) == 1;
    }

    /** 只给仍由原 PREPARING 操作持有的锚点写工作区拒绝，保留其余字段原样。 */
    public boolean recordWorkspaceRefusal(String runId, ToolJobAnchor anchor,
                                          Instant expectedLeaseUntil) {
        if (anchor == null || !"WORKSPACE_REFUSED".equals(anchor.getAnchorState())
                || !WaitMemberDispatchProof.isWorkspaceRefusalCode(anchor.getWorkspaceRefusalCode())
                || anchor.getOperationId() == null || anchor.getOperationId().isBlank()
                || anchor.getRequestFingerprint() == null || anchor.getRequestFingerprint().isBlank()
                || anchor.getTaskId() != null && !anchor.getTaskId().isBlank()) {
            return false;
        }
        boolean liveDag = ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition());
        if (liveDag != (expectedLeaseUntil != null)
                || liveDag && (!expectedLeaseUntil.equals(anchor.getBlockingLeaseUntil())
                || anchor.getBlockingOwnerId() == null || anchor.getBlockingOwnerId().isBlank())) {
            return false;
        }
        return agentRunMapper.recordWorkspaceRefusal(
                runId, anchor.getOperationId(), anchor.getRequestFingerprint(),
                anchor.getRunDisposition(), anchor.isAutoResume(),
                anchor.getBlockingOwnerId(),
                expectedLeaseUntil == null ? null : expectedLeaseUntil.toString(),
                anchor.getWorkspaceRefusalCode()) == 1;
    }

    /** 不覆盖取消等并发处置，只推进同一拒绝名额的持久状态。 */
    public boolean recordWorkspaceRefusalReleased(String runId, ToolJobAnchor anchor) {
        if (anchor == null || !"WORKSPACE_REFUSED".equals(anchor.getAnchorState())
                || !WaitMemberDispatchProof.isWorkspaceRefusalCode(anchor.getWorkspaceRefusalCode())
                || anchor.getReservationJson() == null || anchor.getReservationJson().isBlank()
                || anchor.getOperationId() == null || anchor.getRequestFingerprint() == null) {
            return false;
        }
        return agentRunMapper.recordWorkspaceRefusalReleased(
                runId, anchor.getOperationId(), anchor.getRequestFingerprint(),
                anchor.getWorkspaceRefusalCode(), anchor.getReservationJson()) == 1;
    }

    /** 仅在同一拒绝的容量释放证明已持久化时清锚点、写明确失败。 */
    @Transactional
    public boolean completeWorkspaceRefusal(String runId, String operationId,
                                            String fingerprint, String code) {
        if (operationId == null || operationId.isBlank()
                || fingerprint == null || fingerprint.isBlank()
                || !WaitMemberDispatchProof.isWorkspaceRefusalCode(code)) {
            return false;
        }
        if (agentRunMapper.completeWorkspaceRefusal(runId, operationId, fingerprint, code) != 1) {
            return false;
        }
        // UPDATE 持有的行锁一直到本事务提交。趁此时读取本次更新产生的终态，
        // 再在提交后发布；用户的并发恢复不能抢先把状态改成 RECEIVED 使事件丢失。
        if (finalizationService != null) {
            AgentRun run = agentRunMapper.findById(runId);
            if (run != null && (run.getStatus() == AgentRunStatus.FAILED
                    || run.getStatus() == AgentRunStatus.CANCELED)) {
                String userId = run.getUserId();
                String status = run.getStatus().name();
                Runnable publish = () -> publishWorkspaceRefusalTerminal(
                        runId, userId, status);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    publish.run();
                                }
                            });
                } else {
                    publish.run();
                }
            }
        }
        return true;
    }

    private void publishWorkspaceRefusalTerminal(String runId, String userId,
                                                 String status) {
        try {
            finalizationService.publishFinalizedEvent(runId, userId, status);
        } catch (RuntimeException publishFailure) {
            // 终态和名额释放已提交；轮询器仍可补查，不能回滚为 PREPARING。
            log.warn("Workspace refusal terminal event will be retried by polling: run={}",
                    runId, publishFailure);
        }
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

    /**
     * 写 CANCELED 终态用的 CAS：Run 允许仍处于 WAITING_TOOL_JOB 或 EXECUTING
     * （取消可能落在 accepted handoff 恢复执行期间），同时以 operationId 栅栏
     * 拒绝覆盖已被第二次长工具替换的新 anchor。调用方必须传入 CANCELED 作为 newStatus。
     */
    public boolean cancelFromStatuses(String runId, ToolJobAnchor anchor,
                                      AgentRunStatus newStatus) {
        if (newStatus != AgentRunStatus.CANCELED) {
            throw new IllegalArgumentException(
                    "cancelFromStatuses only writes CANCELED, got " + newStatus);
        }
        return agentRunMapper.cancelToolJobAnchorFromStatuses(
                runId, anchor.toJson(), newStatus, anchor.getOperationId()) == 1;
    }

    /**
     * 终态 Run 残留取消锚点的备用清理：Run 已被其他写入方写进业务终态后，
     * 正常取消 CAS 永远 0 行；本方法只清空残留锚点，不改写已写入的业务终态。终态
     * status、精确 operationId、runDisposition=CANCELED、显式 autoResume=false 与
     * finalizerStep 已达 EVENT 的全部安全条件都在 SQL WHERE 内复核。
     */
    public boolean closeResidualCanceledAnchor(String runId, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        return agentRunMapper.closeResidualCanceledAnchorOnTerminalRun(
                runId, operationId) == 1;
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
     * 原子推进 CAS_STATUS→RESUME_READY。
     * WHERE 绑定 10 个精确旧值条件；SET 只合并写 5 个恢复字段。
     * 只有 rows=1 的调用者是胜者。输家不得写 Redis 或启动 worker。
     */
    public int promoteCasStatusToResumeReady(String runId, String operationId,
                                              String toolCallId, int attempt, String taskId,
                                              long expectedResumeLeaseVersion,
                                              String newResumeToken) {
        return agentRunMapper.promoteCasStatusToResumeReady(
                runId, operationId, toolCallId, attempt, taskId,
                expectedResumeLeaseVersion, newResumeToken);
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

    public boolean casResumeStateAndStatus(String runId,
                                            ToolJobAnchor anchor,
                                            AgentRunStatus newStatus,
                                            AgentRunStatus expectedStatus,
                                            String expectedResumeState,
                                            String expectedResumeToken,
                                            long expectedLeaseVersion) {
        return agentRunMapper.casUpdateAnchorResumeStateAndStatus(
                runId, anchor.toJson(), newStatus, expectedStatus,
                expectedResumeState, expectedResumeToken, expectedLeaseVersion) == 1;
    }

    public boolean heartbeatResumeLauncher(String runId,
                                            String token,
                                            long version,
                                            String launcherOwnerId,
                                            long leaseSeconds) {
        if (token == null || token.isBlank() || version <= 0
                || launcherOwnerId == null || launcherOwnerId.isBlank() || leaseSeconds <= 0) {
            return false;
        }
        return agentRunMapper.heartbeatResumeLauncher(
                runId, token, version, launcherOwnerId, leaseSeconds) == 1;
    }

    public boolean acceptResumeHandoff(String runId,
                                       ToolJobAnchor anchor,
                                       String token,
                                       long version,
                                       String launcherOwnerId,
                                       long leaseSeconds) {
        if (token == null || token.isBlank() || version <= 0
                || launcherOwnerId == null || launcherOwnerId.isBlank() || leaseSeconds <= 0) {
            return false;
        }
        return agentRunMapper.acceptResumeHandoff(
                runId, anchor.toJson(), token, version, launcherOwnerId, leaseSeconds) == 1;
    }

    public boolean clearAcceptedResumeHandoff(String runId,
                                              String token,
                                              long version,
                                              String launcherOwnerId) {
        if (token == null || token.isBlank() || version <= 0
                || launcherOwnerId == null || launcherOwnerId.isBlank()) {
            return false;
        }
        return agentRunMapper.clearAcceptedResumeHandoff(
                runId, token, version, launcherOwnerId) == 1;
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
