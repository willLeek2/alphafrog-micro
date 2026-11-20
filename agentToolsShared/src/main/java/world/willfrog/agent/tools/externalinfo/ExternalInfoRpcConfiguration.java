package world.willfrog.agent.tools.externalinfo;

import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;

/**
 * RAG 和搜索读取的外部信息服务不在 Beta 部署。
 * 进程配了 production 注册中心地址时，这份引用只订阅 production；
 * 单注册中心部署不写注册中心编号，继续使用该部署的默认注册中心。
 * RagTools 与 SearchTools 的超时和重试相同，共用这一份引用。
 * 依赖检查按 ExternalInfoDubboService 寻找唯一引用，两份引用会让该项变成未检查。
 */
@Configuration(proxyBeanMethods = false)
public class ExternalInfoRpcConfiguration {

    @Bean
    public ReferenceBean<ExternalInfoDubboService> externalInfoDubboService(Environment environment) {
        ReferenceBeanBuilder builder = new ReferenceBeanBuilder().setInterface(ExternalInfoDubboService.class);
        builder.setTimeout(60000);
        builder.setRetries(0);
        if (environment.containsProperty("dubbo.registries.production.address")) {
            builder.setRegistry(new String[] {"production"});
        }
        return builder.build();
    }
}
