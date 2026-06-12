package world.willfrog.agent.platform.credit;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.OpenRouterCostService;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndpointCostAdapterTest {

    @Test
    void perCallAdapterUsesBaseRateAsCreditDelta() {
        AgentModelCatalogService catalog = mock(AgentModelCatalogService.class);
        when(catalog.resolveBaseRate("qwen-plus")).thenReturn(0.3D);

        PerCallEndpointCostAdapter adapter = new PerCallEndpointCostAdapter("fireworks", catalog);
        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("fireworks")
                .model("qwen-plus")
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertEquals(BillingMode.PER_CALL, quote.getBillingMode());
        assertEquals(CostSource.PER_CALL, quote.getCostSource());
        assertEquals(new BigDecimal("0.300000"), quote.getCreditDelta());
        assertTrue(quote.isCostAvailable());
        assertFalse(quote.isNeedsDelayedRetry());
    }

    @Test
    void openRouterAdapterUsesActualCostWhenPresent() {
        OpenRouterEndpointCostAdapter adapter = createOpenRouterAdapter(mock(OpenRouterCostService.class));

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("openrouter")
                .model("gpt-5")
                .actualCostUsd(0.0123D)
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertEquals(BillingMode.ACTUAL_COST, quote.getBillingMode());
        assertEquals(CostSource.OPENROUTER_ACTUAL, quote.getCostSource());
        assertEquals(new BigDecimal("0.012300"), quote.getCreditDelta());
        assertFalse(quote.isNeedsDelayedRetry());
    }

    @Test
    void openRouterAdapterRequestsDelayedRetryWhenCostMissingOnFirstAttempt() {
        OpenRouterCostService costService = mock(OpenRouterCostService.class);
        when(costService.fetchBillableCostUsd(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        OpenRouterEndpointCostAdapter adapter = createOpenRouterAdapter(costService);

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("openrouter")
                .model("gpt-5")
                .generationId("gen-abc")
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertFalse(quote.isCostAvailable());
        assertTrue(quote.isNeedsDelayedRetry());
        assertEquals(BigDecimal.ZERO, quote.getCreditDelta());
    }

    @Test
    void registryResolvesLegacyDashscopeEndpoint() {
        AgentModelCatalogService catalog = mock(AgentModelCatalogService.class);
        when(catalog.resolveBaseRate("qwen-plus")).thenReturn(0.25D);
        EndpointCostAdapterRegistry registry = new EndpointCostAdapterRegistry(
                mock(OpenRouterCostService.class),
                mock(AgentLlmLocalConfigLoader.class),
                new AgentLlmProperties(),
                catalog);

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("dashscope")
                .model("qwen-plus")
                .build();

        CostSettlementQuote quote = registry.quote("dashscope", call, 1);

        assertEquals(BillingMode.PER_CALL, quote.getBillingMode());
        assertEquals(new BigDecimal("0.250000"), quote.getCreditDelta());
        assertTrue(quote.isCostAvailable());
        assertFalse(quote.isNeedsDelayedRetry());
    }

    @Test
    void openRouterAdapterFetchesGenerationCostWhenTraceMissingActualCost() {
        OpenRouterCostService costService = mock(OpenRouterCostService.class);
        when(costService.fetchBillableCostUsd(eq("gen-abc"), anyString(), anyString()))
                .thenReturn(Optional.of(new BigDecimal("0.050000")));
        OpenRouterEndpointCostAdapter adapter = createOpenRouterAdapter(costService);

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("openrouter")
                .model("gpt-5")
                .generationId("gen-abc")
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertEquals(new BigDecimal("0.050000"), quote.getCreditDelta());
        assertTrue(quote.isCostAvailable());
    }

    @Test
    void openRouterAdapterUsesUpstreamCostWhenActualCostIsZero() {
        OpenRouterEndpointCostAdapter adapter = createOpenRouterAdapter(mock(OpenRouterCostService.class));

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("openrouter")
                .model("gpt-5")
                .actualCostUsd(0D)
                .upstreamCostUsd(0.0045588D)
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertEquals(BillingMode.ACTUAL_COST, quote.getBillingMode());
        assertEquals(CostSource.OPENROUTER_ACTUAL, quote.getCostSource());
        assertEquals(new BigDecimal("0.004559"), quote.getCreditDelta());
        assertTrue(quote.isCostAvailable());
        assertFalse(quote.isNeedsDelayedRetry());
    }

    @Test
    void openRouterAdapterPrefersUpstreamCostOverActualCost() {
        OpenRouterEndpointCostAdapter adapter = createOpenRouterAdapter(mock(OpenRouterCostService.class));

        LlmCallBillingContext call = LlmCallBillingContext.builder()
                .runId("run-1")
                .callId("call-1")
                .endpoint("openrouter")
                .model("gpt-5")
                .actualCostUsd(0.0123D)
                .upstreamCostUsd(0.05D)
                .build();

        CostSettlementQuote quote = adapter.quote(call, 1);

        assertEquals(new BigDecimal("0.050000"), quote.getCreditDelta());
    }

    private OpenRouterEndpointCostAdapter createOpenRouterAdapter(OpenRouterCostService costService) {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Endpoint endpoint = new AgentLlmProperties.Endpoint();
        endpoint.setApiKey("test-key");
        endpoint.setBaseUrl("https://openrouter.ai/api/v1");
        properties.getEndpoints().put("openrouter", endpoint);
        AgentModelCatalogService catalog = mock(AgentModelCatalogService.class);
        when(catalog.resolveBaseRate(anyString())).thenReturn(1.0D);
        return new OpenRouterEndpointCostAdapter(costService, loader, properties, catalog);
    }
}
