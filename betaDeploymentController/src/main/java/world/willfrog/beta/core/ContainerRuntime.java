package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;

public interface ContainerRuntime {
    default void validateHostPrerequisites() { }
    default void validateManifestEnvironment(JsonNode manifest) { }
    default void validateManifest(JsonNode manifest) {
        validateHostPrerequisites();
        validateManifestEnvironment(manifest);
    }
    ContainerObservation create(JsonNode manifest, JsonNode service, CandidatePlan plan);
    ContainerObservation inspect(String machineId, String containerName);
    void stop(String machineId, String containerName, int timeoutSeconds);
    void remove(String machineId, String containerName);
    void removeCompose(String instanceId);
    String containerName(CandidatePlan plan, String serviceName);

    record CandidatePlan(String deploymentId, String trafficScopeId, String instanceId,
                         String generationId, String portSlot, int hostPort, HttpUpstream httpUpstream) {
        public CandidatePlan(String deploymentId, String trafficScopeId, String instanceId,
                             String generationId, String portSlot, int hostPort) {
            this(deploymentId, trafficScopeId, instanceId, generationId, portSlot, hostPort, null);
        }
    }

    // 候选实例要访问的 HTTP 上游（同部署 Python 沙箱的当前活动宿主口）。只有沙箱网关
    // 携带这个值：沙箱的 HTTP 口不注册，上游口一变网关就必须重建容器。
    record HttpUpstream(String address, int port) {
        public String url() {
            return "http://" + address + ':' + port;
        }
    }

    record ContainerObservation(String containerId, String containerName, String endpointAddress,
                                int hostPort, boolean running, Health health) {
        public enum Health { STARTING, HEALTHY, UNHEALTHY, MISSING }
    }
}
