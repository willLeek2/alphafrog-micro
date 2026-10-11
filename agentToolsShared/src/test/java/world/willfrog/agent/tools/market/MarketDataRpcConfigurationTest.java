package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MarketDataRpcConfigurationTest {
    private static final Map<String, String> MARKET_FIELDS = Map.of(
            "marketDomesticStockService", "domesticStockService",
            "marketDomesticFundService", "domesticFundService",
            "marketDomesticIndexService", "domesticIndexService",
            "marketDomesticListedAssetService", "domesticListedAssetService");

    @Test
    void betaDualRegistriesSelectOnlyProductionForMarketReferences() {
        Map<String, Object> properties = baseProperties();
        registry(properties, "dubbo.registries.beta", "nacos://127.0.0.1:8848?group=alphafrog-beta");
        registry(properties, "dubbo.registries.production", "nacos://127.0.0.1:8848?group=DEFAULT_GROUP");
        properties.put("dubbo.registries.beta.preferred", "true");
        properties.put("dubbo.registries.production.preferred", "false");
        try (AnnotationConfigApplicationContext context = context(properties)) {
            assertMarketInjectionAndLifecycle(context);
            for (String name : MARKET_FIELDS.keySet()) {
                ReferenceBean<?> bean = reference(context, name);
                assertEquals("production", bean.getReferenceConfig().getRegistryIds());
                assertEquals(List.of("production"), registryIds(bean));
            }
            ReferenceBean<?> ordinary = reference(context, "ordinaryIndexService");
            assertEquals(List.of("beta", "production"), registryIds(ordinary));
            RegistryConfig beta = ordinary.getReferenceConfig().getRegistries().stream()
                    .filter(registry -> "beta".equals(registry.getId())).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, beta.getPreferred(), "其他引用保留 Beta 优先，市场引用不应修改全局偏好");
        }
    }

    @Test
    void productionSingleRegistryKeepsDefaultWithoutRequiringProductionId() {
        Map<String, Object> properties = baseProperties();
        registry(properties, "dubbo.registry", "nacos://127.0.0.1:8848");
        try (AnnotationConfigApplicationContext context = context(properties)) {
            assertMarketInjectionAndLifecycle(context);
            for (String name : MARKET_FIELDS.keySet()) {
                ReferenceBean<?> bean = reference(context, name);
                assertNull(bean.getReferenceConfig().getRegistryIds());
                List<RegistryConfig> registries = bean.getReferenceConfig().getRegistries();
                assertEquals(1, registries.size());
                assertEquals("nacos://127.0.0.1:8848", registries.get(0).getAddress());
                assertNotEquals("production", registries.get(0).getId());
            }
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("market-test", properties));
        context.register(TestConfiguration.class, MarketDataRpcConfiguration.class, MarketDataTools.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException error) {
            context.close();
            throw error;
        }
    }

    private static Map<String, Object> baseProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dubbo.application.name", "market-registry-test");
        properties.put("dubbo.application.qos-enable", "false");
        properties.put("dubbo.application.metadata-type", "local");
        properties.put("dubbo.application.register-mode", "interface");
        // 使用原生配置解析及 FactoryBean 生命周期，不在本地测试中建立外部 RPC 连接。
        properties.put("dubbo.consumer.init", "false");
        properties.put("dubbo.consumer.check", "false");
        return properties;
    }

    private static void registry(Map<String, Object> properties, String prefix, String address) {
        properties.put(prefix + ".address", address);
        properties.put(prefix + ".register", "false");
        properties.put(prefix + ".use-as-config-center", "false");
        properties.put(prefix + ".use-as-metadata-center", "false");
    }

    private static void assertMarketInjectionAndLifecycle(AnnotationConfigApplicationContext context) {
        MarketDataTools tools = context.getBean(MarketDataTools.class);
        ReferenceBeanManager manager = context.getBean(ReferenceBeanManager.class);
        for (Map.Entry<String, String> field : MARKET_FIELDS.entrySet()) {
            ReferenceBean<?> bean = reference(context, field.getKey());
            assertNotNull(bean.getReferenceConfig(), "Dubbo 应创建并管理真实 ReferenceConfig");
            assertTrue(bean.getReferenceConfig().isRefreshed(), "最终注册中心须经过原生刷新与解析");
            assertFalse(bean.getReferenceConfig().configInitialized(), "本地测试不得连接外部服务");
            assertSame(bean, manager.getById(field.getKey()));
            assertSame(context.getBean(field.getKey()), ReflectionTestUtils.getField(tools, field.getValue()));
            assertTrue(bean.getObjectType().isInstance(context.getBean(field.getKey())));
        }
    }

    private static ReferenceBean<?> reference(AnnotationConfigApplicationContext context, String name) {
        return (ReferenceBean<?>) context.getBean("&" + name);
    }

    private static List<String> registryIds(ReferenceBean<?> bean) {
        return bean.getReferenceConfig().getRegistries().stream().map(RegistryConfig::getId).sorted().toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableDubbo
    static class TestConfiguration {
        @Bean
        ReferenceBean<DomesticIndexService> ordinaryIndexService() {
            return new ReferenceBeanBuilder().setInterface(DomesticIndexService.class).build();
        }

        @Bean DatasetWriter datasetWriter() { return mock(DatasetWriter.class); }
        @Bean DatasetRegistry datasetRegistry() { return mock(DatasetRegistry.class); }
        @Bean ManifestWriter manifestWriter() { return mock(ManifestWriter.class); }
        @Bean AgentLlmLocalConfigLoader localConfigLoader() { return mock(AgentLlmLocalConfigLoader.class); }
        @Bean AgentLlmProperties llmProperties() { return new AgentLlmProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean SwIndustryMemberDao swIndustryMemberDao() { return mock(SwIndustryMemberDao.class); }
    }
}
