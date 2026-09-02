package world.willfrog.agent.platform.entity;

import lombok.Data;
import world.willfrog.agent.platform.model.AgentRunStatus;
import java.time.OffsetDateTime;

/**
 * Agent Run 的数据库实体。
 *
 * <p>长工具上下文切换有两个持久化支点：{@link #status} 表示调度阶段，
 * {@link #toolJobAnchorJson} 保存可恢复的精确执行上下文。二者由 mapper 的条件更新共同维护；只改其中
 * 一个会产生“状态看似可恢复但没有上下文”或“旧 anchor 被新执行误用”的分裂状态。</p>
 */
@Data
public class AgentRun {
    private String id;
    private String userId;
    /** 创建 Run 的稳定部署标识；创建后不可修改。 */
    private String deploymentId;
    /** 创建 Run 的不可变执行代际；异步领取和恢复必须同时匹配此值。 */
    private String deploymentGenerationId;
    /** 当前 Run 状态；WAITING_TOOL_JOB 表示内存 worker 已退出、但外部作业仍拥有后续恢复权。 */
    private AgentRunStatus status;
    private Integer currentStep;
    private Integer maxSteps;
    
    // JSON strings
    private String planJson;
    private String snapshotJson;
    
    private String lastError;
    private OffsetDateTime ttlExpiresAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
    private String ext; // JSON string
    /**
     * 工作流级粗粒度恢复检查点。它只描述冻结 Plan 的执行边界，不保存长工具任务身份，
     * 因此不能与 {@link #toolJobAnchorJson} 混用。
     */
    private String executionCheckpointJson;
    /** 服务重启后自动重新执行的次数；与 LLM HTTP retry、Todo retry 分开计数。 */
    private Integer restartAttempt;
    /**
     * 外部长工具的 durable anchor JSON。
     * 包含 operation/toolCall/attempt 身份、todo 坐标、已完成结果、dataset snapshot、工具预算、
     * terminal envelope、resume token/state/lease version；进程重启后仅凭数据库即可重建恢复流程。
     */
    private String toolJobAnchorJson;

    // List view metrics extracted from snapshot_json.observability.summary
    private Long durationMs;
    private Integer totalTokens;
    private Integer toolCalls;
}
