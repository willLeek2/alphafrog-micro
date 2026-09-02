package world.willfrog.beta.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.state.AtomicJsonStore;
import world.willfrog.beta.validation.BetaContractValidator;

class BetaDeploymentServiceTest {
    @TempDir Path temporary;
    private ObjectMapper mapper;
    private AtomicJsonStore store;
    private FakeContainers containers;
    private FakeRegistry registry;
    private FakeRetirement retirement;
    private BetaDeploymentService service;
    private Path retirementToken;
    private UnaryOperator<ObjectNode> routeMutation;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        retirementToken = temporary.resolve("retirement-token");
        Files.writeString(retirementToken, "r".repeat(48));
        try { Files.setPosixFilePermissions(retirementToken, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        properties.setRetirementTokenFile(retirementToken);
        store = new AtomicJsonStore(mapper, properties);
        containers = new FakeContainers();
        registry = new FakeRegistry();
        retirement = new FakeRetirement();
        routeMutation = UnaryOperator.identity();
        service = serviceAt(Clock.systemUTC());
    }

    @Test
    void createSwitchUpdateDrainAndDeleteFollowThePersistedRoute() {
        ObjectNode first = manifest(1, "release-1", '1', 'a', 'b');
        service.submitManifest(first);
        assertEquals("STARTING_CANDIDATE", operationPhase());

        service.reconcileOne();
        assertEquals("WAITING_CANDIDATE_READINESS", operationPhase());
        service.reconcileOne();
        assertEquals("SWITCHING_TRAFFIC", operationPhase());
        service.reconcileOne();
        assertEquals("STABLE", serviceState().path("phase").asText());
        String oldInstance = serviceState().path("activeInstance").path("instanceId").asText();
        assertEquals(oldInstance, service.route("main-beta", "agent-service")
                .path("route").path("defaultInstanceId").asText());

        ObjectNode second = manifest(2, "release-2", '2', 'c', 'd');
        service.submitManifest(second);
        assertEquals("UPDATING", serviceState().path("phase").asText());
        String updateOperation = serviceState().path("operation").path("operationId").asText();
        service.reconcileOne();
        assertEquals(updateOperation, serviceState().path("operation").path("operationId").asText());
        service.reconcileOne();
        assertEquals(updateOperation, serviceState().path("operation").path("operationId").asText());
        service.reconcileOne();
        JsonNode switching = serviceState();
        assertEquals("DRAINING_PREVIOUS", switching.path("operation").path("phase").asText(), switching::toPrettyString);
        assertEquals(updateOperation, switching.path("operation").path("operationId").asText());
        String newInstance = switching.path("activeInstance").path("instanceId").asText();
        assertFalse(oldInstance.equals(newInstance));
        assertEquals(newInstance, service.route("main-beta", "agent-service")
                .path("route").path("defaultInstanceId").asText());
        assertEquals(oldInstance, switching.path("drainingInstance").path("instanceId").asText());
        assertEquals(switching.path("route").path("updatedAt"),
                switching.path("drainingInstance").path("trafficRemovedAt"));

        service.reconcileOne();
        assertEquals("STABLE", serviceState().path("phase").asText());
        assertTrue(serviceState().path("drainingInstance").isNull());
        assertEquals(1, retirement.calls);
        assertTrue(containers.stopped.containsKey("af-" + oldInstance));

        service.requestDelete("beta-main-001");
        assertEquals("REMOVING_TRAFFIC", operationPhase());
        service.reconcileOne();
        assertTrue(service.route("main-beta", "agent-service").path("route").path("defaultInstanceId").isNull());
        service.reconcileOne();
        assertEquals(0, store.snapshot().path("deployments").size());
        assertFalse(Files.exists(temporary.resolve("state/deployments/beta-main-001/manifest.json")));
    }

    @Test
    void unhealthyCandidateFailsCleanlyWithoutChangingOldRouteAndCanBeRetried() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        String oldInstance = serviceState().path("activeInstance").path("instanceId").asText();

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        containers.health = ContainerRuntime.ContainerObservation.Health.UNHEALTHY;
        service.reconcileOne();
        JsonNode failed = serviceState();
        assertEquals("STABLE", failed.path("phase").asText());
        assertEquals("CLEAN_RETRYABLE", failed.path("lastError").path("recoveryClass").asText(), failed::toPrettyString);
        assertTrue(failed.path("candidateInstance").isNull());
        assertEquals(oldInstance, failed.path("route").path("defaultInstanceId").asText());

        containers.health = ContainerRuntime.ContainerObservation.Health.HEALTHY;
        service.retry("beta-main-001", "agent-service");
        assertEquals("STARTING_CANDIDATE", operationPhase());
    }

    @Test
    void rejectsServiceRemovalPortMovesAndConcurrentScopeOwners() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        ObjectNode moved = manifest(2, "release-2", '2', 'c', 'd');
        ((com.fasterxml.jackson.databind.node.ArrayNode) moved.path("services").path(0)
                .path("runtime").path("hostPorts")).set(0, mapper.getNodeFactory().numberNode(39090));
        ((ObjectNode) moved.path("services").path(0)).put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, moved.path("services").path(0)));
        assertEquals("SERVICE_LOCATION_IMMUTABLE", assertThrows(ControllerException.class,
                () -> service.submitManifest(moved)).code());

        ObjectNode other = manifest(1, "release-x", '3', 'e', 'f');
        other.put("deploymentId", "beta-other-001");
        assertEquals("TRAFFIC_SCOPE_CONFLICT", assertThrows(ControllerException.class,
                () -> service.submitManifest(other)).code());

        ObjectNode portConflict = manifest(1, "release-y", '4', '6', '7');
        portConflict.put("deploymentId", "beta-other-002");
        portConflict.put("trafficScopeId", "lane-002");
        assertEquals("HOST_PORT_CONFLICT", assertThrows(ControllerException.class,
                () -> service.submitManifest(portConflict)).code());
        assertFalse(store.hasManifest("beta-other-002"));
    }

    @Test
    void invalidServiceDigestAndWideRetirementSecretFailClosed() throws Exception {
        ObjectNode invalid = manifest(1, "release-1", '1', 'a', 'b');
        ((ObjectNode) invalid.path("services").path(0)).put("serviceSpecSha256", "0".repeat(64));
        assertEquals("MANIFEST_INVALID", assertThrows(ControllerException.class,
                () -> service.submitManifest(invalid)).code());

        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        try { Files.setPosixFilePermissions(retirementToken, PosixFilePermissions.fromString("rw-r--r--")); }
        catch (UnsupportedOperationException ignored) { return; }
        service.reconcileOne();
        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals("SECRET_FILE_PERMISSIONS", serviceState().path("lastError").path("code").asText());
    }

    @Test
    void startupPromotesACompleteLeadingManifestAndFinishesTheDeleteWindow() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();

        ObjectNode next = manifest(2, "release-2", '2', 'c', 'd');
        store.writeManifest("beta-main-001", next);
        service.verifyPersistentStateAtStartup();
        JsonNode recovered = store.snapshot().path("deployments").path(0);
        assertEquals(2, recovered.path("acceptedManifestVersion").asLong());
        assertEquals("STARTING_CANDIDATE", recovered.path("services").path(0)
                .path("operation").path("phase").asText());

        store.update(state -> {
            ObjectNode deployment = (ObjectNode) state.path("deployments").path(0);
            deployment.put("phase", "DELETING");
            ((com.fasterxml.jackson.databind.node.ArrayNode) deployment.path("services")).removeAll();
            return null;
        });
        store.deleteManifest("beta-main-001");
        service.verifyPersistentStateAtStartup();
        assertEquals(0, store.snapshot().path("deployments").size());
    }

    @Test
    void operationIdentityIsSeparateFromThePreallocatedCandidateIdentity() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        JsonNode operation = serviceState().path("operation");
        assertTrue(operation.path("operationId").asText().startsWith("op-"));
        assertTrue(operation.path("candidateInstanceId").asText().startsWith("i-"));
        assertFalse(operation.path("operationId").equals(operation.path("candidateInstanceId")));
    }

    @Test
    void explicitRetryResumesThePersistedDrainInsteadOfStartingAnotherCandidate() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();

        retirement.fail = true;
        service.reconcileOne();
        assertEquals("FAILED", serviceState().path("phase").asText());
        assertTrue(serviceState().path("drainingInstance").isObject());

        retirement.fail = false;
        service.retry("beta-main-001", "agent-service");
        assertEquals("DRAINING_PREVIOUS", operationPhase());
        assertTrue(serviceState().path("candidateInstance").isNull());
        service.reconcileOne();
        assertEquals("STABLE", serviceState().path("phase").asText());
    }

    @Test
    void deletingACleanFailedCreateRemovesTheEmptyDeploymentWithoutAContainerStep() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        containers.health = ContainerRuntime.ContainerObservation.Health.UNHEALTHY;
        service.reconcileOne();
        assertEquals("FAILED", serviceState().path("phase").asText());

        ObjectNode result = service.requestDelete("beta-main-001");

        assertEquals("DELETED", result.path("phase").asText());
        assertEquals(0, store.snapshot().path("deployments").size());
        assertFalse(store.hasManifest("beta-main-001"));
    }

    @Test
    void unexpectedCandidateTrafficIsPausedUntilExternalFactsAreCorrected() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        String active = serviceState().path("activeInstance").path("instanceId").asText();

        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        String candidate = serviceState().path("candidateInstance").path("instanceId").asText();
        registry.forceSelectable(candidate, true);

        service.reconcileOne();

        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals("FACTS_UNCERTAIN", serviceState().path("lastError").path("recoveryClass").asText());
        assertEquals(candidate, serviceState().path("candidateInstance").path("instanceId").asText());
        assertEquals(active, serviceState().path("route").path("defaultInstanceId").asText());
        assertEquals("CANDIDATE_SELECTABILITY_CONFLICT", assertThrows(ControllerException.class,
                () -> service.retry("beta-main-001", "agent-service")).code());

        registry.forceSelectable(candidate, false);
        service.retry("beta-main-001", "agent-service");
        assertEquals("WAITING_CANDIDATE_READINESS", operationPhase());
    }

    @Test
    void startupPausesAStableServiceWhenItsPersistedContainerIsMissing() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        String active = serviceState().path("activeInstance").path("containerName").asText();
        containers.values.remove(active);

        service.verifyPersistentStateAtStartup();

        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals("CONTAINER_FACT_MISSING", serviceState().path("lastError").path("code").asText());
        assertEquals("FACTS_UNCERTAIN", serviceState().path("lastError").path("recoveryClass").asText());
    }

    @Test
    void restartFinishesCleanupWhenPreStopCompletedBeforeTheOldContainerDisappeared() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        String drainingContainer = serviceState().path("drainingInstance").path("containerName").asText();
        String drainingInstance = serviceState().path("drainingInstance").path("instanceId").asText();
        store.update(state -> {
            ObjectNode draining = (ObjectNode) state.path("deployments").path(0).path("services").path(0)
                    .path("drainingInstance");
            draining.put("preStopCompletedAt", "2026-09-03T00:01:00Z");
            ((ObjectNode) draining.path("registration")).put("enabled", false).put("weight", 0);
            return null;
        });
        containers.values.remove(drainingContainer);
        registry.values.remove(drainingInstance);

        service.verifyPersistentStateAtStartup();
        service.reconcileOne();

        assertEquals("STABLE", serviceState().path("phase").asText());
        assertTrue(serviceState().path("drainingInstance").isNull());
    }

    @Test
    void deploymentsShareOneDeterministicExternalOperationQueue() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        ObjectNode other = manifest(1, "release-2", '2', 'c', 'd');
        other.put("deploymentId", "beta-lane-002");
        other.put("trafficScopeId", "lane-002");
        ObjectNode spec = (ObjectNode) other.path("services").path(0);
        com.fasterxml.jackson.databind.node.ArrayNode ports =
                (com.fasterxml.jackson.databind.node.ArrayNode) spec.path("runtime").path("hostPorts");
        ports.set(0, mapper.getNodeFactory().numberNode(28180));
        ports.set(1, mapper.getNodeFactory().numberNode(28181));
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));

        service.submitManifest(other);

        assertTrue(deployment("beta-lane-002").path("services").path(0).path("operation").isNull());
        assertEquals(1, activeOperations());
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        assertEquals("STARTING_CANDIDATE", deployment("beta-lane-002").path("services").path(0)
                .path("operation").path("phase").asText());
        assertEquals(1, activeOperations());
    }

    @Test
    void aHigherManifestRequeuesACleanFailedInitialCreate() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        containers.health = ContainerRuntime.ContainerObservation.Health.UNHEALTHY;
        service.reconcileOne();
        assertEquals("FAILED", serviceState().path("phase").asText());

        containers.health = ContainerRuntime.ContainerObservation.Health.HEALTHY;
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));

        assertEquals("CREATING", serviceState().path("phase").asText());
        assertEquals("STARTING_CANDIDATE", operationPhase());
        assertTrue(serviceState().path("lastError").isNull());
    }

    @Test
    void everyRepeatedContainerStopGetsAFreshPersistedDeadline() {
        Instant firstAttempt = Instant.parse("2026-09-03T01:00:00Z");
        service = serviceAt(firstAttempt);
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();

        containers.failStop = true;
        service.reconcileOne();
        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals(firstAttempt.toString(), serviceState().path("drainingInstance")
                .path("stopSignalRequestedAt").asText());
        assertEquals(firstAttempt.plusSeconds(60).toString(), serviceState().path("drainingInstance")
                .path("stopDeadline").asText());

        Instant secondAttempt = firstAttempt.plusSeconds(300);
        service = serviceAt(secondAttempt);
        service.retry("beta-main-001", "agent-service");
        service.reconcileOne();

        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals(secondAttempt.toString(), serviceState().path("drainingInstance")
                .path("stopSignalRequestedAt").asText());
        assertEquals(secondAttempt.plusSeconds(60).toString(), serviceState().path("drainingInstance")
                .path("stopDeadline").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "generation"})
    void switchReadbackRequiresTheExactCandidateEndpointAndGeneration(String fault) {
        prepareUpdateForSwitch();
        String oldInstance = serviceState().path("activeInstance").path("instanceId").asText();
        routeMutation = route -> {
            if ("endpoint".equals(fault)) {
                ((ObjectNode) route.path("endpoint")).put("address", "10.0.0.99");
            } else {
                ((ObjectNode) route.path("route")).put("defaultDeploymentGenerationId", "gen-" + "f".repeat(64));
            }
            return route;
        };

        ControllerException failure = assertThrows(ControllerException.class, service::reconcileOne);

        assertEquals("ROUTE_READBACK_FAILED", failure.code());
        assertEquals("DRAINING_PREVIOUS", operationPhase());
        assertTrue(registry.values.get(oldInstance).enabled());
        assertEquals(0, retirement.calls);
    }

    @Test
    void deleteReadbackRequiresANewEmptyRouteVersionAndClearedGeneration() {
        createStableService();
        String oldInstance = serviceState().path("activeInstance").path("instanceId").asText();
        long oldRouteVersion = serviceState().path("route").path("routeVersion").asLong();
        service.requestDelete("beta-main-001");
        routeMutation = route -> {
            ObjectNode pointer = (ObjectNode) route.path("route");
            pointer.put("routeVersion", oldRouteVersion);
            pointer.put("defaultDeploymentGenerationId", "gen-" + "f".repeat(64));
            return route;
        };

        ControllerException failure = assertThrows(ControllerException.class, service::reconcileOne);

        assertEquals("ROUTE_READBACK_FAILED", failure.code());
        assertEquals("DRAINING_ACTIVE", operationPhase());
        assertTrue(registry.values.get(oldInstance).enabled());
        assertEquals(0, retirement.calls);
    }

    @Test
    void drainReadbackRejectsAWrongEndpointBeforeDisablingTheOldInstance() {
        prepareUpdateForSwitch();
        service.reconcileOne();
        String oldInstance = serviceState().path("drainingInstance").path("instanceId").asText();
        routeMutation = route -> {
            ((ObjectNode) route.path("endpoint")).put("port", 6553);
            return route;
        };

        service.reconcileOne();

        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals("ROUTE_READBACK_FAILED", serviceState().path("lastError").path("code").asText());
        assertTrue(registry.values.get(oldInstance).enabled());
        assertEquals(0, retirement.calls);
    }

    @Test
    void startupReadbackRejectsAnEndpointThatDoesNotMatchThePersistedRoute() {
        createStableService();
        String activeInstance = serviceState().path("activeInstance").path("instanceId").asText();
        routeMutation = route -> {
            ((ObjectNode) route.path("endpoint")).put("address", "10.0.0.99");
            return route;
        };

        service.verifyPersistentStateAtStartup();

        assertEquals("FAILED", serviceState().path("phase").asText());
        assertEquals("ROUTE_READBACK_FAILED", serviceState().path("lastError").path("code").asText());
        assertTrue(registry.values.get(activeInstance).enabled());
        assertEquals(0, retirement.calls);
    }

    @Test
    void initialCreateRouteFailurePausesTheDeploymentAndRetryConfirmsThePublishedInstance() {
        service.submitManifest(twoServiceManifest());
        service.reconcileOne();
        service.reconcileOne();
        String publishedInstance = serviceState().path("candidateInstance").path("instanceId").asText();
        routeMutation = route -> {
            ((ObjectNode) route.path("endpoint")).put("address", "10.0.0.99");
            return route;
        };

        service.reconcileOne();

        JsonNode failed = namedService("agent-service");
        assertEquals("FAILED", failed.path("phase").asText());
        assertEquals("FACTS_UNCERTAIN", failed.path("lastError").path("recoveryClass").asText());
        assertEquals(publishedInstance, failed.path("activeInstance").path("instanceId").asText());
        assertTrue(failed.path("operation").isNull());
        assertTrue(namedService("tools-service").path("operation").isNull());
        assertEquals(1, containers.values.size());

        service.reconcileOne();
        assertTrue(namedService("tools-service").path("operation").isNull());
        assertEquals(1, containers.values.size());

        routeMutation = UnaryOperator.identity();
        service.retry("beta-main-001", "agent-service");

        JsonNode confirmed = namedService("agent-service");
        assertEquals("STABLE", confirmed.path("phase").asText());
        assertEquals(publishedInstance, confirmed.path("activeInstance").path("instanceId").asText());
        assertTrue(confirmed.path("candidateInstance").isNull());
        assertEquals(1, containers.values.size());
        assertEquals("STARTING_CANDIDATE", namedService("tools-service").path("operation").path("phase").asText());
    }

    private void createStableService() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b'));
        service.reconcileOne();
        service.reconcileOne();
        service.reconcileOne();
    }

    private void prepareUpdateForSwitch() {
        createStableService();
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd'));
        service.reconcileOne();
        service.reconcileOne();
        assertEquals("SWITCHING_TRAFFIC", operationPhase());
    }

    private BetaDeploymentService serviceAt(Instant instant) {
        return serviceAt(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private BetaDeploymentService serviceAt(Clock clock) {
        return new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper), containers,
                registry, retirement, retirementToken, clock) {
            @Override
            public ObjectNode route(String trafficScopeId, String serviceName) {
                return routeMutation.apply(super.route(trafficScopeId, serviceName));
            }
        };
    }

    private ObjectNode manifest(long version, String release, char git, char repository, char local) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("deploymentId", "beta-main-001");
        manifest.put("trafficScopeId", "main-beta");
        manifest.put("manifestVersion", version);
        manifest.put("gitCommit", Character.toString(git).repeat(40));
        manifest.putObject("owner").put("ownerId", "frog");
        manifest.put("createdAt", "2026-09-03T00:00:00Z");
        manifest.put("expiresAt", "2026-09-10T00:00:00Z");
        ObjectNode spec = manifest.putArray("services").addObject();
        spec.put("serviceName", "agent-service");
        spec.put("releaseId", release);
        spec.put("serviceSpecSha256", "0".repeat(64));
        spec.put("machineId", "beta-machine-1");
        spec.putObject("image")
                .put("repositoryDigest", "registry.local/agent-service@sha256:" + Character.toString(repository).repeat(64))
                .put("localImageId", "sha256:" + Character.toString(local).repeat(64));
        ObjectNode runtime = spec.putObject("runtime");
        runtime.put("containerPort", 18080);
        runtime.putArray("hostPorts").add(28080).add(28081);
        runtime.put("healthCheckProfile", "CONTROLLER_TCP_V1");
        runtime.put("readinessTimeoutSeconds", 120);
        runtime.put("preStopPolicy", "AGENT_RETIRE_GENERATION_V1");
        runtime.put("shutdownProfile", "SPRING_BOOT_HTTP_DUBBO_V1");
        runtime.put("applicationDrainSeconds", 55);
        runtime.put("drainGraceSeconds", 60);
        ObjectNode registration = spec.putObject("registration");
        registration.put("serviceName", "com.alphafrog.AgentService:1.0@@providers");
        registration.put("groupName", "DEFAULT_GROUP");
        registration.put("namespaceId", "public");
        registration.put("clusterName", "DEFAULT");
        spec.putNull("runtimeConfigSha256");
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));
        return manifest;
    }

    private ObjectNode twoServiceManifest() {
        ObjectNode manifest = manifest(1, "release-1", '1', 'a', 'b');
        ObjectNode tools = ((ObjectNode) manifest.path("services").path(0)).deepCopy();
        tools.put("serviceName", "tools-service");
        tools.put("releaseId", "tools-release-1");
        ((ObjectNode) tools.path("image"))
                .put("repositoryDigest", "registry.local/tools-service@sha256:" + "e".repeat(64))
                .put("localImageId", "sha256:" + "f".repeat(64));
        com.fasterxml.jackson.databind.node.ArrayNode ports =
                (com.fasterxml.jackson.databind.node.ArrayNode) tools.path("runtime").path("hostPorts");
        ports.set(0, mapper.getNodeFactory().numberNode(29080));
        ports.set(1, mapper.getNodeFactory().numberNode(29081));
        ((ObjectNode) tools.path("registration")).put("serviceName", "com.alphafrog.ToolsService:1.0@@providers");
        tools.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, tools));
        ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("services")).add(tools);
        return manifest;
    }

    private JsonNode serviceState() { return store.snapshot().path("deployments").path(0).path("services").path(0); }
    private JsonNode namedService(String serviceName) {
        for (JsonNode value : store.snapshot().path("deployments").path(0).path("services"))
            if (serviceName.equals(value.path("serviceName").asText())) return value;
        return mapper.missingNode();
    }
    private String operationPhase() { return serviceState().path("operation").path("phase").asText(); }
    private JsonNode deployment(String deploymentId) {
        for (JsonNode deployment : store.snapshot().path("deployments"))
            if (deploymentId.equals(deployment.path("deploymentId").asText())) return deployment;
        return mapper.missingNode();
    }
    private int activeOperations() {
        int operations = 0;
        for (JsonNode deployment : store.snapshot().path("deployments"))
            for (JsonNode service : deployment.path("services"))
                if (service.path("operation").isObject()) operations++;
        return operations;
    }

    private final class FakeContainers implements ContainerRuntime {
        ContainerObservation.Health health = ContainerObservation.Health.HEALTHY;
        boolean failStop;
        final Map<String, ContainerObservation> values = new LinkedHashMap<>();
        final Map<String, Boolean> stopped = new LinkedHashMap<>();

        @Override public ContainerObservation create(JsonNode manifest, JsonNode spec, CandidatePlan plan, String token) {
            String name = "af-" + plan.instanceId();
            ContainerObservation value = new ContainerObservation(
                    String.format("%064x", values.size() + 1),
                    name, "10.0.0.8", plan.hostPort(), true, health);
            values.put(name, value);
            return value;
        }
        @Override public ContainerObservation inspect(String machineId, String name) {
            ContainerObservation value = values.get(name);
            if (value == null) return new ContainerObservation("", name, "", 0, false, ContainerObservation.Health.MISSING);
            boolean running = !Boolean.TRUE.equals(stopped.get(name));
            return new ContainerObservation(value.containerId(), name, value.endpointAddress(), value.hostPort(), running, health);
        }
        @Override public void stop(String machineId, String name, int timeoutSeconds) {
            if (failStop) throw new ControllerException("CONTAINER_STOP_FAILED", "simulated stop failure");
            stopped.put(name, true);
        }
        @Override public void remove(String machineId, String name) { values.remove(name); }
    }

    private static final class FakeRegistry implements ServiceRegistry {
        private final Map<String, Registration> values = new LinkedHashMap<>();
        @Override public Registration register(JsonNode manifest, JsonNode service, String instanceId, String generation,
                                               String address, int port, boolean selectable) {
            Map<String, String> metadata = Map.of(
                    "alphafrog.traffic-scope-id", manifest.path("trafficScopeId").asText(),
                    "alphafrog.release-id", service.path("releaseId").asText(),
                    "alphafrog.deployment-generation-id", generation,
                    "alphafrog.instance-id", instanceId);
            Registration value = new Registration(service.path("registration").path("serviceName").asText(),
                    "DEFAULT_GROUP", "public", "DEFAULT", address, port, "nacos:" + instanceId,
                    selectable, true, selectable ? 1 : 0, true, metadata);
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
        @Override public void unregister(Registration expected) { values.remove(expected.metadata().get("alphafrog.instance-id")); }
        void forceSelectable(String instanceId, boolean selectable) {
            Registration expected = values.get(instanceId);
            values.put(instanceId, new Registration(expected.serviceName(), expected.groupName(), expected.namespaceId(),
                    expected.clusterName(), expected.ip(), expected.port(), expected.nacosInstanceId(), selectable,
                    expected.healthy(), selectable ? 1 : 0, expected.ephemeral(), expected.metadata()));
        }
    }

    private static final class FakeRetirement implements RetirementGateway {
        int calls;
        boolean fail;
        @Override public void retire(String address, int port, String deploymentId, String generationId, String token) {
            assertFalse(token.isBlank());
            if (fail) throw new ControllerException("AGENT_RETIREMENT_FAILED", "simulated retirement failure");
            calls++;
        }
    }
}
