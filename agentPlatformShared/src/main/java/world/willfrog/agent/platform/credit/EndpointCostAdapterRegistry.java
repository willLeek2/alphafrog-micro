package world.willfrog.agent.platform.credit;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.OpenRouterCostService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class EndpointCostAdapterRegistry {

    private final Map<String, EndpointCostAdapter> adaptersByEndpoint;

    public EndpointCostAdapterRegistry(OpenRouterCostService openRouterCostService,
                                       AgentLlmLocalConfigLoader localConfigLoader,
                                       AgentLlmProperties properties,
                                       AgentModelCatalogService modelCatalogService) {
        this.adaptersByEndpoint = new LinkedHashMap<>();
        register(new OpenRouterEndpointCostAdapter(
                openRouterCostService, localConfigLoader, properties, modelCatalogService));
        register(new PerCallEndpointCostAdapter("fireworks", modelCatalogService));
        register(new PerCallEndpointCostAdapter("dashscope-cn", modelCatalogService));
        PerCallEndpointCostAdapter dashscopeSgAdapter =
                new PerCallEndpointCostAdapter("dashscope-sg", modelCatalogService);
        register(dashscopeSgAdapter);
        registerAlias("dashscope", dashscopeSgAdapter);
    }

    private void register(EndpointCostAdapter adapter) {
        adaptersByEndpoint.put(normalize(adapter.endpointId()), adapter);
    }

    private void registerAlias(String alias, EndpointCostAdapter adapter) {
        adaptersByEndpoint.put(normalize(alias), adapter);
    }

    public Optional<EndpointCostAdapter> find(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adaptersByEndpoint.get(normalize(endpoint)));
    }

    public boolean supportsCostFetch(String endpoint) {
        return find(endpoint).map(EndpointCostAdapter::supportsCostFetch).orElse(false);
    }

    public CostSettlementQuote quote(String endpoint, LlmCallBillingContext call, int settlementAttempt) {
        return find(endpoint)
                .map(adapter -> adapter.quote(call, settlementAttempt))
                .orElseGet(() -> CostSettlementQuote.builder()
                        .runId(call.getRunId())
                        .callId(call.getCallId())
                        .endpoint(call.getEndpoint())
                        .model(call.getModel())
                        .billingMode(BillingMode.PER_CALL)
                        .costSource(CostSource.PER_CALL)
                        .currency("USD")
                        .costAmount(java.math.BigDecimal.ZERO)
                        .creditDelta(java.math.BigDecimal.ZERO)
                        .costAvailable(false)
                        .needsDelayedRetry(false)
                        .settlementAttempt(settlementAttempt)
                        .build());
    }

    public static LlmCallBillingContext fromTrace(AgentObservabilityService.LlmTrace trace) {
        if (trace == null) {
            return LlmCallBillingContext.builder().build();
        }
        return LlmCallBillingContext.builder()
                .runId(trace.getRunId())
                .callId(trace.getTraceId())
                .endpoint(trace.getEndpoint())
                .model(trace.getModel())
                .generationId(trace.getGenerationId())
                .actualCostUsd(trace.getActualCost())
                .hasError(trace.isHasError())
                .build();
    }

    private static String normalize(String endpoint) {
        return endpoint.trim().toLowerCase(Locale.ROOT);
    }
}
