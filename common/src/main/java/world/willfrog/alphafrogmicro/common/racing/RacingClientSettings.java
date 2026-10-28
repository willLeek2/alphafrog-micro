package world.willfrog.alphafrogmicro.common.racing;

import java.util.Objects;
import java.util.function.Function;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.racing.idl.TargetIdentity;

/** 在调用时读取受限配置；配置缺失不会被解释成主范围或本地范围。 */
public final class RacingClientSettings {
    private final Function<String, String> properties;

    public RacingClientSettings(Function<String, String> properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public TargetIdentity target() {
        return TargetIdentity.newBuilder()
                .setEnvironment(require("RACING_ENVIRONMENT"))
                .setTargetScopeInstanceId(require("RACING_TARGET_CREATION_ID"))
                .setTargetRouteTag(requirePresent("RACING_TARGET_ROUTE_TAG"))
                .setTargetDeploymentId(DeploymentIdentity.requireDeploymentId(
                        require("RACING_TARGET_DEPLOYMENT_ID")))
                .setExpectedTargetGenerationId(DeploymentIdentity.requireActiveGenerationId(
                        require("RACING_TARGET_GENERATION_ID")))
                .build();
    }

    public String ownScopeInstanceId() {
        return require("AF_LANE_CREATION_ID");
    }

    public String ownRouteTag() {
        String scope = properties.apply("AF_LANE_TRAFFIC_SCOPE_ID");
        return scope == null || scope.isBlank() ? requirePresent("AF_LANE_TAG") : scope;
    }

    public String callerGenerationId() {
        return DeploymentIdentity.requireActiveGenerationId(require("AF_DEPLOYMENT_GENERATION_ID"));
    }

    Credentials credentials() {
        return new Credentials(require("RACING_SERVICE_KEY_ID"), require("RACING_SERVICE_TOKEN"));
    }

    private String require(String key) {
        String value = requirePresent(key);
        if (value.isBlank()) throw missing(key);
        return value;
    }

    private String requirePresent(String key) {
        String value = properties.apply(key);
        if (value == null || !value.equals(value.strip())) throw missing(key);
        return value;
    }

    private static RacingCallException missing(String key) {
        return RacingCallException.failure("SCOPE_UNAVAILABLE", "实验平台调用配置缺失或格式错误：" + key);
    }

    record Credentials(String keyId, String token) {
        @Override
        public String toString() {
            return "RacingCredentials[redacted]";
        }
    }
}
