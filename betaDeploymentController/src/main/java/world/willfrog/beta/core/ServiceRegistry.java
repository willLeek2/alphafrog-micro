package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;

public interface ServiceRegistry {
    Registration register(JsonNode manifest, JsonNode service, String instanceId,
                          String generationId, String address, int port, boolean selectable);
    Registration setSelectable(Registration expected, boolean selectable);
    Registration observe(Registration expected);
    default Optional<Registration> find(Registration expected) {
        return Optional.ofNullable(observe(expected));
    }
    default void assertNoUntrackedRegistrations(JsonNode manifest, JsonNode service, Set<String> trackedInstanceIds) { }
    void unregister(Registration expected);

    record Registration(String serviceName, String groupName, String namespaceId, String clusterName,
                        String ip, int port, String nacosInstanceId, boolean enabled, boolean healthy,
                        int weight, boolean ephemeral, java.util.Map<String, String> metadata) {}
}
