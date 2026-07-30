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
     * 第一次 PREPARING dispatch 只允许占用空 anchor。
     * 这是长工具 operation 的最初所有权 CAS，可阻止同一 Run 的并发工具调用互相覆盖恢复坐标。
     */
    int claimPreparingToolJobAnchor(@Param("id") String id,
                                    @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                    @Param("expectedStatus") AgentRunStatus expectedStatus);

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
     * 幂等容量释放后，按 operation/owner/lease/ABORTING disposition 清除 durable abort anchor。
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

    /** 只清理仍属于指定 operation 的活跃 anchor，防止旧清理动作删除新一轮工具上下文。 */
    int clearActiveToolJobAnchor(@Param("id") String id,
                                 @Param("expectedStatus") AgentRunStatus expectedStatus,
                                 @Param("expectedOperationId") String expectedOperationId);

    /**
     * DAG blocking worker 在进程重启后已经不可恢复。只有 cleanup-only disposition、
     * {@code autoResume=false}、EXECUTING 状态和 operationId 全部匹配时，才原子地
     * 把 Run 标为 FAILED、保存诊断并清空 active anchor。
     */
    int failDagBlockingAndClearToolJobAnchor(
            @Param("id") String id,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId,
            @Param("lastError") String lastError);

    /**
     * 同步返回后的窄清理：除 status/operation 所有权外，还要求 terminal、released 和 usage proof。
     */
    int clearSynchronouslyCompletedToolJobAnchor(
            @Param("id") String id,
            @Param("expectedStatus") AgentRunStatus expectedStatus,
            @Param("expectedOperationId") String expectedOperationId);

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
     * 列出 status=RECEIVED 且 anchor 中 resumeState 为 READY 或 LAUNCHING 的 Run。
     * 用于扫描“结果已接管但尚未 launch”以及“声明 LAUNCHING 后进程崩溃”的两个断点窗口。
     */
    List<AgentRun> listResumeReadyAnchors(@Param("limit") int limit);

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
