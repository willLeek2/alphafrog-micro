package world.willfrog.agent.platform.credit;

import lombok.RequiredArgsConstructor;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.OpenRouterCostService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@RequiredArgsConstructor
public class OpenRouterEndpointCostAdapter implements EndpointCostAdapter {

    public static final String ENDPOINT_ID = "openrouter";

    private final OpenRouterCostService openRouterCostService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties properties;
    private final AgentModelCatalogService modelCatalogService;

    @Override
    public String endpointId() {
        return ENDPOINT_ID;
    }

    @Override
    public boolean supportsCostFetch() {
        return true;
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
        return CostSource.OPENROUTER_ACTUAL;
    }

    @Override
    public CostSettlementQuote quote(LlmCallBillingContext call, int settlementAttempt) {
        Optional<BigDecimal> billableCost = resolveBillableCostUsd(call);
        if (billableCost.isPresent()) {
            BigDecimal usd = billableCost.get();
            return CostSettlementQuote.builder()
                    .runId(call.getRunId())
                    .callId(call.getCallId())
                    .endpoint(call.getEndpoint())
                    .model(call.getModel())
                    .billingMode(BillingMode.ACTUAL_COST)
                    .costSource(CostSource.OPENROUTER_ACTUAL)
                    .currency(costCurrency())
                    .costAmount(usd)
                    .creditDelta(usd)
                    .costAvailable(true)
                    .needsDelayedRetry(false)
                    .settlementAttempt(settlementAttempt)
                    .build();
        }

        if (settlementAttempt <= 1) {
            return CostSettlementQuote.zeroPendingRetry(call, settlementAttempt);
        }

        BigDecimal fallback = fallbackPerCallRate(call);
        if (shouldFallbackToPerCall()) {
            return CostSettlementQuote.builder()
                    .runId(call.getRunId())
                    .callId(call.getCallId())
                    .endpoint(call.getEndpoint())
                    .model(call.getModel())
                    .billingMode(BillingMode.PER_CALL)
                    .costSource(CostSource.OPENROUTER_FALLBACK)
                    .currency(costCurrency())
                    .costAmount(fallback)
                    .creditDelta(fallback)
                    .costAvailable(true)
                    .needsDelayedRetry(false)
                    .settlementAttempt(settlementAttempt)
                    .build();
        }

        return CostSettlementQuote.builder()
                .runId(call.getRunId())
                .callId(call.getCallId())
                .endpoint(call.getEndpoint())
                .model(call.getModel())
                .billingMode(BillingMode.ACTUAL_COST)
                .costSource(CostSource.OPENROUTER_ACTUAL)
                .currency(costCurrency())
                .costAmount(BigDecimal.ZERO)
                .creditDelta(BigDecimal.ZERO)
                .costAvailable(false)
                .needsDelayedRetry(false)
                .settlementAttempt(settlementAttempt)
                .build();
    }

    /**
     * Resolve billable USD cost for an OpenRouter call.
     * Priority (per frog): upstream_inference_cost > 0, then actual/total_cost > 0.
     * This handles BYOK/provider-order scenarios where total_cost is 0 but upstream cost exists.
     */
    private Optional<BigDecimal> resolveBillableCostUsd(LlmCallBillingContext call) {
        if (call.getUpstreamCostUsd() != null && call.getUpstreamCostUsd() > 0D) {
            return Optional.of(BigDecimal.valueOf(call.getUpstreamCostUsd()).setScale(6, RoundingMode.HALF_UP));
        }
        if (call.getActualCostUsd() != null && call.getActualCostUsd() > 0D) {
            return Optional.of(BigDecimal.valueOf(call.getActualCostUsd()).setScale(6, RoundingMode.HALF_UP));
        }
        String generationId = call.getGenerationId();
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        OpenRouterCredentials credentials = resolveOpenRouterCredentials();
        return openRouterCostService.fetchBillableCostUsd(generationId, credentials.apiKey(), credentials.baseUrl());
    }

    private boolean shouldFallbackToPerCall() {
        return false;
    }

    private OpenRouterCredentials resolveOpenRouterCredentials() {
        AgentLlmProperties.Endpoint endpoint = localConfigLoader.current()
                .map(AgentLlmProperties::getEndpoints)
                .map(endpoints -> endpoints.get(ENDPOINT_ID))
                .orElse(null);
        if (endpoint == null && properties.getEndpoints() != null) {
            endpoint = properties.getEndpoints().get(ENDPOINT_ID);
        }
        String apiKey = endpoint != null ? nvl(endpoint.getApiKey()) : "";
        String baseUrl = endpoint != null && !nvl(endpoint.getBaseUrl()).isBlank()
                ? endpoint.getBaseUrl().trim()
                : "https://openrouter.ai/api/v1";
        return new OpenRouterCredentials(apiKey, baseUrl);
    }

    private static String nvl(String value) {
        return value == null ? "" : value.trim();
    }

    private record OpenRouterCredentials(String apiKey, String baseUrl) {
    }
}
