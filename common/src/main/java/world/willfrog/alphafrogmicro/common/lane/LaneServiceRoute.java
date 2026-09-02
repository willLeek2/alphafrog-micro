package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;

/** 一份已经原子替换的服务默认路由，以及指针指向的那一个实例。 */
public final class LaneServiceRoute {

    private final String trafficScopeId;
    private final String serviceName;
    private final String registrationServiceName;
    private final String defaultInstanceId;
    private final String defaultReleaseId;
    private final String defaultDeploymentGenerationId;
    private final long routeVersion;
    private final String updatedAt;
    private final LaneEndpoint endpoint;

    public LaneServiceRoute(
            String trafficScopeId,
            String serviceName,
            String registrationServiceName,
            String defaultInstanceId,
            String defaultReleaseId,
            String defaultDeploymentGenerationId,
            long routeVersion,
            String updatedAt,
            LaneEndpoint endpoint) {
        this.trafficScopeId = requireText(trafficScopeId, "trafficScopeId");
        this.serviceName = requireText(serviceName, "serviceName");
        this.registrationServiceName = registrationServiceName == null || registrationServiceName.isBlank()
                ? null
                : registrationServiceName;
        this.defaultInstanceId = requireText(defaultInstanceId, "defaultInstanceId");
        this.defaultReleaseId = requireText(defaultReleaseId, "defaultReleaseId");
        this.defaultDeploymentGenerationId = requireText(
                defaultDeploymentGenerationId, "defaultDeploymentGenerationId");
        if (routeVersion < 0) {
            throw new IllegalArgumentException("routeVersion 不能为负数");
        }
        this.routeVersion = routeVersion;
        this.updatedAt = updatedAt;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    public String trafficScopeId() {
        return trafficScopeId;
    }

    public String serviceName() {
        return serviceName;
    }

    public String registrationServiceName() {
        return registrationServiceName;
    }

    public String defaultInstanceId() {
        return defaultInstanceId;
    }

    public String defaultReleaseId() {
        return defaultReleaseId;
    }

    public String defaultDeploymentGenerationId() {
        return defaultDeploymentGenerationId;
    }

    public long routeVersion() {
        return routeVersion;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public LaneEndpoint endpoint() {
        return endpoint;
    }

    public LaneCallBinding toBinding() {
        return new LaneCallBinding(
                trafficScopeId,
                serviceName,
                defaultInstanceId,
                defaultReleaseId,
                defaultDeploymentGenerationId,
                routeVersion,
                endpoint);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }
}
