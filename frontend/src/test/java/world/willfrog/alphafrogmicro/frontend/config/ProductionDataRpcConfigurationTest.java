package world.willfrog.alphafrogmicro.frontend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

class ProductionDataRpcConfigurationTest {

    @Test
    void betaDualRegistriesSelectOnlyProductionAndKeepTimeouts() {
        try (AnnotationConfigApplicationContext context = context(properties(
                "frontend-production-registry-test", true))) {
            ReferenceConfig<?> news = reference(context, "marketNewsExternalInfoService");
            ReferenceConfig<?> index = reference(context, "domesticPageIndexService");
            ReferenceConfig<?> pageFund = reference(context, "domesticPageFundService");
            ReferenceConfig<?> listedAsset = reference(context, "listedAssetService");
            ReferenceConfig<?> listedFund = reference(context, "listedAssetFundService");
            ReferenceConfig<?> ordinary = reference(context, "ordinaryStockService");

            for (ReferenceConfig<?> productionReference : new ReferenceConfig<?>[] {news, index, pageFund, listedAsset, listedFund}) {
                assertEquals(List.of("production"), registryIds(productionReference));
            }
            assertNull(news.getTimeout());
            assertNull(index.getTimeout());
            assertNull(pageFund.getTimeout());
            assertEquals(Integer.valueOf(35000), listedAsset.getTimeout());
            assertEquals(Integer.valueOf(35000), listedFund.getTimeout());
            assertEquals(List.of("beta", "production"), registryIds(ordinary));
        }
    }

    @Test
    void singleRegistryKeepsDefaultWithoutRequiringProductionId() {
        try (AnnotationConfigApplicationContext context = context(properties(
                "frontend-single-registry-test", false))) {
            for (String beanName : new String[] {
                "marketNewsExternalInfoService",
                "domesticPageIndexService",
                "domesticPageFundService",
                "listedAssetService",
                "listedAssetFundService"
            }) {
                ReferenceConfig<?> reference = reference(context, beanName);
                assertNull(reference.getRegistryIds());
                assertEquals(1, reference.getRegistries().size());
                assertEquals("nacos://127.0.0.1:8848", reference.getRegistries().get(0).getAddress());
                assertNotEquals("production", reference.getRegistries().get(0).getId());
            }
            assertEquals(Integer.valueOf(35000), reference(context, "listedAssetService").getTimeout());
            assertEquals(Integer.valueOf(35000), reference(context, "listedAssetFundService").getTimeout());
            assertNull(reference(context, "domesticPageFundService").getTimeout());
        }
    }

    private static Map<String, Object> properties(String applicationName, boolean productionRegistry) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dubbo.application.name", applicationName);
        properties.put("dubbo.application.qos-enable", "false");
        properties.put("dubbo.application.metadata-type", "local");
        properties.put("dubbo.application.register-mode", "interface");
        properties.put("dubbo.consumer.check", "false");
        properties.put("dubbo.consumer.init", "false");
        if (productionRegistry) {
            registry(properties, "dubbo.registries.beta", "nacos://127.0.0.1:8848");
            properties.put("dubbo.registries.beta.preferred", "true");
            registry(properties, "dubbo.registries.production", "nacos://127.0.0.1:8848");
        } else {
            registry(properties, "dubbo.registry", "nacos://127.0.0.1:8848");
        }
        return properties;
    }

    private static void registry(Map<String, Object> properties, String prefix, String address) {
        properties.put(prefix + ".address", address);
        properties.put(prefix + ".register", "false");
        properties.put(prefix + ".use-as-config-center", "false");
        properties.put(prefix + ".use-as-metadata-center", "false");
    }

    private static List<String> registryIds(ReferenceConfig<?> reference) {
        return reference.getRegistries().stream().map(RegistryConfig::getId).sorted().toList();
    }

    private static ReferenceConfig<?> reference(AnnotationConfigApplicationContext context, String beanName) {
        ReferenceBean<?> bean = context.getBean("&" + beanName, ReferenceBean.class);
        assertNotNull(bean.getReferenceConfig());
        assertTrue(bean.getReferenceConfig().isRefreshed());
        assertFalse(bean.getReferenceConfig().configInitialized());
        return bean.getReferenceConfig();
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(ProductionDataRpcConfiguration.class, OrdinaryReferenceConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException error) {
            context.close();
            throw error;
        }
    }

    @Configuration
    @EnableDubbo
    static class OrdinaryReferenceConfiguration {

        @Bean
        ReferenceBean<DomesticStockService> ordinaryStockService() {
            return new ReferenceBeanBuilder().setInterface(DomesticStockService.class).build();
        }
    }
}
