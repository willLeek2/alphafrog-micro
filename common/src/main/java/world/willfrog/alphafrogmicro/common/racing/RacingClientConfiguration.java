package world.willfrog.alphafrogmicro.common.racing;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import world.willfrog.racing.idl.RacingService;

/** 远程引用按需初始化，业务首次调用再核对完整部署配置。 */
@AutoConfiguration(afterName = "org.apache.dubbo.spring.boot.autoconfigure.DubboAutoConfiguration")
@ConditionalOnClass(RacingService.class)
public class RacingClientConfiguration {
    @DubboReference(protocol = "tri", check = false, lazy = true, init = false, retries = 0, timeout = 10000)
    private RacingService service;

    @Bean
    @ConditionalOnMissingBean
    public RacingClientSettings racingClientSettings(Environment environment) {
        return new RacingClientSettings(environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean
    public RacingTransport racingTransport(RacingClientSettings settings) {
        return new RacingTransport(service, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    public RacingExperimentClient racingExperimentClient(RacingTransport transport) {
        return new RacingExperimentClient(transport);
    }
}
