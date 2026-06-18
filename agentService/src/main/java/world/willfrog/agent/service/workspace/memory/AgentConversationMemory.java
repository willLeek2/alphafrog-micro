package world.willfrog.agent.service.workspace.memory;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Agent 多轮对话 memory 实体。
 *
 * <p>对应表：alphafrog_agent_conversation_memory
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>memory_type: fact / preference / open_issue / correction</li>
 *   <li>conversation_scope: {tenant_id}_{user_id} 组合键，跨 run 串联同一会话上下文的 memory</li>
 *   <li>source_run_id + source_message_seq_start/end: 追溯 memory 来自哪条 run / 哪几轮消息</li>
 *   <li>supersedes_memory_id: 该 memory 取代的旧 memory id（用于纠错 / 更新场景）</li>
 *   <li>status: active / superseded / deleted（不真删，保留审计）</li>
 *   <li>verification_status: pending / auto_extracted / verified</li>
 *   <li>embedding_status: pending / done / failed</li>
 * </ul>
 *
 * <p>v0 worker 只填入核心 9 个字段；其余字段（expires_at / confidence / supersedes 等）保留 NULL / 默认。
 *
 * @author wang
 */
@Data
@NoArgsConstructor
public class AgentConversationMemory {

    /** 主键 ID */
    private Long id;

    /** 租户 ID */
    private String tenantId;

    /** 用户 ID */
    private String userId;

    /** 会话 scope，跨 run 串联同一上下文（{tenant_id}_{user_id}） */
    private String conversationScope;

    /** memory 类型：fact / preference / open_issue / correction */
    private String memoryType;

    /** memory 内容（抽取出的语句片段） */
    private String content;

    /** 来源 run ID（外键，run 删除时级联） */
    private String sourceRunId;

    /** 来源 run 内的消息起始 seq */
    private Integer sourceMessageSeqStart;

    /** 来源 run 内的消息结束 seq */
    private Integer sourceMessageSeqEnd;

    /** 置信度（v0 固定 1.0；模型参与后再启用） */
    private BigDecimal confidence;

    /** 验证状态：pending / auto_extracted / verified */
    private String verificationStatus;

    /** 该 memory 取代的旧 memory id */
    private Long supersedesMemoryId;

    /** 过期时间（NULL 表示永不过期） */
    private OffsetDateTime expiresAt;

    /** 状态：active / superseded / deleted */
    private String status;

    /** embedding 状态：pending / done / failed */
    private String embeddingStatus;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
