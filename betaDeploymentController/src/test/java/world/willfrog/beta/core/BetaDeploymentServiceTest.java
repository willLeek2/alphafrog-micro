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
        BetaControllerProperties properties = testProperties();
        store = new AtomicJsonStore(mapper, properties);
        containers = new FakeContainers();
        registrationProbe = new FakeRegistrationProbe();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe, properties,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private BetaControllerProperties testProperties() {
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        BetaControllerProperties.Machine machine = new BetaControllerProperties.Machine();
        machine.setDockerHost(java.net.URI.create("unix:///var/run/docker.sock"));
        machine.setBindIp("127.0.0.1");
        machine.setRoutableAddress("10.0.0.8");
        properties.setMachines(Map.of("beta-machine-1", machine));
        return properties;
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
        assertTrue(containers.removedComposeInstanceIds.contains(oldId));
        assertEquals(65, containers.stopTimeoutSeconds);
    }

    @Test
    void updatingOneServiceLeavesServicesWithUnchangedDigestsStable() {
        ObjectNode first = manifest(1, "release-1", '1', 'a', 'b', "main-beta");
        ObjectNode secondService = withPortfolioService(first);
        service.submitManifest(first);
        reconcile(6);
        String agentContainer = stateOf("agent-service").path("activeInstance").path("containerName").asText();
        String portfolioContainer = stateOf("portfolio-service").path("activeInstance").path("containerName").asText();
        String portfolioInstance = stateOf("portfolio-service").path("activeInstance").path("instanceId").asText();
        assertEquals("STABLE", stateOf("agent-service").path("phase").asText());
        assertEquals("STABLE", stateOf("portfolio-service").path("phase").asText());

        // 新部署单只换 agent 的镜像（版本号也升高）；portfolio 的服务摘要不变。
        ObjectNode second = manifest(2, "release-1", '1', 'a', 'c', "main-beta");
        ((com.fasterxml.jackson.databind.node.ArrayNode) second.path("services")).add(secondService.deepCopy());
        service.submitManifest(second);
        reconcile(4);

        assertEquals("STABLE", stateOf("portfolio-service").path("phase").asText());
        assertEquals(portfolioInstance, stateOf("portfolio-service").path("activeInstance").path("instanceId").asText());
        assertTrue(containers.values.containsKey(portfolioContainer));
        assertFalse(containers.stopped.containsKey(portfolioContainer));
        assertFalse(agentContainer.equals(stateOf("agent-service").path("activeInstance").path("containerName").asText()));
    }

    @Test
    void startFailureRemovesTheDeterministicCandidateBeforeRetry() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        String candidateId = state().path("operation").path("candidateInstanceId").asText();
        containers.failAfterCreating = true;

        service.reconcileOne();

        assertEquals("FAILED", state().path("phase").asText());
        assertEquals("CLEAN_RETRYABLE", state().path("lastError").path("recoveryClass").asText());
        assertFalse(containers.values.containsKey("af-" + candidateId));
        assertTrue(containers.removedComposeInstanceIds.contains(candidateId));
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

        BetaControllerProperties properties = testProperties();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe, properties,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(state().path("lastError").isNull());

        service.reconcileOne();
        assertEquals("DRAINING_PREVIOUS", state().path("operation").path("phase").asText());
        assertEquals(candidate.path("instanceId").asText(), state().path("activeInstance").path("instanceId").asText());
        assertEquals(oldId, state().path("drainingInstance").path("instanceId").asText());
    }

    @Test
    void startupValidatesPersistedFilesWithoutScanningEveryContainer() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        containers.inspectCalls = 0;

        service.verifyPersistentStateAtStartup();

        assertEquals(0, containers.inspectCalls);
        assertEquals("STABLE", state().path("phase").asText());
    }

    @Test
    void startupIsolatesOneDeploymentWithInvalidRuntimeFilesAndContinuesAnotherDeployment() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        service.submitManifest(manifest("beta-lane-a", 1, "release-1", '1', 'c', 'd', "lane-a", 38080));
        reconcile(3);
        containers.invalidManifestId = "beta-lane-a";

        service.verifyPersistentStateAtStartup();

        assertEquals("STABLE", state("main-beta").path("phase").asText());
        assertEquals("FAILED", state("lane-a").path("phase").asText());
        assertEquals("RUNTIME_CONFIG_MISMATCH", state("lane-a").path("lastError").path("code").asText());
        assertEquals("FACTS_UNCERTAIN", state("lane-a").path("lastError").path("recoveryClass").asText());

        service.submitManifest(manifest(2, "release-2", '2', 'e', 'f', "main-beta"));
        reconcile(4);
        assertEquals("STABLE", state("main-beta").path("phase").asText());
        assertEquals("release-2", state("main-beta").path("activeInstance").path("releaseId").asText());
    }

    @Test
    void scheduledReconcileContinuesAfterOneUnexpectedFailure() {
        class FlakyDeploymentService extends BetaDeploymentService {
            int calls;

            FlakyDeploymentService() {
                super(mapper, store, new BetaContractValidator(mapper, new BetaControllerProperties()),
                        containers, registrationProbe, new BetaControllerProperties(), Clock.systemUTC());
            }

            @Override
            public ObjectNode reconcileOne() {
                calls++;
                if (calls == 1) throw new IllegalStateException("transient failure");
                return mapper.createObjectNode();
            }
        }
        FlakyDeploymentService flaky = new FlakyDeploymentService();

        flaky.scheduledReconcile();
        flaky.scheduledReconcile();

        assertEquals(2, flaky.calls);
    }

    @Test
    void expiryThatDeletesAnEmptyDeploymentReturnsWithoutDereferencingANullOperation() {
        ObjectNode expired = manifest(1, "release-1", '1', 'a', 'b', "main-beta");
        expired.put("createdAt", "2025-08-01T00:00:00Z");
        expired.put("expiresAt", "2025-09-01T00:00:00Z");
        service.submitManifest(expired);
        store.update(state -> {
            ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("deployments").path(0).path("services"))
                    .removeAll();
            return null;
        });

        service.reconcileOne();

        assertEquals(0, store.snapshot().path("deployments").size());
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
    void transientNacosQueryFailureKeepsWaitingAndLaterPromotesTheCandidate() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        service.reconcileOne();
        registrationProbe.nextFailure = new ControllerException(
                "NACOS_QUERY_FAILED", "Unable to confirm the candidate self-registration");

        service.reconcileOne();

        assertEquals("WAITING_CANDIDATE_READINESS", state().path("operation").path("phase").asText());
        assertTrue(state().path("lastError").isNull());

        service.reconcileOne();

        assertEquals("SWITCHING_TRAFFIC", state().path("operation").path("phase").asText());
    }

    @Test
    void namespaceMismatchFailsFastWithoutBlockingDeploymentDeletion() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        service.reconcileOne();
        registrationProbe.nextFailure = new ControllerException(
                "NACOS_NAMESPACE_MISMATCH", "Manifest namespace differs from the configured Nacos namespace");

        service.reconcileOne();

        assertEquals("FAILED", state().path("phase").asText());
        assertEquals("CLEAN_RETRYABLE", state().path("lastError").path("recoveryClass").asText());
        assertTrue(state().path("candidateInstance").isNull());

        service.requestDelete("beta-main-001");

        assertEquals(0, store.snapshot().path("deployments").size());
    }

    @Test
    void retryingAPersistedCandidateStartsANewReadinessWindow() {
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        service.reconcileOne();
        containers.observedPortOffset = 1;
        BetaControllerProperties properties = testProperties();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe, properties,
                Clock.fixed(Instant.parse("2026-09-01T00:03:00Z"), ZoneOffset.UTC));
        service.reconcileOne();
        assertEquals("FAILED", state().path("phase").asText());
        assertTrue(state().path("candidateInstance").isObject());
        containers.observedPortOffset = 0;

        service.retry("beta-main-001", "agent-service");

        assertEquals("WAITING_CANDIDATE_READINESS", state().path("operation").path("phase").asText());
        assertEquals("2026-09-01T00:05:00Z", state().path("candidateInstance")
                .path("readinessDeadline").asText());
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
    void frontendUpdateStopsTheOldContainerFirstAndRecreatesOnTheFirstHostPort() {
        service.submitManifest(frontendManifest(1, "release-1", 'a'));
        reconcile(3);
        String oldId = state().path("activeInstance").path("instanceId").asText();
        assertEquals("STABLE", state().path("phase").asText());

        // 模拟旧控制器蓝绿翻口留下的现场：活动实例在槽 B（对应现网 18091）。
        store.update(state -> {
            ObjectNode active = (ObjectNode) state.path("deployments").path(0).path("services").path(0)
                    .path("activeInstance");
            active.put("portSlot", "B");
            active.put("hostPort", 28081);
            ((ObjectNode) active.path("endpoint")).put("port", 28081);
            return null;
        });

        service.submitManifest(frontendManifest(2, "release-2", 'c'));

        assertEquals("UPDATING", state().path("phase").asText());
        assertEquals("DRAINING_PREVIOUS", state().path("operation").path("phase").asText());
        assertTrue(state().path("activeInstance").isNull());
        assertEquals(oldId, state().path("drainingInstance").path("instanceId").asText());

        reconcile(4);

        assertEquals("STABLE", state().path("phase").asText());
        assertEquals("release-2", state().path("activeInstance").path("releaseId").asText());
        assertEquals(28080, state().path("activeInstance").path("hostPort").asInt());
        assertEquals("A", state().path("activeInstance").path("portSlot").asText());
        assertTrue(containers.stopped.containsKey("af-" + oldId));
        assertTrue(containers.removedComposeInstanceIds.contains(oldId));
        assertEquals(0, registrationProbe.calls);

        // 再滚一次仍固定第一只口，不翻口。
        service.submitManifest(frontendManifest(3, "release-3", 'e'));
        reconcile(4);

        assertEquals("STABLE", state().path("phase").asText());
        assertEquals("release-3", state().path("activeInstance").path("releaseId").asText());
        assertEquals(28080, state().path("activeInstance").path("hostPort").asInt());
        assertEquals("A", state().path("activeInstance").path("portSlot").asText());
        assertEquals(0, registrationProbe.calls);
    }

    @Test
    void gatewayRebuildsToFollowTheSandboxHostPortEvenWhenItsOwnDigestIsUnchanged() {
        service.submitManifest(sandboxDeployment(1, 'a', 'b'));
        reconcile(6);

        JsonNode sandbox = stateOf("python-sandbox-service");
        JsonNode gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("STABLE", sandbox.path("phase").asText());
        assertEquals("STABLE", gateway.path("phase").asText());
        assertEquals(18095, sandbox.path("activeInstance").path("hostPort").asInt());
        assertFalse(sandbox.path("activeInstance").has("httpUpstream"));
        assertEquals("10.0.0.8", gateway.path("activeInstance").path("httpUpstream").path("address").asText());
        assertEquals(18095, gateway.path("activeInstance").path("httpUpstream").path("port").asInt());
        String oldGatewayInstance = gateway.path("activeInstance").path("instanceId").asText();
        String oldGatewayContainer = gateway.path("activeInstance").path("containerName").asText();

        // 只改沙箱镜像摘要：沙箱翻到第二只口，网关摘要没变也要重建追上新口。
        service.submitManifest(sandboxDeployment(2, 'c', 'd'));
        reconcile(5);

        assertEquals(18096, stateOf("python-sandbox-service").path("activeInstance").path("hostPort").asInt());
        gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("UPDATING", gateway.path("phase").asText());
        // 网关自己的目标摘要和活动实例摘要一致，更新只由沙箱新口驱动。
        assertEquals(gateway.path("targetServiceSpecSha256").asText(),
                gateway.path("activeInstance").path("serviceSpecSha256").asText());
        assertEquals(18096, gateway.path("candidateInstance").path("httpUpstream").path("port").asInt());

        reconcile(3);

        gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("STABLE", gateway.path("phase").asText());
        assertFalse(oldGatewayInstance.equals(gateway.path("activeInstance").path("instanceId").asText()));
        assertEquals(18096, gateway.path("activeInstance").path("httpUpstream").path("port").asInt());
        assertTrue(containers.stopped.containsKey(oldGatewayContainer));
        assertTrue(containers.removedComposeInstanceIds.contains(oldGatewayInstance));
    }

    @Test
    void legacyGatewayWithoutAnUpstreamRecordIsRebuiltOnTheNextReconcile() {
        service.submitManifest(sandboxDeployment(1, 'a', 'b'));
        reconcile(6);
        // 模拟现网旧控制器留下的记录：网关活动实例没有 httpUpstream（services[1] 是网关）。
        store.update(state -> {
            ((ObjectNode) state.path("deployments").path(0).path("services").path(1)
                    .path("activeInstance")).remove("httpUpstream");
            return null;
        });
        assertEquals("STABLE", stateOf("python-sandbox-gateway-service").path("phase").asText());

        service.reconcileOne();

        JsonNode gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("UPDATING", gateway.path("phase").asText());
        assertEquals(18095, gateway.path("candidateInstance").path("httpUpstream").path("port").asInt());
    }

    @Test
    void gatewayWithoutASandboxServiceInTheDeploymentKeepsTheEnvironmentFileUpstream() {
        ObjectNode onlyGateway = sandboxDeployment(1, 'a', 'b');
        ((com.fasterxml.jackson.databind.node.ArrayNode) onlyGateway.path("services")).remove(0);
        service.submitManifest(onlyGateway);
        reconcile(3);

        JsonNode gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("STABLE", gateway.path("phase").asText());
        assertFalse(gateway.path("activeInstance").has("httpUpstream"));

        // 没有沙箱服务可跟随，摘要没变的重复提交也不触发网关更新。
        ObjectNode repeat = sandboxDeployment(2, 'a', 'b');
        ((com.fasterxml.jackson.databind.node.ArrayNode) repeat.path("services")).remove(0);
        service.submitManifest(repeat);
        reconcile(1);

        gateway = stateOf("python-sandbox-gateway-service");
        assertEquals("STABLE", gateway.path("phase").asText());
        assertTrue(gateway.path("operation").isNull());
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
        assertEquals(65, containers.stopTimeoutSeconds);
        assertTrue(containers.removedComposeInstanceIds.contains(activeId));
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
    void keepsManifestSchemaAndFixedHostPortGuards() {
        ObjectNode malformed = manifest(1, "release-1", '1', 'a', 'b', "main-beta");
        malformed.remove("expiresAt");

        ControllerException schemaFailure = assertThrows(ControllerException.class,
                () -> service.submitManifest(malformed));
        assertEquals("MANIFEST_INVALID", schemaFailure.code());

        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        ObjectNode conflictingLane = manifest(
                "beta-lane-a", 1, "release-1", '2', 'c', 'd', "lane-a", 28080);

        ControllerException portFailure = assertThrows(ControllerException.class,
                () -> service.submitManifest(conflictingLane));
        assertEquals("HOST_PORT_CONFLICT", portFailure.code());
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
        assertEquals("2026-09-01T00:01:05Z", state().path("drainingInstance").path("stopDeadline").asText());

        containers.leaveRunningAfterStop = false;
        BetaControllerProperties properties = testProperties();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe, properties,
                Clock.fixed(Instant.parse("2026-09-01T00:00:30Z"), ZoneOffset.UTC));
        service.retry("beta-main-001", "agent-service");
        service.reconcileOne();

        assertEquals(35, containers.stopTimeoutSeconds);
        assertEquals("STABLE", state().path("phase").asText());
    }

    private void reconcile(int count) {
        for (int index = 0; index < count; index++) service.reconcileOne();
    }

    private JsonNode state() {
        return store.snapshot().path("deployments").path(0).path("services").path(0);
    }

    private JsonNode stateOf(String serviceName) {
        for (JsonNode deployment : store.snapshot().path("deployments")) {
            for (JsonNode service : deployment.path("services")) {
                if (serviceName.equals(service.path("serviceName").asText())) return service;
            }
        }
        throw new AssertionError("Missing service " + serviceName);
    }

    private ObjectNode frontendManifest(int version, String release, char image) {
        ObjectNode value = manifest(version, release, '1', 'a', image, "main-beta");
        ObjectNode spec = (ObjectNode) value.path("services").path(0);
        spec.put("serviceName", "frontend");
        spec.put("dubboServiceKey", "world.willfrog.alphafrogmicro.frontend.http.FrontendHttp");
        spec.remove("registration");
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));
        return value;
    }

    private ObjectNode sandboxDeployment(int version, char sandboxRepository, char sandboxImage) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("deploymentId", "beta-main-001");
        root.put("trafficScopeId", "main-beta");
        root.put("manifestVersion", version);
        root.put("gitCommit", "1".repeat(40));
        root.putObject("owner").put("ownerId", "frog");
        root.put("createdAt", "2026-09-01T00:00:00Z");
        root.put("expiresAt", "2026-09-08T00:00:00Z");
        ObjectNode sandbox = root.putArray("services").addObject();
        sandbox.put("serviceName", "python-sandbox-service");
        sandbox.put("dubboServiceKey", "world.willfrog.alphafrogmicro.sandbox.http.PythonSandboxHttp");
        sandbox.put("releaseId", "release-1");
        sandbox.put("machineId", "beta-machine-1");
        ObjectNode sandboxImageNode = sandbox.putObject("image");
        sandboxImageNode.put("repositoryDigest",
                "registry.local/sandbox@sha256:" + String.valueOf(sandboxRepository).repeat(64));
        sandboxImageNode.put("localImageId", "sha256:" + String.valueOf(sandboxImage).repeat(64));
        ObjectNode sandboxRuntime = sandbox.putObject("runtime");
        sandboxRuntime.put("containerPort", 8095);
        sandboxRuntime.putArray("hostPorts").add(18095).add(18096);
        sandboxRuntime.put("healthCheckProfile", "CONTROLLER_TCP_V1");
        sandboxRuntime.put("readinessTimeoutSeconds", 120);
        sandboxRuntime.put("shutdownProfile", "SPRING_BOOT_HTTP_V1");
        sandboxRuntime.put("applicationDrainSeconds", 60);
        sandboxRuntime.put("drainGraceSeconds", 60);
        sandbox.putNull("runtimeConfigSha256");
        sandbox.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, sandbox));

        // 网关镜像摘要固定：第二次提交里它不变，验证网关重建由沙箱口驱动而不是摘要。
        ObjectNode gateway = sandbox.deepCopy();
        gateway.put("serviceName", "python-sandbox-gateway-service");
        gateway.put("dubboServiceKey", "world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService");
        ObjectNode gatewayImage = (ObjectNode) gateway.path("image");
        gatewayImage.put("repositoryDigest", "registry.local/gateway@sha256:" + "e".repeat(64));
        gatewayImage.put("localImageId", "sha256:" + "f".repeat(64));
        ObjectNode gatewayRuntime = (ObjectNode) gateway.path("runtime");
        gatewayRuntime.put("containerPort", 50060);
        gatewayRuntime.remove("hostPorts");
        gatewayRuntime.putArray("hostPorts").add(51060).add(52060);
        gatewayRuntime.put("shutdownProfile", "SPRING_BOOT_HTTP_DUBBO_V1");
        ObjectNode registration = gateway.putObject("registration");
        registration.put("serviceName", "providers:world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService::");
        registration.put("groupName", "alphafrog-beta");
        registration.put("namespaceId", "public");
        registration.put("clusterName", "DEFAULT");
        registration.put("applicationName", "python-sandbox-gateway-service");
        gateway.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, gateway));
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("services")).add(gateway);
        return root;
    }

    private ObjectNode withPortfolioService(ObjectNode manifest) {
        ObjectNode spec = (ObjectNode) manifest.path("services").path(0).deepCopy();
        spec.put("serviceName", "portfolio-service");
        spec.put("dubboServiceKey", "portfolio/com.alphafrog.PortfolioService");
        ((ObjectNode) spec.path("registration"))
                .put("serviceName", "providers:com.alphafrog.PortfolioService::portfolio");
        ObjectNode runtime = (ObjectNode) spec.path("runtime");
        runtime.remove("hostPorts");
        runtime.putArray("hostPorts").add(28100).add(28101);
        spec.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, spec));
        ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("services")).add(spec);
        return spec;
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
        runtime.put("drainGraceSeconds", 65);
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
        final java.util.Set<String> removedComposeInstanceIds = new java.util.LinkedHashSet<>();
        int stopTimeoutSeconds;
        int inspectCalls;
        boolean leaveRunningAfterStop;
        String invalidManifestId;
        int observedPortOffset;
        boolean failAfterCreating;
        int createdContainers;

        @Override public void validateManifestEnvironment(JsonNode manifest) {
            if (manifest.path("deploymentId").asText().equals(invalidManifestId)) {
                throw new ControllerException("RUNTIME_CONFIG_MISMATCH", "Service environment file changed");
            }
        }

        @Override public ContainerObservation create(JsonNode manifest, JsonNode spec, CandidatePlan plan) {
            String name = "af-" + plan.instanceId();
            // 容器 ID 用只增计数：删除旧实例后 values.size() 会回退，两个存活实例会撞 ID。
            ContainerObservation value = new ContainerObservation(String.format("%064x", ++createdContainers),
                    name, "10.0.0.8", plan.hostPort(), true, health);
            values.put(name, value);
            if (failAfterCreating) throw new ControllerException("CONTAINER_START_FAILED", "post-create check failed");
            return value;
        }
        @Override public ContainerObservation inspect(String machineId, String name) {
            inspectCalls++;
            ContainerObservation value = values.get(name);
            if (value == null) return new ContainerObservation("", name, "", 0, false, ContainerObservation.Health.MISSING);
            return new ContainerObservation(value.containerId(), name, value.endpointAddress(),
                    value.hostPort() + observedPortOffset,
                    !Boolean.TRUE.equals(stopped.get(name)), health);
        }
        @Override public void stop(String machineId, String name, int timeoutSeconds) {
            stopTimeoutSeconds = timeoutSeconds;
            if (!leaveRunningAfterStop) stopped.put(name, true);
        }
        @Override public void remove(String machineId, String name) { values.remove(name); }
        @Override public void removeCompose(String instanceId) { removedComposeInstanceIds.add(instanceId); }
        @Override public String containerName(CandidatePlan plan, String serviceName) {
            return "af-" + plan.instanceId();
        }
    }

    private static final class FakeRegistrationProbe implements CandidateRegistrationProbe {
        boolean visible = true;
        String lastAddress;
        int lastPort;
        int calls;
        ControllerException nextFailure;

        @Override public boolean isVisible(JsonNode service, String address, int port) {
            calls++;
            if (nextFailure != null) {
                ControllerException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            lastAddress = address;
            lastPort = port;
            return visible;
        }
    }
}
