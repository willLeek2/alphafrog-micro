package world.willfrog.alphafrogmicro.frontend.lane;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

/**
 * 给入口发出的 Agent 写请求提供部署身份。打标请求使用本次可信路由事实，
 * 普通请求使用部署流程注入给 frontend 本身的稳定环境身份。
 */
public final class FrontendDeploymentIdentityProvider implements DeploymentIdentityProvider {

    private final LaneEntryProperties properties;

    public FrontendDeploymentIdentityProvider(LaneEntryProperties properties) {
        this.properties = properties;
    }

    @Override
    public DeploymentIdentity current() {
        LaneRouteFacts laneFacts = LaneRequestContext.current();
        if (laneFacts != null) {
            return laneFacts.deploymentIdentity();
        }
        try {
            return new DeploymentIdentity(
                    properties.getLocalDeploymentId(),
                    properties.getLocalDeploymentGenerationId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("frontend 的稳定部署身份没有完整配置", exception);
        }
    }
}
