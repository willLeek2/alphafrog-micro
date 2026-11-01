package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceMethodResolverModelResolverTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }














    private static StageLlmConfig stageConfig(String endpoint, String model) {
        StageLlmConfig config = new StageLlmConfig();
        config.setEndpointName(endpoint);
        config.setModelName(model);
        return config;
    }

    private static AgentLlmProperties propertiesWithDefaultRoute(String endpoint, String model) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.FinanceMethodResolver.DefaultRoute route = properties.getFinanceMethodResolver().getDefaultRoute();
        route.setEnabled(true);
        route.setEndpointName(endpoint);
        route.setModelName(model);
        route.setProviderOrder(List.of("provider-a"));
        route.setTemperature(0.0D);
        route.setMaxTokens(2048);
        return properties;
    }
}
