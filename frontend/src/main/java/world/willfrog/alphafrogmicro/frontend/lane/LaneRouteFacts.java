package world.willfrog.alphafrogmicro.frontend.lane;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

/** 由部署控制器确认的当前流量范围默认路由事实。 */
public record LaneRouteFacts(
        String trafficScopeId,
        String serviceName,
        DeploymentIdentity deploymentIdentity,
        long stateVersion) {

    public LaneRouteFacts {
        if (trafficScopeId == null || trafficScopeId.isBlank()) {
            throw new IllegalArgumentException("流量范围不能为空");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("服务名称不能为空");
        }
        if (deploymentIdentity == null
                || DeploymentIdentity.STABLE_DEPLOYMENT_ID.equals(deploymentIdentity.deploymentId())) {
            throw new IllegalArgumentException("打标路由必须指向测试部署");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("状态版本不能小于零");
        }
    }
}
