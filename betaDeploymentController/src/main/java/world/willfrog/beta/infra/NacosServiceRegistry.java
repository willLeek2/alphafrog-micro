package world.willfrog.beta.infra;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.ServiceRegistry;

@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class NacosServiceRegistry implements ServiceRegistry {
    private final NamingService naming;
    private final String namespace;

    @Autowired
    public NacosServiceRegistry(BetaControllerProperties properties) {
        try {
            BetaControllerProperties.Nacos source = properties.getNacos();
            if (source.getServerAddress() == null || source.getServerAddress().isBlank())
                throw new ControllerException("NACOS_CONFIG_INVALID", "Nacos server address is required");
            Properties values = new Properties();
            values.setProperty("serverAddr", source.getServerAddress());
            namespace = normalizeNamespace(source.getNamespace());
            values.setProperty("namespace", namespace);
            if (source.getUsername() != null && !source.getUsername().isBlank()) values.setProperty("username", source.getUsername());
            if (source.getPassword() != null && !source.getPassword().isBlank()) values.setProperty("password", source.getPassword());
            naming = NacosFactory.createNamingService(values);
        } catch (NacosException exception) {
            throw new ControllerException("NACOS_START_FAILED", "Unable to initialize the Nacos client", exception);
        }
    }

    NacosServiceRegistry(NamingService naming, String namespace) {
        this.naming = naming;
        this.namespace = normalizeNamespace(namespace);
    }

    @PreDestroy
    void close() {
        try { naming.shutDown(); }
        catch (NacosException ignored) { }
    }

    @Override
    public Registration register(JsonNode manifest, JsonNode service, String instanceId,
                                 String generationId, String address, int port, boolean selectable) {
        JsonNode template = service.path("registration");
        if (!namespace.equals(normalizeNamespace(template.path("namespaceId").asText())))
            throw new ControllerException("NACOS_NAMESPACE_MISMATCH", "Manifest namespace differs from the configured Nacos namespace");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("alphafrog.traffic-scope-id", manifest.path("trafficScopeId").asText());
        metadata.put("alphafrog.release-id", service.path("releaseId").asText());
        metadata.put("alphafrog.deployment-generation-id", generationId);
        metadata.put("alphafrog.instance-id", instanceId);
        Instance instance = instance(address, port, template.path("clusterName").asText(), selectable, metadata);
        call(() -> naming.registerInstance(template.path("serviceName").asText(),
                template.path("groupName").asText(), instance));
        return requireSingle(new Registration(template.path("serviceName").asText(), template.path("groupName").asText(),
                normalizeNamespace(template.path("namespaceId").asText()), template.path("clusterName").asText(),
                address, port, "pending", selectable, true, selectable ? 1 : 0, true, metadata));
    }

    @Override
    public Registration setSelectable(Registration expected, boolean selectable) {
        Instance instance = instance(expected.ip(), expected.port(), expected.clusterName(), selectable, expected.metadata());
        call(() -> naming.registerInstance(expected.serviceName(), expected.groupName(), instance));
        return requireSingle(new Registration(expected.serviceName(), expected.groupName(), expected.namespaceId(),
                expected.clusterName(), expected.ip(), expected.port(), expected.nacosInstanceId(), selectable,
                expected.healthy(), selectable ? 1 : 0, true, expected.metadata()));
    }

    @Override
    public Registration observe(Registration expected) { return requireSingle(expected); }

    @Override
    public Optional<Registration> find(Registration expected) {
        List<Instance> matches = matchingAddress(expected);
        if (matches.isEmpty()) return Optional.empty();
        if (matches.size() != 1 || !expected.metadata().equals(matches.get(0).getMetadata()))
            throw new ControllerException("NACOS_IDENTITY_UNCERTAIN", "Nacos registration identity is conflicting");
        return Optional.of(observed(expected, matches.get(0)));
    }

    @Override
    public void assertNoUntrackedRegistrations(JsonNode manifest, JsonNode service, Set<String> trackedInstanceIds) {
        JsonNode template = service.path("registration");
        if (!namespace.equals(normalizeNamespace(template.path("namespaceId").asText())))
            throw new ControllerException("NACOS_NAMESPACE_MISMATCH", "Manifest namespace differs from controller configuration");
        try {
            List<Instance> instances = naming.getAllInstances(template.path("serviceName").asText(),
                    template.path("groupName").asText(), List.of(template.path("clusterName").asText()), false);
            String trafficScopeId = manifest.path("trafficScopeId").asText();
            for (Instance instance : instances) {
                Map<String, String> metadata = instance.getMetadata();
                if (metadata == null) continue;
                if (!trafficScopeId.equals(metadata.get("alphafrog.traffic-scope-id"))) continue;
                String instanceId = metadata.get("alphafrog.instance-id");
                if (instanceId == null || !trackedInstanceIds.contains(instanceId))
                    throw new ControllerException("UNTRACKED_NACOS_REGISTRATION",
                            "Nacos contains an untracked deployment registration");
            }
        } catch (NacosException exception) {
            throw new ControllerException("NACOS_QUERY_FAILED", "Unable to query deployment registrations", exception);
        }
    }

    @Override
    public void unregister(Registration expected) {
        call(() -> naming.deregisterInstance(expected.serviceName(), expected.groupName(), expected.ip(),
                expected.port(), expected.clusterName()));
        if (!matching(expected).isEmpty())
            throw new ControllerException("NACOS_REMOVE_NOT_CONFIRMED", "Nacos registration still exists after removal");
    }

    private Registration requireSingle(Registration expected) {
        List<Instance> matches = matchingAddress(expected);
        if (matches.size() != 1)
            throw new ControllerException("NACOS_IDENTITY_UNCERTAIN", "Nacos registration did not resolve to one exact instance");
        Instance value = matches.get(0);
        if (!expected.metadata().equals(value.getMetadata()))
            throw new ControllerException("NACOS_IDENTITY_UNCERTAIN", "Nacos registration metadata is conflicting");
        return observed(expected, value);
    }

    private Registration observed(Registration expected, Instance value) {
        if (value.getWeight() != 0.0d && value.getWeight() != 1.0d)
            throw new ControllerException("NACOS_IDENTITY_UNCERTAIN", "Nacos registration has an unsupported routing weight");
        String observedId = value.getInstanceId();
        if (observedId == null || observedId.isBlank())
            observedId = value.getIp() + '#' + value.getPort() + '#' + value.getClusterName() + '#'
                    + expected.groupName() + "@@" + expected.serviceName();
        if (!"pending".equals(expected.nacosInstanceId()) && !expected.nacosInstanceId().equals(observedId))
            throw new ControllerException("NACOS_IDENTITY_UNCERTAIN", "Nacos instance identifier differs from persisted state");
        return new Registration(expected.serviceName(), expected.groupName(), expected.namespaceId(),
                expected.clusterName(), value.getIp(), value.getPort(), observedId, value.isEnabled(),
                value.isHealthy(), (int) value.getWeight(), value.isEphemeral(), Map.copyOf(value.getMetadata()));
    }

    private List<Instance> matching(Registration expected) {
        return matchingAddress(expected).stream()
                .filter(value -> expected.metadata().equals(value.getMetadata()))
                .toList();
    }

    private List<Instance> matchingAddress(Registration expected) {
        if (!namespace.equals(normalizeNamespace(expected.namespaceId())))
            throw new ControllerException("NACOS_NAMESPACE_MISMATCH", "Persisted namespace differs from controller configuration");
        try {
            List<Instance> instances = naming.getAllInstances(expected.serviceName(), expected.groupName(),
                    List.of(expected.clusterName()), false);
            return instances.stream().filter(value -> expected.ip().equals(value.getIp())
                    && expected.port() == value.getPort()
                    && expected.clusterName().equals(value.getClusterName()))
                    .toList();
        } catch (NacosException exception) {
            throw new ControllerException("NACOS_QUERY_FAILED", "Unable to query the exact Nacos instance", exception);
        }
    }

    private Instance instance(String address, int port, String cluster, boolean selectable, Map<String, String> metadata) {
        Instance value = new Instance();
        value.setIp(address);
        value.setPort(port);
        value.setClusterName(cluster);
        value.setEnabled(selectable);
        value.setHealthy(true);
        value.setWeight(selectable ? 1 : 0);
        value.setEphemeral(true);
        value.setMetadata(new LinkedHashMap<>(metadata));
        return value;
    }

    private void call(NacosCall call) {
        try { call.run(); }
        catch (NacosException exception) { throw new ControllerException("NACOS_WRITE_FAILED", "Nacos instance update failed", exception); }
    }

    private String normalizeNamespace(String value) { return value == null || value.isBlank() ? "public" : value; }
    @FunctionalInterface private interface NacosCall { void run() throws NacosException; }
}
