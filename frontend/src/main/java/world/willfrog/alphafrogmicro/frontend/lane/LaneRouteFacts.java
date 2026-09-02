package world.willfrog.alphafrogmicro.frontend.lane;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBinding;
import world.willfrog.alphafrogmicro.common.lane.LaneDubboServiceKey;

/** 由部署控制器确认的当前流量范围默认路由事实。 */
public record LaneRouteFacts(
        String trafficScopeId,
        String serviceName,
        DeploymentIdentity deploymentIdentity,
        LaneDubboServiceKey dubboServiceKey,
        String registrationServiceName,
        LaneCallBinding callBinding,
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
        if (registrationServiceName == null || registrationServiceName.isBlank()) {
            throw new IllegalArgumentException("注册服务名称不能为空");
        }
        if (dubboServiceKey == null) {
            throw new IllegalArgumentException("Dubbo 调用服务键不能为空");
        }
        if (callBinding == null
                || !trafficScopeId.equals(callBinding.trafficScopeId())
                || !serviceName.equals(callBinding.serviceName())
                || !deploymentIdentity.generationId().equals(callBinding.deploymentGenerationId())) {
            throw new IllegalArgumentException("部署身份和精确调用绑定必须来自同一次路由读取");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("状态版本不能小于零");
        }
    }
}
