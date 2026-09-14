package world.willfrog.externalinfo.ingestion.db;

import lombok.Data;

/**
 * RAG 记录状态更新请求体。
 *
 * <p>两类动作：
 * <ul>
 *   <li>写入 OSS object key（mark-oss-uploaded）：必填 ossKey</li>
 *   <li>标记向量化完成（mark-vectorized）：忽略 ossKey</li>
 * </ul>
 *
 * <p>docType 决定 UPDATE 目标表：
 * "announcement" → alphafrog_rag_announcement
 * "research_report" → alphafrog_rag_research_report
 */
@Data
public class RagRecordMarkRequest {

    /** 文档类型："announcement" | "research_report" */
    private String docType;

    /** 目标记录主键 id */
    private Long recordId;

    /**
     * OSS object key（仅 mark-oss-uploaded 动作需要，mark-vectorized 忽略）。
     */
    private String ossKey;
}
