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
