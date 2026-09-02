package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;

/**
 * 一次新服务调用开始时读到的精确实例。
 *
 * <p>调用方必须保存这份绑定直到本次调用结束。后续指针被原子替换后，这份对象保持原值，
 * 新的调用再读一次指针。</p>
 */
public final class LaneCallBinding {

    private final String trafficScopeId;
    private final String serviceName;
    private final String instanceId;
    private final String releaseId;
    private final String deploymentGenerationId;
    private final long routeVersion;
    private final LaneEndpoint endpoint;

    public LaneCallBinding(
            String trafficScopeId,
            String serviceName,
            String instanceId,
            String releaseId,
            String deploymentGenerationId,
            long routeVersion,
            LaneEndpoint endpoint) {
        this.trafficScopeId = requireText(trafficScopeId, "trafficScopeId");
        this.serviceName = requireText(serviceName, "serviceName");
        this.instanceId = requireText(instanceId, "instanceId");
        this.releaseId = requireText(releaseId, "releaseId");
        this.deploymentGenerationId = requireText(deploymentGenerationId, "deploymentGenerationId");
        if (routeVersion < 0) {
            throw new IllegalArgumentException("routeVersion 不能为负数");
        }
        this.routeVersion = routeVersion;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    public String trafficScopeId() {
        return trafficScopeId;
    }

    public String serviceName() {
        return serviceName;
    }

    public String instanceId() {
        return instanceId;
    }

    public String releaseId() {
        return releaseId;
    }

    public String deploymentGenerationId() {
        return deploymentGenerationId;
    }

    public long routeVersion() {
        return routeVersion;
    }

    public LaneEndpoint endpoint() {
        return endpoint;
    }

    public boolean matches(String address, int port) {
        return endpoint.address().equals(address) && endpoint.port() == port;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }
}
