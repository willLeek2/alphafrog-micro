package world.willfrog.agent.platform.credit;

import lombok.RequiredArgsConstructor;
import world.willfrog.agent.platform.service.AgentModelCatalogService;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Charges one LLM call using agent-llm {@code baseRate} as credit multiplier (1 credit = 1 USD).
 */
@RequiredArgsConstructor
public class PerCallEndpointCostAdapter implements EndpointCostAdapter {

    private final String endpointId;
    private final AgentModelCatalogService modelCatalogService;

    @Override
    public String endpointId() {
        return endpointId;
    }

    @Override
    public boolean supportsCostFetch() {
        return false;
    }

    @Override
    public BigDecimal fallbackPerCallRate(LlmCallBillingContext call) {
        double rate = modelCatalogService.resolveBaseRate(call.getModel());
        return BigDecimal.valueOf(rate).setScale(6, RoundingMode.HALF_UP);
    }

    @Override
    public String costCurrency() {
        return "USD";
    }

    @Override
    public CostSource costSource() {
        return CostSource.PER_CALL;
    }

    @Override
    public CostSettlementQuote quote(LlmCallBillingContext call, int settlementAttempt) {
        if (settlementAttempt > 1) {
            return emptyQuote(call, settlementAttempt);
        }
        BigDecimal creditDelta = fallbackPerCallRate(call);
        return CostSettlementQuote.builder()
                .runId(call.getRunId())
                .callId(call.getCallId())
                .endpoint(call.getEndpoint())
                .model(call.getModel())
                .billingMode(BillingMode.PER_CALL)
                .costSource(CostSource.PER_CALL)
                .currency(costCurrency())
                .costAmount(creditDelta)
                .creditDelta(creditDelta)
                .costAvailable(true)
                .needsDelayedRetry(false)
                .settlementAttempt(settlementAttempt)
                .build();
    }

    private static CostSettlementQuote emptyQuote(LlmCallBillingContext call, int attempt) {
        return CostSettlementQuote.builder()
                .runId(call.getRunId())
                .callId(call.getCallId())
                .endpoint(call.getEndpoint())
                .model(call.getModel())
                .billingMode(BillingMode.PER_CALL)
                .costSource(CostSource.PER_CALL)
                .currency("USD")
                .costAmount(BigDecimal.ZERO)
                .creditDelta(BigDecimal.ZERO)
                .costAvailable(false)
                .needsDelayedRetry(false)
                .settlementAttempt(attempt)
                .build();
    }
}
