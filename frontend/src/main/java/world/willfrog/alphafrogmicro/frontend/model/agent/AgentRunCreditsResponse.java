package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentRunCreditsResponse(
        String runId,
        String ownerUserId,
        String totalCredits,
        String currency,
        List<CallRecord> records,
        SettlementSummary summary,
        String updatedAt
) {
    public record CallRecord(
            String callId,
            String phase,
            String todoId,
            String endpoint,
            String model,
            String costSource,
            String currency,
            String costAmount,
            String creditDelta,
            Integer settlementAttempt,
            String settlementStatus,
            String reason,
            String createdAt
    ) {
    }

    public record SettlementSummary(
            Integer immediateCount,
            Integer delayedCount,
            Integer pendingCount,
            Integer missingCount,
            Integer totalCallCount,
            String currency,
            String totalCredits,
            String lastSettlementAt
    ) {
    }
}
