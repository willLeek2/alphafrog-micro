package world.willfrog.agent.platform.credit;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Settlement quote for a single LLM call, consumed by {@code AgentRunCreditSettlementService}.
 */
@Value
@Builder
public class CostSettlementQuote {
    String runId;
    String callId;
    String endpoint;
    String model;
    BillingMode billingMode;
    CostSource costSource;
    String currency;
    BigDecimal costAmount;
    BigDecimal creditDelta;
    boolean costAvailable;
    boolean needsDelayedRetry;
    int settlementAttempt;

    public static CostSettlementQuote zeroPendingRetry(LlmCallBillingContext call, int attempt) {
        return CostSettlementQuote.builder()
                .runId(call.getRunId())
                .callId(call.getCallId())
                .endpoint(call.getEndpoint())
                .model(call.getModel())
                .billingMode(BillingMode.ACTUAL_COST)
                .costSource(CostSource.OPENROUTER_ACTUAL)
                .currency("USD")
                .costAmount(BigDecimal.ZERO)
                .creditDelta(BigDecimal.ZERO)
                .costAvailable(false)
                .needsDelayedRetry(true)
                .settlementAttempt(attempt)
                .build();
    }
}
