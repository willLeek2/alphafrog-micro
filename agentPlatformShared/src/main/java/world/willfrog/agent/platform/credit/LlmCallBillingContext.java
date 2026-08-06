package world.willfrog.agent.platform.credit;

import lombok.Builder;
import lombok.Value;

/**
 * Minimal LLM call facts required by endpoint cost adapters during settlement.
 */
@Value
@Builder
public class LlmCallBillingContext {
    String runId;
    String callId;
    String endpoint;
    String model;
    String generationId;
    Double actualCostUsd;
    Double upstreamCostUsd;
    boolean hasError;
}
