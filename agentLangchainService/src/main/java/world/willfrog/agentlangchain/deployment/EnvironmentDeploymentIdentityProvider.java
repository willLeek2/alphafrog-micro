package world.willfrog.agentlangchain.deployment;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

/** 从部署注入的环境变量读取当前 Agent 服务实例身份。 */
@Component
public class EnvironmentDeploymentIdentityProvider implements DeploymentIdentityProvider {

    private final Environment environment;
    private volatile DeploymentIdentity resolved;

    public EnvironmentDeploymentIdentityProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public DeploymentIdentity current() {
        DeploymentIdentity current = resolved;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (resolved == null) {
                resolved = new DeploymentIdentity(
                        environment.getProperty("AF_DEPLOYMENT_ID"),
                        environment.getProperty("AF_DEPLOYMENT_GENERATION_ID"));
            }
            return resolved;
        }
    }
}
