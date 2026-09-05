package world.willfrog.beta.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.state.AtomicJsonStore;
import world.willfrog.beta.validation.BetaContractValidator;

class BetaDeploymentServiceTest {
    @TempDir Path temporary;
    private ObjectMapper mapper;
    private AtomicJsonStore store;
    private FakeContainers containers;
    private FakeRegistry registry;
    private BetaDeploymentService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        store = new AtomicJsonStore(mapper, properties);
        containers = new FakeContainers();
        registry = new FakeRegistry();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers, registry,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void updateEnablesCandidateRemovesOldRegistrationThenNaturallyStopsOldContainer() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();
        assertTrue(registry.values.containsKey(oldId));

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        reconcile(3);
        JsonNode draining = state().path("drainingInstance");
        String newId = state().path("activeInstance").path("instanceId").asText();
        assertFalse(oldId.equals(newId));
        assertFalse(registry.values.containsKey(oldId));
        assertTrue(registry.values.containsKey(newId));
        assertEquals("beta", registry.values.get(newId).metadata().get("zone"));
        assertEquals("beta-main-001", registry.values.get(newId).metadata().get("alphafrog.deployment-id"));
        assertNull(registry.values.get(newId).metadata().get("dubbo.tag"));
        assertNull(registry.values.get(newId).metadata().get("tag"));
        assertFalse(draining.path("registrationRemovedAt").isNull());

        service.reconcileOne();
        assertEquals("STABLE", state().path("phase").asText());
        assertTrue(containers.stopped.containsKey("af-" + oldId));
        assertEquals(60, containers.stopTimeoutSeconds);
    }

    @Test
    void startupResumesAfterCandidateWasEnabledAndOldRegistrationWasRemoved() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        reconcile(2);
        assertEquals("SWITCHING_TRAFFIC", state().path("operation").path("phase").asText());
        JsonNode candidate = state().path("candidateInstance");
        ServiceRegistry.Registration expected = registry.values.get(candidate.path("instanceId").asText());
        registry.setSelectable(expected, true);
        registry.values.remove(oldId);

        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers, registry,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(state().path("lastError").isNull());

        service.reconcileOne();
        assertEquals("DRAINING_PREVIOUS", state().path("operation").path("phase").asText());
        assertEquals(candidate.path("instanceId").asText(), state().path("activeInstance").path("instanceId").asText());
        assertEquals(oldId, state().path("drainingInstance").path("instanceId").asText());
    }

    @Test
    void laneCandidateCarriesFinalStaticTagBeforeItBecomesSelectable() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        service.submitManifest(manifest("beta-lane-a", 1, "release-1", '1', 'c', 'd', "lane-a", 38080));
        service.reconcileOne();
        JsonNode candidate = state("lane-a").path("candidateInstance");
        ServiceRegistry.Registration value = registry.values.get(candidate.path("instanceId").asText());
        assertFalse(value.enabled());
        assertEquals(0, value.weight());
        assertEquals("lane-a", value.metadata().get("dubbo.tag"));
        assertEquals("lane-a", value.metadata().get("tag"));
        assertEquals("beta", value.metadata().get("zone"));
    }

    @Test
    void unhealthyCandidateIsRemovedWhileOldInstanceContinuesServing() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();
        containers.health = ContainerRuntime.ContainerObservation.Health.UNHEALTHY;

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        service.reconcileOne();
        service.reconcileOne();

        assertEquals("STABLE", state().path("phase").asText());
        assertEquals(oldId, state().path("activeInstance").path("instanceId").asText());
        assertTrue(registry.values.containsKey(oldId));
        assertFalse(state().path("lastError").isNull());
    }

    @Test
    void deleteRemovesRegistrationBeforeSendingTheCommonStopDeadline() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String activeId = state().path("activeInstance").path("instanceId").asText();

        service.requestDelete("beta-main-001");
        service.reconcileOne();
        assertFalse(registry.values.containsKey(activeId));
        assertFalse(state().path("drainingInstance").path("registrationRemovedAt").isNull());
        service.reconcileOne();
        assertEquals(0, store.snapshot().path("deployments").size());
        assertEquals(60, containers.stopTimeoutSeconds);
    }

    @Test
    void laneRequiresMainBetaAndMainBetaCannotBeDeletedWhileLaneProviderExists() {
        ControllerException missingMain = assertThrows(ControllerException.class,
                () -> service.submitManifest(manifest("beta-lane-a", 1, "release-1", '1', 'c', 'd', "lane-a", 38080)));
        assertEquals("MAIN_BETA_PROVIDER_REQUIRED", missingMain.code());

        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        service.submitManifest(manifest("beta-lane-a", 1, "release-1", '1', 'c', 'd', "lane-a", 38080));
        reconcile(3);

        ControllerException activeLane = assertThrows(ControllerException.class,
                () -> service.requestDelete("beta-main-001"));
        assertEquals("MAIN_BETA_PROVIDER_REQUIRED", activeLane.code());
    }

    @Test
    void manifestDeadlineMustMatchTheControllerWideDeadline() {
        ObjectNode value = manifest(1, "release-1", '1', 'a', 'b', "main-beta");
        ObjectNode spec = (ObjectNode) value.path("services").path(0);
        ((ObjectNode) spec.path("runtime")).put("applicationDrainSeconds", 30);
        ((ObjectNode) spec.path("runtime")).put("drainGraceSeconds", 30);
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));

        ControllerException mismatch = assertThrows(ControllerException.class,
                () -> service.submitManifest(value));

        assertEquals("MANIFEST_INVALID", mismatch.code());
    }

    @Test
    void retryUsesThePersistedStopDeadlineInsteadOfStartingANewWindow() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        reconcile(3);
        containers.leaveRunningAfterStop = true;
        service.reconcileOne();
        assertEquals("FAILED", state().path("phase").asText());
        assertEquals("2026-09-01T00:01:00Z", state().path("drainingInstance").path("stopDeadline").asText());

        containers.leaveRunningAfterStop = false;
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers, registry,
                Clock.fixed(Instant.parse("2026-09-01T00:00:30Z"), ZoneOffset.UTC));
        service.retry("beta-main-001", "agent-service");
        service.reconcileOne();

        assertEquals(30, containers.stopTimeoutSeconds);
        assertEquals("STABLE", state().path("phase").asText());
    }

    private void reconcile(int count) {
        for (int index = 0; index < count; index++) service.reconcileOne();
    }

    private JsonNode state() {
        return store.snapshot().path("deployments").path(0).path("services").path(0);
    }

    private JsonNode state(String scope) {
        for (JsonNode deployment : store.snapshot().path("deployments")) {
            if (scope.equals(deployment.path("trafficScopeId").asText())) {
                return deployment.path("services").path(0);
            }
        }
        throw new AssertionError("Missing traffic scope " + scope);
    }

    private ObjectNode manifest(int version, String release, char git, char repository, char image, String scope) {
        return manifest("beta-main-001", version, release, git, repository, image, scope, 28080);
    }

    private ObjectNode manifest(String deploymentId, int version, String release, char git,
                                char repository, char image, String scope, int firstHostPort) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("deploymentId", deploymentId);
        root.put("trafficScopeId", scope);
        root.put("manifestVersion", version);
        root.put("gitCommit", String.valueOf(git).repeat(40));
        root.putObject("owner").put("ownerId", "frog");
        root.put("createdAt", "2026-09-01T00:00:00Z");
        root.put("expiresAt", "2026-09-08T00:00:00Z");
        ObjectNode spec = root.putArray("services").addObject();
        spec.put("serviceName", "agent-service");
        spec.put("dubboServiceKey", "langchain/com.alphafrog.AgentService");
        spec.put("releaseId", release);
        spec.put("serviceSpecSha256", "0".repeat(64));
        spec.put("machineId", "beta-machine-1");
        ObjectNode imageNode = spec.putObject("image");
        imageNode.put("repositoryDigest", "registry.local/agent@sha256:" + String.valueOf(repository).repeat(64));
        imageNode.put("localImageId", "sha256:" + String.valueOf(image).repeat(64));
        ObjectNode runtime = spec.putObject("runtime");
        runtime.put("containerPort", 18080);
        runtime.putArray("hostPorts").add(firstHostPort).add(firstHostPort + 1);
        runtime.put("healthCheckProfile", "CONTROLLER_TCP_V1");
        runtime.put("readinessTimeoutSeconds", 120);
        runtime.put("shutdownProfile", "SPRING_BOOT_HTTP_DUBBO_V1");
        runtime.put("applicationDrainSeconds", 60);
        runtime.put("drainGraceSeconds", 60);
        ObjectNode registration = spec.putObject("registration");
        registration.put("serviceName", "providers:com.alphafrog.AgentService::langchain");
        registration.put("groupName", "alphafrog-beta");
        registration.put("namespaceId", "public");
        registration.put("clusterName", "DEFAULT");
        registration.put("applicationName", "agent-langchain-service");
        spec.putNull("runtimeConfigSha256");
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));
        return root;
    }

    private final class FakeContainers implements ContainerRuntime {
        ContainerObservation.Health health = ContainerObservation.Health.HEALTHY;
        final Map<String, ContainerObservation> values = new LinkedHashMap<>();
        final Map<String, Boolean> stopped = new LinkedHashMap<>();
        int stopTimeoutSeconds;
        boolean leaveRunningAfterStop;

        @Override public ContainerObservation create(JsonNode manifest, JsonNode spec, CandidatePlan plan) {
            String name = "af-" + plan.instanceId();
            ContainerObservation value = new ContainerObservation(String.format("%064x", values.size() + 1),
                    name, "10.0.0.8", plan.hostPort(), true, health);
            values.put(name, value);
            return value;
        }
        @Override public ContainerObservation inspect(String machineId, String name) {
            ContainerObservation value = values.get(name);
            if (value == null) return new ContainerObservation("", name, "", 0, false, ContainerObservation.Health.MISSING);
            return new ContainerObservation(value.containerId(), name, value.endpointAddress(), value.hostPort(),
                    !Boolean.TRUE.equals(stopped.get(name)), health);
        }
        @Override public void stop(String machineId, String name, int timeoutSeconds) {
            stopTimeoutSeconds = timeoutSeconds;
            if (!leaveRunningAfterStop) stopped.put(name, true);
        }
        @Override public void remove(String machineId, String name) { values.remove(name); }
    }

    private static final class FakeRegistry implements ServiceRegistry {
        final Map<String, Registration> values = new LinkedHashMap<>();
        @Override public Registration register(JsonNode manifest, JsonNode service, String instanceId,
                                               String generation, String address, int port, boolean selectable) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("alphafrog.deployment-id", manifest.path("deploymentId").asText());
            metadata.put("alphafrog.traffic-scope-id", manifest.path("trafficScopeId").asText());
            metadata.put("alphafrog.release-id", service.path("releaseId").asText());
            metadata.put("alphafrog.deployment-generation-id", generation);
            metadata.put("alphafrog.instance-id", instanceId);
            metadata.put("zone", "beta");
            metadata.put("application", service.path("registration").path("applicationName").asText());
            metadata.put("category", "providers");
            metadata.put("dynamic", "true");
            metadata.put("group", "langchain");
            metadata.put("interface", "com.alphafrog.AgentService");
            metadata.put("path", "com.alphafrog.AgentService");
            metadata.put("protocol", "tri");
            metadata.put("side", "provider");
            metadata.put("version", "");
            if (!"main-beta".equals(manifest.path("trafficScopeId").asText())) {
                metadata.put("tag", manifest.path("trafficScopeId").asText());
                metadata.put("dubbo.tag", manifest.path("trafficScopeId").asText());
            }
            Registration value = new Registration(service.path("registration").path("serviceName").asText(),
                    "alphafrog-beta", "public", "DEFAULT", address, port, "nacos:" + instanceId,
                    selectable, true, selectable ? 1 : 0, true, Map.copyOf(metadata));
            values.put(instanceId, value);
            return value;
        }
        @Override public Registration setSelectable(Registration expected, boolean selectable) {
            Registration value = new Registration(expected.serviceName(), expected.groupName(), expected.namespaceId(),
                    expected.clusterName(), expected.ip(), expected.port(), expected.nacosInstanceId(), selectable,
                    true, selectable ? 1 : 0, true, expected.metadata());
            values.put(expected.metadata().get("alphafrog.instance-id"), value);
            return value;
        }
        @Override public Registration observe(Registration expected) { return values.get(expected.metadata().get("alphafrog.instance-id")); }
        @Override public Optional<Registration> find(Registration expected) {
            return Optional.ofNullable(values.get(expected.metadata().get("alphafrog.instance-id")));
        }
        @Override public void unregister(Registration expected) { values.remove(expected.metadata().get("alphafrog.instance-id")); }
        @Override public void assertNoUntrackedRegistrations(JsonNode manifest, JsonNode service, Set<String> tracked) { }
    }
}
