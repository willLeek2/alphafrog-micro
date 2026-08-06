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
}
