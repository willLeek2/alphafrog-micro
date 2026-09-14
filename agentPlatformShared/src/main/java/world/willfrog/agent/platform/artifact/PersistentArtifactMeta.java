package world.willfrog.agent.platform.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersistentArtifactMeta {
    private String artifactId;
    private String artifactType;
    private String runId;
    private String userId;
    private String logicalId;
    private String displayName;
    private String path;
    private String contentHash;
    private Long sizeBytes;
    private Long createdAtMillis;
    private Long lastAccessAtMillis;
    private Long expiresAtMillis;
    private Long ttlHours;
    private Boolean external;
    private Boolean cleanupPath;
    /**
     * D22-5.1.3 第五轮：该制品是否经幂等认领注册（identity hash 是否应有它的身份项）。
     * 读取 touch 的原子修复只在该标记为 true 时才允许以 HSETNX 补建丢失的身份项——
     * 非幂等制品（如逐条 rawRef）本就没有身份项，绝不允许 touch 顺手创建一个，
     * 否则后来同身份的幂等认领会错误采纳它。旧 meta JSON 无该字段 → null → 按
     * 非幂等处理（不补建），与历史行为一致。
     */
    private Boolean idempotent;
}
