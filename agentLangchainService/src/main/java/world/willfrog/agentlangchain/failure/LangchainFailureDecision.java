package world.willfrog.agentlangchain.failure;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
public class LangchainFailureDecision {

    private AgentRunStatus runStatus;
    private String eventType;
    private String reason;
    private LangchainFailureCategory category;
    private boolean retryable;
    private String observabilityFailureType;

    // Stable failure classification fields (Phase 3.1)
    private String failureCategory;
    private String failureSubCategory;
    private String dimension;
    private Boolean partial;
    private List<String> providerOrder;
    private String model;
    private String endpoint;
    private String errorCode;
    private Long actual;
    private Long limit;
    private Double ratio;
    private String rawMessage;

    @Builder.Default
    private Map<String, Object> eventPayload = new LinkedHashMap<>();
}
