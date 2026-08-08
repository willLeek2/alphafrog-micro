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

    @Test
    void resolveCandidates_shouldReturnStageThenDefaultInFrozenOrder() {
        StageLlmConfig dedicated = stageConfig("dedicated-endpoint", "dedicated-model");
        RunStageConfig runStageConfig = new RunStageConfig();
        runStageConfig.setFinanceMethodResolver(dedicated);
        AgentContext.setStageConfig(runStageConfig);

        AgentLlmProperties properties = propertiesWithDefaultRoute("default-endpoint", "default-model");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        List<FinanceMethodResolverModelResolver.ResolvedStageModel> candidates = resolver.resolveCandidates();

        assertEquals(2, candidates.size());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG, candidates.get(0).source());
        assertEquals("dedicated-endpoint", candidates.get(0).config().getEndpointName());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE, candidates.get(1).source());
        assertEquals("default-endpoint", candidates.get(1).config().getEndpointName());
    }

    @Test
    void resolveCandidates_shouldSkipInvalidStageButKeepDefault() {
        StageLlmConfig invalidStage = stageConfig("", "model-only");
        RunStageConfig runStageConfig = new RunStageConfig();
        runStageConfig.setFinanceMethodResolver(invalidStage);
        AgentContext.setStageConfig(runStageConfig);

        AgentLlmProperties properties = propertiesWithDefaultRoute("default-endpoint", "default-model");
        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(properties);

        List<FinanceMethodResolverModelResolver.ResolvedStageModel> candidates = resolver.resolveCandidates();

        assertEquals(1, candidates.size());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE, candidates.get(0).source());
    }

    @Test
    void effectiveResolverConfig_shouldPreferLocalSectionWhenPresent() {
        AgentLlmProperties staticProperties = propertiesWithDefaultRoute("static-endpoint", "static-model");
        staticProperties.getFinanceMethodResolver().setRequestMaxBytes(8192);

        AgentLlmProperties local = new AgentLlmProperties();
        local.getFinanceMethodResolver().setRequestMaxBytes(6000);
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(
                        local, java.util.Set.of(FinanceMethodResolverModelResolver.LOCAL_SECTION)));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        assertEquals(6000, resolver.effectiveResolverConfig().getRequestMaxBytes());
    }

    @Test
    void effectiveResolverConfig_shouldFallBackToStaticWhenSectionAbsent() {
        AgentLlmProperties staticProperties = propertiesWithDefaultRoute("static-endpoint", "static-model");
        staticProperties.getFinanceMethodResolver().setRequestMaxBytes(7000);

        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(local, java.util.Set.of("prompts")));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        assertEquals(7000, resolver.effectiveResolverConfig().getRequestMaxBytes());
    }

    @Test
    void effectiveResolverConfig_shouldFallBackToStaticWhenNoLocalConfig() {
        AgentLlmProperties staticProperties = propertiesWithDefaultRoute("static-endpoint", "static-model");
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(null, java.util.Set.of()));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        assertEquals("static-endpoint",
                resolver.effectiveResolverConfig().getDefaultRoute().getEndpointName());
    }

    @Test
    void resolveCandidates_shouldBuildDefaultRouteFromLocalSection() {
        AgentLlmProperties staticProperties = new AgentLlmProperties();
        AgentLlmProperties local = propertiesWithDefaultRoute("local-endpoint", "local-model");
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(
                        local, java.util.Set.of(FinanceMethodResolverModelResolver.LOCAL_SECTION)));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        List<FinanceMethodResolverModelResolver.ResolvedStageModel> candidates = resolver.resolveCandidates();

        assertEquals(1, candidates.size());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE, candidates.get(0).source());
        assertEquals("local-endpoint", candidates.get(0).config().getEndpointName());
    }

    @Test
    void resolveCandidates_shouldHonorLocalExplicitDisableOverStaticEnabled() {
        AgentLlmProperties staticProperties = propertiesWithDefaultRoute("static-endpoint", "static-model");
        AgentLlmProperties local = new AgentLlmProperties();
        local.getFinanceMethodResolver().getDefaultRoute().setEnabled(false);
        local.getFinanceMethodResolver().getDefaultRoute().setEndpointName("ignored");
        local.getFinanceMethodResolver().getDefaultRoute().setModelName("ignored");
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(
                        local, java.util.Set.of(FinanceMethodResolverModelResolver.LOCAL_SECTION)));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        assertTrue(resolver.resolveCandidates().isEmpty(),
                "本地显式 enabled=false 必须生效，不能回退到静态 enabled=true");
    }

    @Test
    void effectiveResolverConfig_shouldReadConfigAndSectionFromSameSnapshot() {
        // 结构性保证：config 含 financeMethodResolver 内容、但同一快照的节集合不含该节名时，
        // 必须回退静态——证明 resolver 只读一次 currentSnapshot()，不组合两个 accessor
        // （否则 refresh 窗口可能把含该节内容的 config 与含该节名的 sections 错配成本地生效）。
        AgentLlmProperties staticProperties = propertiesWithDefaultRoute("static-endpoint", "static-model");
        AgentLlmProperties local = propertiesWithDefaultRoute("local-endpoint", "local-model");
        AgentLlmLocalConfigLoader loader = org.mockito.Mockito.mock(AgentLlmLocalConfigLoader.class);
        org.mockito.Mockito.when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(local, java.util.Set.of("prompts")));

        FinanceMethodResolverModelResolver resolver = new FinanceMethodResolverModelResolver(staticProperties, loader);

        assertEquals("static-endpoint",
                resolver.effectiveResolverConfig().getDefaultRoute().getEndpointName());
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
