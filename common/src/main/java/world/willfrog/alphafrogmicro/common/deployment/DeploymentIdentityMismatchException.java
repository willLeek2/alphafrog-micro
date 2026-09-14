package world.willfrog.alphafrogmicro.common.deployment;

/** 请求携带的部署身份不能由当前服务实例执行。 */
public class DeploymentIdentityMismatchException extends IllegalStateException {

    public DeploymentIdentityMismatchException(String message) {
        super(message);
    }
}
