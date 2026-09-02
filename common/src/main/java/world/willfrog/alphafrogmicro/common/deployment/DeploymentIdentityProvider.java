package world.willfrog.alphafrogmicro.common.deployment;

/** 提供由部署系统注入、而不是由外部请求声明的当前服务实例身份。 */
@FunctionalInterface
public interface DeploymentIdentityProvider {

    DeploymentIdentity current();
}
