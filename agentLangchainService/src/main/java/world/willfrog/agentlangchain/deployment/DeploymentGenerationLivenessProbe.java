package world.willfrog.agentlangchain.deployment;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

/** 从服务注册现场判断某一部署代际是否仍有存活实例。查询不确定时必须抛错并停止清扫。 */
public interface DeploymentGenerationLivenessProbe {
    boolean hasLiveInstance(DeploymentIdentity identity);
}
