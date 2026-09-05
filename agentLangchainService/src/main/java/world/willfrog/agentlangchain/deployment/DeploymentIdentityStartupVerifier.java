package world.willfrog.agentlangchain.deployment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

/** Agent RPC 提供者启动前验证可信部署身份已经完整注入。 */
@Component
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
public class DeploymentIdentityStartupVerifier implements InitializingBean {

    private final DeploymentIdentityProvider identityProvider;

    public DeploymentIdentityStartupVerifier(DeploymentIdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @Override
    public void afterPropertiesSet() {
        identityProvider.current();
    }
}
