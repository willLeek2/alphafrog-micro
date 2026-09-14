package world.willfrog.agent.platform.credit;

import java.math.BigDecimal;

/**
 * Endpoint-specific credit cost adapter for agent run settlement.
 */
public interface EndpointCostAdapter {

    String endpointId();

    boolean supportsCostFetch();

    BigDecimal fallbackPerCallRate(LlmCallBillingContext call);

    String costCurrency();

    CostSource costSource();

    /**
     * @param settlementAttempt 1 = immediate settlement, 2 = delayed retry
     */
    CostSettlementQuote quote(LlmCallBillingContext call, int settlementAttempt);
}
