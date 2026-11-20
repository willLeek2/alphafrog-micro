package world.willfrog.alphafrogmicro.frontend.config;

import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;

/**
 * 基金、指数、场内资产和新闻读取的数据服务不在 Beta 部署。
 * 进程配了 production 注册中心地址时，这些引用只订阅 production；
 * 单注册中心部署不写注册中心编号，继续使用该部署的默认注册中心。
 * 页面基金查询没有单独超时，场内资产页的基金查询超时为 35000 毫秒，两处各保留一份引用。
 */
@Configuration(proxyBeanMethods = false)
public class ProductionDataRpcConfiguration {

    @Bean
    public ReferenceBean<ExternalInfoDubboService> marketNewsExternalInfoService(Environment environment) {
        return reference(ExternalInfoDubboService.class, environment, null, null);
    }

    @Bean
    public ReferenceBean<DomesticIndexService> domesticPageIndexService(Environment environment) {
        return reference(DomesticIndexService.class, environment, null, null);
    }

    @Bean
    public ReferenceBean<DomesticFundService> domesticPageFundService(Environment environment) {
        return reference(DomesticFundService.class, environment, null, null);
    }

    @Bean
    public ReferenceBean<DomesticListedAssetService> listedAssetService(Environment environment) {
        return reference(DomesticListedAssetService.class, environment, 35000, null);
    }

    @Bean
    public ReferenceBean<DomesticFundService> listedAssetFundService(Environment environment) {
        return reference(DomesticFundService.class, environment, 35000, null);
    }

    private static <T> ReferenceBean<T> reference(
            Class<T> serviceType, Environment environment, Integer timeout, Integer retries) {
        ReferenceBeanBuilder builder = new ReferenceBeanBuilder().setInterface(serviceType);
        if (timeout != null) {
            builder.setTimeout(timeout);
        }
        if (retries != null) {
            builder.setRetries(retries);
        }
        if (environment.containsProperty("dubbo.registries.production.address")) {
            builder.setRegistry(new String[] {"production"});
        }
        return builder.build();
    }
}
