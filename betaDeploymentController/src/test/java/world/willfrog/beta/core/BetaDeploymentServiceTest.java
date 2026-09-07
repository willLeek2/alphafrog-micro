package world.willfrog.beta.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private FakeRegistrationProbe registrationProbe;
    private BetaDeploymentService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        store = new AtomicJsonStore(mapper, properties);
        containers = new FakeContainers();
        registrationProbe = new FakeRegistrationProbe();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void updatePromotesHealthyRegisteredCandidateThenNaturallyStopsOldContainer() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        reconcile(3);
        JsonNode draining = state().path("drainingInstance");
        String newId = state().path("activeInstance").path("instanceId").asText();
        assertFalse(oldId.equals(newId));
        assertEquals(oldId, draining.path("instanceId").asText());
        assertEquals("10.0.0.8", registrationProbe.lastAddress);
        assertEquals(28081, registrationProbe.lastPort);

        service.reconcileOne();
        assertEquals("STABLE", state().path("phase").asText());
        assertTrue(containers.stopped.containsKey("af-" + oldId));
        assertEquals(60, containers.stopTimeoutSeconds);
    }

    @Test
    void startupResumesFromPersistedTrafficSwitchCheckpoint() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        reconcile(2);
        assertEquals("SWITCHING_TRAFFIC", state().path("operation").path("phase").asText());
        JsonNode candidate = state().path("candidateInstance");

        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(state().path("lastError").isNull());

        service.reconcileOne();
        assertEquals("DRAINING_PREVIOUS", state().path("operation").path("phase").asText());
        assertEquals(candidate.path("instanceId").asText(), state().path("activeInstance").path("instanceId").asText());
        assertEquals(oldId, state().path("drainingInstance").path("instanceId").asText());
    }

    @Test
    void healthyCandidateWaitsUntilItsSelfRegistrationIsVisible() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        registrationProbe.visible = false;
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        service.reconcileOne();
        service.reconcileOne();
        assertEquals("WAITING_CANDIDATE_READINESS", state().path("operation").path("phase").asText());
        assertEquals("release-1", state().path("activeInstance").path("releaseId").asText());

        registrationProbe.visible = true;
        service.reconcileOne();
        assertEquals("SWITCHING_TRAFFIC", state().path("operation").path("phase").asText());
    }

    @Test
    void serviceWithoutDubboProviderUsesContainerHealthOnly() {
        ObjectNode value = manifest(1, "release-1", '1', 'a', 'b', "main-beta");
        ObjectNode spec = (ObjectNode) value.path("services").path(0);
        spec.remove("registration");
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));
        registrationProbe.visible = false;

        service.submitManifest(value);
        reconcile(3);

        assertEquals("STABLE", state().path("phase").asText());
        assertEquals(0, registrationProbe.calls);
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
        assertFalse(state().path("lastError").isNull());
    }

    @Test
    void deleteMovesInstanceToDrainingBeforeSendingTheCommonStopDeadline() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        String activeId = state().path("activeInstance").path("instanceId").asText();

        service.requestDelete("beta-main-001");
        service.reconcileOne();
        assertEquals(activeId, state().path("drainingInstance").path("instanceId").asText());
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
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe,
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

    private static final class FakeRegistrationProbe implements CandidateRegistrationProbe {
        boolean visible = true;
        String lastAddress;
        int lastPort;
        int calls;

        @Override public boolean isVisible(JsonNode service, String address, int port) {
            calls++;
            lastAddress = address;
            lastPort = port;
            return visible;
        }
    }
}
