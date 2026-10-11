package world.willfrog.agent.tools.market;

import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

/** 市场查询从生产数据服务读取；其他工具继续使用部署本身的默认注册中心。 */
@Configuration(proxyBeanMethods = false)
public class MarketDataRpcConfiguration {

    @Bean
    public ReferenceBean<DomesticStockService> marketDomesticStockService(Environment environment) {
        return marketReference(DomesticStockService.class, environment);
    }

    @Bean
    public ReferenceBean<DomesticFundService> marketDomesticFundService(Environment environment) {
        return marketReference(DomesticFundService.class, environment);
    }

    @Bean
    public ReferenceBean<DomesticIndexService> marketDomesticIndexService(Environment environment) {
        return marketReference(DomesticIndexService.class, environment);
    }

    @Bean
    public ReferenceBean<DomesticListedAssetService> marketDomesticListedAssetService(Environment environment) {
        return marketReference(DomesticListedAssetService.class, environment);
    }

    private static <T> ReferenceBean<T> marketReference(Class<T> serviceType, Environment environment) {
        ReferenceBeanBuilder builder = new ReferenceBeanBuilder().setInterface(serviceType);
        if (environment.containsProperty("dubbo.registries.production.address")) {
            // Beta 已注入 production 注册中心时，只订阅它，避免市场查询受 Beta 提供者的数据覆盖限制影响。
            builder.setRegistry(new String[]{"production"});
        }
        // 单注册中心生产部署沿用原来的默认配置，不要求它额外声明 production 编号。
        return builder.build();
    }
}
