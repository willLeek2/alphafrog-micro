package world.willfrog.agent.platform.finance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Persisted finance record/audit row. Internal fields are never model output. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FinanceMetricRecord {
    private Long id;
    private String recordId;
    private String runId;
    private String todoId;
    private String executePythonToolCallId;
    private Integer recordIndex;
    private String rawDigest;
    private String rawPayload;
    private String sourceResolverToolCallId;
    private String methodId;
    private String methodVersion;
    private String specDigest;
    private String valueJson;
    private String unit;
    private String parametersJson;
    private String inputRefsJson;
    private String checksJson;
    private String formulaDescription;
    private String declaredEvidence;
    private String effectiveInternalEvidence;
    private String actualEnvironmentId;
    private Boolean renderable;
    private String validationErrorJson;
    private OffsetDateTime createdAt;
}
