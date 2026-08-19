package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AgentRunMapper {

    int insert(AgentRun run);

    AgentRun findById(@Param("id") String id);

    AgentRun findByIdAndUser(@Param("id") String id, @Param("userId") String userId);

    List<AgentRun> listByUser(@Param("userId") String userId,
                              @Param("status") AgentRunStatus status,
                              @Param("fromTime") OffsetDateTime fromTime,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    int countByUser(@Param("userId") String userId,
                    @Param("status") AgentRunStatus status,
                    @Param("fromTime") OffsetDateTime fromTime);

    int sumCompletedCreditsByUser(@Param("userId") String userId);

    int updateStatus(@Param("id") String id,
                     @Param("userId") String userId,
                     @Param("status") AgentRunStatus status);

    int updateStatusWithTtl(@Param("id") String id,
                            @Param("userId") String userId,
                            @Param("status") AgentRunStatus status,
                            @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);

    int updatePlanJson(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("planJson") String planJson);

    int updateExecutionCheckpoint(@Param("id") String id,
                                  @Param("userId") String userId,
                                  @Param("executionCheckpointJson") String executionCheckpointJson);

    /**
     * 列出服务启动前遗留、可能需要恢复的 Run。调用者必须再逐条校验 Plan/checkpoint，
     * 本查询只负责有界发现，不把人工暂停 WAITING 或终态带入恢复链。
     */
    List<AgentRun> listStartupRecoveryCandidates(
            @Param("startedBefore") OffsetDateTime startedBefore,
            @Param("limit") int limit);

    /**
     * 单实例启动扫描的窄 CAS：状态和 restartAttempt 同时匹配才取得本次恢复权。
     * 当前不提供多实例租约；多实例部署必须关闭启动恢复或升级所有权协议。
     */
    int claimStartupRestart(@Param("id") String id,
                            @Param("expectedStatus") AgentRunStatus expectedStatus,
                            @Param("expectedRestartAttempt") int expectedRestartAttempt,
                            @Param("maxRestartAttempts") int maxRestartAttempts);

    /** CANCELING 遗留记录只收口到 CANCELED，不重新进入执行器。 */
    int completeStartupCancellation(@Param("id") String id);

    /** 校验失败或达到自动重启上限时，按当前状态原子写成可见失败。 */
    int failStartupRecovery(@Param("id") String id,
                            @Param("expectedStatus") AgentRunStatus expectedStatus,
                            @Param("lastError") String lastError);

    int updateExt(@Param("id") String id,
                  @Param("userId") String userId,
                  @Param("ext") String ext);

    int updateSnapshot(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("status") AgentRunStatus status,
                       @Param("snapshotJson") String snapshotJson,
                       @Param("completed") boolean completed,
                       @Param("lastError") String lastError);

    int resetForResume(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);

    /**
     * 仅在当前 data-analysis observability 子树仍等于 expectedJson 时写入下一版。
     * expectedJson 为 null 表示该子树尚不存在；该 CAS 避免并发 recorder 丢失 attempt。
     */
    int casUpdateDataAnalysisObservability(@Param("id") String id,
                                           @Param("expectedJson") String expectedJson,
                                           @Param("nextJson") String nextJson);

    /** 高频 status 查询只读取 summary JSON，不加载 calls。 */
    String findDataAnalysisObservabilitySummaryJsonById(@Param("id") String id);

    /** result/full 查询读取完整 data-analysis observability 子树。 */
    String findDataAnalysisObservabilityJsonById(@Param("id") String id);

    /**
     * 列出处于指定终态集合、且 updated_at 大于 fromTime 的 run（polling observer 用）。
     *
     * <p>按 updated_at ASC 排序，保证先处理最早的 run；
     * workspace polling observer 拿到结果后用最大 updated_at 推进 lastSeenAt，
     * 避免重复 dump。</p>
     *
     * @param statuses 终态枚举集合（COMPLETED / PARTIAL / FAILED / CANCELED / EXPIRED）
     * @param fromTime 起点（> fromTime）
     * @param limit    单批上限
     */
    List<AgentRun> listByStatusAndUpdatedAfter(@Param("statuses") List<AgentRunStatus> statuses,
                                               @Param("fromTime") OffsetDateTime fromTime,
                                               @Param("limit") int limit);

    /** D21-B 5.2.3: 复合游标查询 (cursorTime, cursorRunId)，防止同秒超批永久漏扫。 */
    List<AgentRun> listByStatusAndUpdatedAfterComposite(
            @Param("statuses") List<AgentRunStatus> statuses,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorRunId") String cursorRunId,
            @Param("limit") int limit);

    /**
     * 根据 run ID 和用户 ID 删除指定的 Agent Run。
     * <p>
     * 说明：
     * <ul>
     *   <li>此删除为物理删除，直接从数据库移除记录</li>
     *   <li>必须同时匹配 id 和 user_id，防止用户删除他人的 run</li>
     * </ul>
     *
     * @param id     run 的唯一标识 ID
     * @param userId 用户 ID，用于权限校验
     * @return 实际删除的记录数（成功为 1，未找到匹配记录为 0）
     */
    int deleteByIdAndUser(@Param("id") String id, @Param("userId") String userId);

    // ===== 长工具上下文切换：durable anchor、结果接管与恢复租约 =====
    // 以下方法是跨线程、跨进程恢复的数据库仲裁面。返回 0 不是普通“没更新”，而是调用者已经失去
    // 对该 operation/status/token/version 的所有权；上层必须停止，不能靠无条件 update 强行覆盖。

    /**
     * 原子更新 tool_job_anchor_json，并以当前 Run 状态作为 Compare-And-Set 条件。
     * 更新 1 行表示当前调用者仍拥有这版状态；更新 0 行表示控制面或其他恢复者已先行变更。
     */
    int updateToolJobAnchor(@Param("id") String id,
                            @Param("toolJobAnchorJson") String toolJobAnchorJson,
                            @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 在同一条 SQL 中同时更新 anchor 与 Run 状态，避免观察者看见只完成一半的上下文切换。
     */
    int updateToolJobAnchorAndStatus(@Param("id") String id,
                                     @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                     @Param("newStatus") AgentRunStatus newStatus,
                                     @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 260818（grace round-4）：取消意图专用窄写——仅在 Run 状态与精确 operationId 仍
     * 匹配时，用 jsonb 合并只写 autoResume=false 与 runDisposition=CANCELED，绝不写回
     * 内存中的整份旧锚点。返回 0 表示 operationId 已被新工具任务替换（或状态已变），
     * 调用方必须重读当前任务重试或按既有语义失败关闭。
     */
    int persistCancelDisposition(@Param("id") String id,
                                 @Param("expectedStatus") AgentRunStatus expectedStatus,
                                 @Param("expectedOperationId") String expectedOperationId);

    /**
     * 第一次 PREPARING dispatch 只允许占用空 anchor。
     * 这是长工具 operation 的最初所有权 CAS，可阻止同一 Run 的并发工具调用互相覆盖恢复坐标。
     */
    int claimPreparingToolJobAnchor(@Param("id") String id,
                                    @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                    @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 恢复 worker 的第二次 dispatch 只允许替换自己已经消费的 LAUNCHING handoff。
     * token/version/resultConsumed 共同防止旧 launcher 覆盖新的工具任务。
     */
    int claimPreparingToolJobAnchorFromResume(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion);

    /** 仅替换仍由 expectedOperationId 拥有的活跃 dispatch，拒绝旧 operation 的迟到写入。 */
    int updateActiveToolJobAnchor(@Param("id") String id,
                                  @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                  @Param("expectedStatus") AgentRunStatus expectedStatus,
                                  @Param("expectedOperationId") String expectedOperationId);

    /**
     * DAG blocking live worker 的 owner/lease fenced 写入口。
     * 只接受当前 NO_RESUME、owner 与精确旧 lease 均匹配且旧 lease 尚未过期的写入。
     */
    int updateLiveDagBlockingToolJobAnchor(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedLeaseUntil") String expectedLeaseUntil);

    /**
     * 仅当前未过期 DAG PREPARING owner 可把被权威证明未创建的任务推进到 durable ABORTING。
     */
    int beginLiveDagBlockingPreparingAbort(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedLeaseUntil") String expectedLeaseUntil);

    /**
     * 幂等容量释放后，先把 ABORTING（或租约已过期的 CLEARING）推进到独占 CLEARING。
     * 只有赢得该 CAS 的恢复者可以清理 Redis 派生索引。
     */
    int claimLiveDagBlockingPreparingAbortCleanup(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedLeaseUntil") String expectedLeaseUntil);

    /**
     * Redis 清理完成后，按 operation/owner/lease/CLEARING disposition 清除 durable abort anchor。
     */
    int completeLiveDagBlockingPreparingAbort(
            @Param("id") String id,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedLeaseUntil") String expectedLeaseUntil);

    /**
     * 仅对当前 operation 原子写入下一版 anchor 并切换 Run 状态。
     * Python 工具完成 PENDING handoff 时用它把内存执行权转交给 WAITING_TOOL_JOB durable 状态。
     */
    int updateToolJobAnchorAndStatusByOperation(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("newStatus") AgentRunStatus newStatus,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId);

    /**
     * 260818：CANCELED 终态收口专用。expectedStatus 同时接受 WAITING_TOOL_JOB（正常
     * 后台工具取消）与 EXECUTING（取消落在 markHandoffAccepted 已恢复执行之后——批次
     * 20260818-182948 两个 Run 因此永久 EXECUTING 并触发 finalizer 5s / resume 60s
     * 双循环）。operationId 栅栏保证旧 finalizer 不能覆盖已被第二次长工具替换的新 anchor。
     */
    int cancelToolJobAnchorFromStatuses(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("newStatus") AgentRunStatus newStatus,
            @Param("expectedOperationId") String expectedOperationId);

    /**
     * 260819：终态 Run 残留取消锚点的兜底收口。Run 已被其他写入方落进任意业务终态
     * （FAILED/CANCELED/COMPLETED/PARTIAL/EXPIRED）后，cancelToolJobAnchorFromStatuses
     * 永远 0 行，finalizer 每 5s 重试形成告警循环。本语句只清空残留锚点，不改写已落
     * 的业务终态。WHERE 完整栅栏：终态 status 集合 + operationId 精确匹配 +
     * runDisposition='CANCELED' + 显式 autoResume=false + finalizerStep 已达 EVENT
     * 及之后（ENVELOPE/RELEASE/USAGE/EVENT 均已落库），步骤安全不依赖调用方内存对象。
     */
    int closeResidualCanceledAnchorOnTerminalRun(
            @Param("id") String id,
            @Param("expectedOperationId") String expectedOperationId);

    /** 只清理仍属于指定 operation 的活跃 anchor，防止旧清理动作删除新一轮工具上下文。 */
    int clearActiveToolJobAnchor(@Param("id") String id,
                                 @Param("expectedStatus") AgentRunStatus expectedStatus,
                                 @Param("expectedOperationId") String expectedOperationId);

    /**
     * 只有租约已经过期且 operation/owner/disposition 仍匹配时，才把在线 DAG worker
     * 原子提升为 cleanup-only。状态必须仍为 EXECUTING，防止恢复者覆盖新的控制结果。
     */
    int promoteExpiredDagBlockingWorkerLost(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId);

    /**
     * cleanup-only 中间步骤允许跨 EXECUTING/FAILED/CANCELED 重入，但始终绑定
     * operation、原 blocking owner、worker-lost disposition 和 autoResume=false。
     */
    int updateDagCleanupToolJobAnchor(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId);

    /**
     * PREPARING cleanup 的专用状态 CAS。远端解析结果或 retry 时间只能在数据库当前
     * 仍是同一 PREPARING dispatch 时写入，不能覆盖另一恢复者已推进的 ATTACHED/TERMINAL。
     */
    int updateDagCleanupPreparingToolJobAnchor(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedRequestFingerprint") String expectedRequestFingerprint);

    /**
     * cleanup-only 的终态证明全部落地后清空 anchor。EXECUTING 转为 FAILED；
     * 已经 FAILED/CANCELED 的 Run 保留原 status、snapshot 和 last_error。
     */
    int completeDagCleanupAndClearToolJobAnchor(
            @Param("id") String id,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("lastError") String lastError);

    /**
     * 同步返回后的窄清理：除 status/operation 所有权外，还要求 terminal、released 和 usage proof。
     */
    int clearSynchronouslyCompletedToolJobAnchor(
            @Param("id") String id,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId);

    /**
     * DAG blocking 同步返回后的窄清理。除终态证明外，还绑定进程 owner、
     * 精确未过期 lease 和 durable logical-terminal event。
     */
    int clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
            @Param("id") String id,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedLeaseUntil") String expectedLeaseUntil);

    /**
     * 完整 checkpoint 写失败后的白名单补偿写入：只登记失败身份与错误，不覆盖 terminal/reservation。
     * startup recovery 可据此继续收口；调用方不能把失败误当成已经安全释放 worker。
     */
    int markToolJobCheckpointFailed(@Param("id") String id,
                                    @Param("operationId") String operationId,
                                    @Param("toolCallId") String toolCallId,
                                    @Param("attempt") int attempt,
                                    @Param("taskId") String taskId,
                                    @Param("checkpointVersion") int checkpointVersion,
                                    @Param("finalizerError") String finalizerError);

    int markToolJobCheckpointFailurePending(@Param("id") String id,
                                            @Param("operationId") String operationId,
                                            @Param("toolCallId") String toolCallId,
                                            @Param("attempt") int attempt,
                                            @Param("taskId") String taskId,
                                            @Param("checkpointVersion") int checkpointVersion,
                                            @Param("marker") String marker);

    int clearToolJobCheckpointFailurePending(@Param("id") String id,
                                             @Param("marker") String marker);

    /**
     * 条件更新 Run 状态：只有当前状态等于 expectedStatus 时才更新。
     * 返回 1 表示获得状态变更权；0 表示其他 finalizer、控制请求或恢复者已经获胜。
     */
    int casUpdateStatus(@Param("id") String id,
                        @Param("newStatus") AgentRunStatus newStatus,
                        @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 列出存在活跃 tool job anchor 的 Run，供 reconciler 周期补扫。
     * 这使 terminal webhook 丢失、Redis 丢键或进程重启后仍能从数据库重新进入收口链。
     */
    List<AgentRun> listActiveToolJobAnchors(@Param("limit") int limit);

    /**
     * 列出 RECEIVED+READY，以及 launcher lease 已过期的 RECEIVED/EXECUTING+LAUNCHING Run。
     * 活跃 lease 不进入扫描结果，避免多个实例反复提交同一恢复 worker。
     */
    List<AgentRun> listResumeReadyAnchors(@Param("limit") int limit);

    /**
     * 发现 CAS_STATUS→RESUME_READY 半状态：RECEIVED + finalizerStep=CAS_STATUS + resumeState 空。
     * 只用于发现，不承担并发正确性。推进必须走 {@link #promoteCasStatusToResumeReady}。
     */
    List<AgentRun> listStuckAtCasStatusAnchors(@Param("limit") int limit);

    /**
     * 原子推进 CAS_STATUS→RESUME_READY。
     * WHERE 绑定 RECEIVED + finalizerStep=CAS_STATUS + resumeState 空
     * + operationId + toolCallId + attempt + taskId + expectedLeaseVersion。
     * SET 只合并写 resumeState/token/leaseVersion/claimedAt/finalizerStep，不覆盖其余字段。
     * claimedAt 使用数据库 CURRENT_TIMESTAMP，leaseVersion 在 DB 内自增。
     * @return 更新行数（1=胜者，0=并发输家或条件不满足）
     */
    int promoteCasStatusToResumeReady(
            @Param("id") String id,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("expectedToolCallId") String expectedToolCallId,
            @Param("expectedAttempt") int expectedAttempt,
            @Param("expectedTaskId") String expectedTaskId,
            @Param("expectedResumeLeaseVersion") long expectedResumeLeaseVersion,
            @Param("newResumeToken") String newResumeToken);

    /**
     * 原子 CAS 更新 resumeState，同时约束 Run 状态、旧 state、token 与 lease version。
     * READY→LAUNCHING 和过期 LAUNCHING→READY 都经此入口，防止双 launch 与旧租约回滚新声明。
     */
    int casUpdateAnchorResumeState(@Param("id") String id,
                                   @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                   @Param("expectedStatus") AgentRunStatus expectedStatus,
                                   @Param("expectedResumeState") String expectedResumeState,
                                   @Param("expectedResumeToken") String expectedResumeToken,
                                   @Param("expectedLeaseVersion") long expectedLeaseVersion);

    /** 同一条 CAS 同时推进恢复 anchor 与 Run 状态，避免已恢复 worker 长时间停在 RECEIVED。 */
    int casUpdateAnchorResumeStateAndStatus(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("newStatus") AgentRunStatus newStatus,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedResumeState") String expectedResumeState,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion);

    /** READY→LAUNCHING 的持久化 launcher claim；owner 与 lease 使用同一条数据库 CAS 写入。 */
    int claimResumeLauncher(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("newStatus") AgentRunStatus newStatus,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("launcherOwnerId") String launcherOwnerId,
            @Param("leaseSeconds") long leaseSeconds);

    /** 只有数据库确认 launcher lease 已过期时，新的实例才能原子旋转 token/version/owner。 */
    int takeoverExpiredResumeLauncher(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("expectedLauncherOwnerId") String expectedLauncherOwnerId,
            @Param("launcherOwnerId") String launcherOwnerId,
            @Param("leaseSeconds") long leaseSeconds,
            @Param("legacyStaleSeconds") long legacyStaleSeconds);

    /** 仅当前 owner/token/version 可以窄更新 launcher lease，不能覆盖 handoff/checkpoint 字段。 */
    int heartbeatResumeLauncher(
            @Param("id") String id,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("expectedLauncherOwnerId") String expectedLauncherOwnerId,
            @Param("leaseSeconds") long leaseSeconds);

    /** 首次消费终态时，在未过期 launcher lease 下原子写 accepted handoff 并恢复 EXECUTING。 */
    int acceptResumeHandoff(
            @Param("id") String id,
            @Param("toolJobAnchorJson") String toolJobAnchorJson,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("expectedLauncherOwnerId") String expectedLauncherOwnerId,
            @Param("leaseSeconds") long leaseSeconds);

    /**
     * resumed pipeline 的唯一终态写入口。plan/status/snapshot 在同一条 UPDATE 中写入，
     * 并要求 accepted LAUNCHING handoff 的 token/version/owner/未过期 lease 仍匹配。
     */
    int updateResumedTerminal(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("status") AgentRunStatus status,
            @Param("planJson") String planJson,
            @Param("snapshotJson") String snapshotJson,
            @Param("completed") boolean completed,
            @Param("lastError") String lastError,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("expectedLauncherOwnerId") String expectedLauncherOwnerId);

    /** 终态已落稳后，仅清理精确 accepted handoff。 */
    int clearAcceptedResumeHandoff(
            @Param("id") String id,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedLeaseVersion") long expectedLeaseVersion,
            @Param("expectedLauncherOwnerId") String expectedLauncherOwnerId);

    /**
     * 原子合并检查点白名单字段，并保留 reservation、terminal、finalizer 等并发子树。
     * WHERE 同时绑定 Run 状态、operationId、toolCallId、attempt、taskId 与 checkpointVersion；
     * 成功时在数据库内递增版本，避免旧 pipeline 覆盖 finalizer 已写入的结果或新检查点。
     */
    int updateToolJobCheckpoint(@Param("id") String id,
                                 @Param("expectedStatus") AgentRunStatus expectedStatus,
                                 @Param("expectedOperationId") String expectedOperationId,
                                 @Param("expectedToolCallId") String expectedToolCallId,
                                 @Param("expectedAttempt") int expectedAttempt,
                                 @Param("expectedTaskId") String expectedTaskId,
                                 @Param("expectedCheckpointVersion") int expectedCheckpointVersion,
                                 @Param("todoId") String todoId,
                                 @Param("sequence") int sequence,
                                 @Param("completedTodosJson") String completedTodosJson,
                                 @Param("datasetSnapshotJson") String datasetSnapshotJson,
                                 @Param("datasetSnapshotDigest") String datasetSnapshotDigest,
                                 @Param("datasetRefsJson") String datasetRefsJson,
                                 @Param("toolCallsUsed") int toolCallsUsed,
                                 @Param("estimateJson") String estimateJson);

    /**
     * 仅在 resumeState、resumeToken 与 resumeLeaseVersion 全部匹配时清空 anchor。
     * 恢复 pipeline 必须先完成 durable handoff，再用自己持有的精确租约清理；旧 consumer 即使迟到，
     * 也无法删除已经被新 token/version 重新声明的上下文。这里刻意不提供无 token 的清理入口。
     */
    int clearToolJobAnchorWithToken(@Param("id") String id,
                                    @Param("expectedResumeState") String expectedResumeState,
                                    @Param("expectedToken") String expectedToken,
                                    @Param("expectedLeaseVersion") long expectedLeaseVersion);
}
