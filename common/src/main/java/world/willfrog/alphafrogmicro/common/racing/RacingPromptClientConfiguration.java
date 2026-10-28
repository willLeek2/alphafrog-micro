package world.willfrog.alphafrogmicro.common.racing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 业务显式导入提示词与输入客户端，复用共同传输和受限配置。 */
@Configuration(proxyBeanMethods = false)
@Import(RacingClientConfiguration.class)
public class RacingPromptClientConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RacingPromptClient racingPromptClient(RacingTransport transport) {
        return new RacingPromptClient(transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public RacingInputClient racingInputClient(RacingTransport transport) {
        return new RacingInputClient(transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public RacingPromptSetReader racingPromptSetReader(RacingPromptClient client) {
        return new RacingPromptSetReader(client);
    }
}
