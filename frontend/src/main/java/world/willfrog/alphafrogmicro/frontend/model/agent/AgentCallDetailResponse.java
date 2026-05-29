package world.willfrog.alphafrogmicro.frontend.model.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Safe lazy-load detail for LLM / tool calls (Step 1 — no raw HTTP / reasoning / full I/O).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentCallDetailResponse {

    public static final String KIND_AVAILABLE = "available";
    public static final String KIND_TRUNCATED = "truncated";
    public static final String KIND_UNAVAILABLE = "unavailable";
    public static final String KIND_EXPIRED = "expired";

    public static final String SOURCE_OBSERVABILITY = "observability_snapshot";

    private String type;
    private String detailKind;
    private String source;
    private String id;
    private String runId;
    private String todoId;
    private Integer todoSequence;
    private String phase;
    private String stage;
    private String time;
    private Long durationMs;
    private String status;
    private String summary;
    private DetailMetrics metrics;
    private DetailLlm llm;
    private DetailTool tool;
    private DetailLimits limits;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailMetrics {
        private Long inputTokens;
        private Long outputTokens;
        private Long totalTokens;
        private Double actualCost;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailLlm {
        private String model;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailTool {
        private String name;
        private String paramsSummary;
        private String outputPreview;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailLimits {
        private Integer previewMaxChars;
        private Boolean truncated;
    }
}
