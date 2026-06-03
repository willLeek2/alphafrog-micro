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
    public static final String SOURCE_CALL_DETAIL_REDIS = "call_detail_redis";

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
    /**
     * 仅当调用方传了 {@code includeThinking=true}（且调用者为 admin）时才可能为 true：
     * 表示本次 detail blob 缺失/不含 {@code reasoningText} 字段（6h TTL 过期或根本没存）。
     * 不影响 detailKind —— 整体仍为 {@code available}，仅 thinking 不可用。
     */
    private Boolean reasoningUnavailable;

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
        /**
         * 可选：thinking/reasoning 内容。仅在调用方传 {@code includeThinking=true}（且为 admin）
         * 且 Redis detail blob 包含 {@code reasoningText} 时回传；默认 null（不出 JSON）。
         * 来源：blob 的 {@code reasoningText} 字段。
         */
        private String reasoningContent;
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
