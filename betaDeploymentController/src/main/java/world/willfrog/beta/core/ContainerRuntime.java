package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;

public interface ContainerRuntime {
    default void validateManifest(JsonNode manifest) { }
    ContainerObservation create(JsonNode manifest, JsonNode service, CandidatePlan plan);
    ContainerObservation inspect(String machineId, String containerName);
    void stop(String machineId, String containerName, int timeoutSeconds);
    void remove(String machineId, String containerName);

    record CandidatePlan(String deploymentId, String trafficScopeId, String instanceId,
                         String generationId, String portSlot, int hostPort) {}

    record ContainerObservation(String containerId, String containerName, String endpointAddress,
                                int hostPort, boolean running, Health health) {
        public enum Health { STARTING, HEALTHY, UNHEALTHY, MISSING }
    }
}
