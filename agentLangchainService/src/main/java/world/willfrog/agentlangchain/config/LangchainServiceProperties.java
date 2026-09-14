package world.willfrog.agentlangchain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.langchain")
public class LangchainServiceProperties {

    private final Provider provider = new Provider();

    @Data
    public static class Provider {
        /** 关闭时不注册 Dubbo provider，health 会报告 PROVIDER_DISABLED。 */
        private boolean enabled = false;
    }
}
