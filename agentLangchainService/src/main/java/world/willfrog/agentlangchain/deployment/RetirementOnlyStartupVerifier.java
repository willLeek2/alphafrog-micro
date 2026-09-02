package world.willfrog.agentlangchain.deployment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** 退役专用实例必须脱离服务注册，避免被任何普通请求发现。 */
@Component
@ConditionalOnExpression("${agent.langchain.provider.enabled:false}"
        + " && ${agent.deployment.retirement-only:false}")
public class RetirementOnlyStartupVerifier implements InitializingBean {

    private final boolean registryRegistrationEnabled;

    public RetirementOnlyStartupVerifier(
            @Value("${dubbo.registry.register:true}") boolean registryRegistrationEnabled) {
        this.registryRegistrationEnabled = registryRegistrationEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (registryRegistrationEnabled) {
            throw new IllegalStateException(
                    "retirement-only Agent must set AF_DUBBO_REGISTRY_REGISTER=false");
        }
    }
}
