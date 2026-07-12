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

    // ===== Tool Job Anchor =====

    /**
     * 原子更新 tool_job_anchor_json，带前置状态条件（Compare-And-Set）。
     * 更新 1 行表示当前调用者获得更新权；更新 0 行表示状态已变更。
     */
    int updateToolJobAnchor(@Param("id") String id,
                            @Param("toolJobAnchorJson") String toolJobAnchorJson,
                            @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 原子更新 tool_job_anchor_json 和 status，带前置状态条件。
     */
    int updateToolJobAnchorAndStatus(@Param("id") String id,
                                     @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                     @Param("newStatus") AgentRunStatus newStatus,
                                     @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 条件更新 status（CAS）：只有当前状态等于 expectedStatus 时才更新。
     * 返回 1 表示获得变更权，0 表示已被其他流程变更。
     */
    int casUpdateStatus(@Param("id") String id,
                        @Param("newStatus") AgentRunStatus newStatus,
                        @Param("expectedStatus") AgentRunStatus expectedStatus);

    /**
     * 列出存在活跃 tool job anchor 的 run，用于 reconciler 周期补扫。
     * 返回非终态 + WAITING_TOOL_JOB + RESULT_FETCH_PENDING 的 run。
     */
    List<AgentRun> listActiveToolJobAnchors(@Param("limit") int limit);

    /**
     * 列出 status=RECEIVED 且 tool_job_anchor_json 中 resumeState 为 READY 或 LAUNCHING 的 run，
     * 用于启动恢复和 reconciler 扫描在 CAS 之后但 launch 之前崩溃的 run。
     */
    List<AgentRun> listResumeReadyAnchors(@Param("limit") int limit);

    /**
     * 原子 CAS 更新 resmeState：同时约束 status 和 JSON 内的 resumeState 字段。
     * 防止两个进程同时读取 READY 后双 launch。
     */
    int casUpdateAnchorResumeState(@Param("id") String id,
                                   @Param("toolJobAnchorJson") String toolJobAnchorJson,
                                   @Param("expectedStatus") AgentRunStatus expectedStatus,
                                   @Param("expectedResumeState") String expectedResumeState);

    /** Clear tool_job_anchor_json to {} so the partial index stops matching. */
    int clearToolJobAnchor(@Param("id") String id);

    /**
     * Token-gated clear: only clears the anchor if the resumeToken matches.
     * Returns 1 if cleared, 0 if token mismatch (another consumer already cleared).
     */
    int clearToolJobAnchorWithToken(@Param("id") String id, @Param("expectedToken") String expectedToken);
}
