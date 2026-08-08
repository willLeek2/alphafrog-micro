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

    @Test
    void resolve_shouldPreferDedicatedStageConfig() {
        StageLlmConfig dedicated = stageConfig("dedicated-endpoint", "dedicated-model");
        RunStageConfig runStageConfig = new RunStageConfig();
        runStageConfig.setFinanceMethodResolver(dedicated);
        AgentContext.setStageConfig(runStageConfig);

        AgentLlmProperties properties = propertiesWithDefaultRoute("default-endpoint", "default-model");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> result = resolver.resolve();

        assertTrue(result.isPresent());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG, result.get().source());
        assertEquals("dedicated-endpoint", result.get().config().getEndpointName());
        assertEquals("dedicated-model", result.get().config().getModelName());
    }

    @Test
    void resolve_shouldFallBackToDefaultRouteWhenStageConfigMissing() {
        AgentContext.setStageConfig(new RunStageConfig());
        AgentLlmProperties properties = propertiesWithDefaultRoute("fallback-endpoint", "fallback-model");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> result = resolver.resolve();

        assertTrue(result.isPresent());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE, result.get().source());
        assertEquals("fallback-endpoint", result.get().config().getEndpointName());
        assertEquals("fallback-model", result.get().config().getModelName());
        assertEquals(0.0D, result.get().config().getTemperature());
    }

    @Test
    void resolve_shouldReturnEmptyWhenDefaultRouteDisabled() {
        AgentContext.setStageConfig(new RunStageConfig());
        AgentLlmProperties properties = new AgentLlmProperties();
        properties.getFinanceMethodResolver().getDefaultRoute().setEnabled(false);
        properties.getFinanceMethodResolver().getDefaultRoute().setEndpointName("unused");
        properties.getFinanceMethodResolver().getDefaultRoute().setModelName("unused");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> result = resolver.resolve();

        assertFalse(result.isPresent());
    }

    @Test
    void resolve_shouldReturnEmptyWhenDefaultRouteInvalid() {
        AgentContext.setStageConfig(new RunStageConfig());
        AgentLlmProperties properties = new AgentLlmProperties();
        properties.getFinanceMethodResolver().getDefaultRoute().setEnabled(true);
        properties.getFinanceMethodResolver().getDefaultRoute().setEndpointName("");
        properties.getFinanceMethodResolver().getDefaultRoute().setModelName("model-only");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> result = resolver.resolve();

        assertFalse(result.isPresent());
    }

    @Test
    void resolve_shouldNotInheritExecutionModel() {
        RunStageConfig runStageConfig = new RunStageConfig();
        StageLlmConfig execution = stageConfig("exec-endpoint", "exec-model");
        runStageConfig.setExecution(execution);
        AgentContext.setStageConfig(runStageConfig);

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(new AgentLlmProperties());

        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> result = resolver.resolve();

        assertFalse(result.isPresent(), "resolver 不能静默继承 execution 模型");
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
