package world.willfrog.agent.platform.finance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** One validated resolver suggestion persisted as an immutable snapshot row. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FinanceMethodResolution {
    private Long id;
    private String runId;
    private String resolverToolCallId;
    private String todoId;
    private String methodId;
    private String methodVersion;
    private String specDigest;
    private String catalogDigest;
    private String resolverSchemaVersion;
    private String resolverPromptVersion;
    private String modelRouteJson;
    private String matchReason;
    private String clarificationJson;
    private String targetEnvironmentId;
    private String targetPackageApiJson;
    private String resolutionPayloadJson;
    private String resolutionContentDigest;
    private OffsetDateTime createdAt;
}
