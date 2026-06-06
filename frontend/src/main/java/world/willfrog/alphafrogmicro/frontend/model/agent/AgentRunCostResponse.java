package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentRunCostResponse(
        String id,
        Double totalCost,
        Double upstreamInferenceCost,
        Double cacheDiscount,
        Integer costedCallCount,
        Integer totalCallCount,
        Boolean complete,
        String currency,
        String source,
        String updatedAt,
        Boolean persisted,
        List<CallCost> calls
) {
    public record CallCost(
            String traceId,
            String generationId,
            String phase,
            String todoId,
            String endpoint,
            String model,
            Double actualCost,
            Double upstreamInferenceCost,
            Double cacheDiscount,
            Boolean isByok,
            Long startedAtMs,
            Long completedAtMs,
            String source
    ) {
    }
}
