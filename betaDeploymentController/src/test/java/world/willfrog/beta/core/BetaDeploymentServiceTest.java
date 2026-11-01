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
        properties.setFailedCandidateRetain(java.time.Duration.ZERO);
        return properties;
    }
































    private void reconcile(int count) {
        for (int index = 0; index < count; index++) service.reconcileOne();
    }

    private void retainUnhealthyCandidate(MutableClock clock) {
        BetaControllerProperties properties = testProperties();
        properties.setFailedCandidateRetain(java.time.Duration.ofSeconds(120));
        store = new AtomicJsonStore(mapper, properties);
        containers = new FakeContainers();
        registrationProbe = new FakeRegistrationProbe();
        service = new BetaDeploymentService(mapper, store, new BetaContractValidator(mapper, properties), containers,
                registrationProbe, properties, clock);
        service.submitManifest(manifest(1, "release-1", '1', 'a', 'b', "main-beta"));
        reconcile(3);
        containers.health = ContainerRuntime.ContainerObservation.Health.UNHEALTHY;
        service.submitManifest(manifest(2, "release-2", '2', 'c', 'd', "main-beta"));
        service.reconcileOne();
        service.reconcileOne();
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
        final Map<String, CandidatePlan> plans = new LinkedHashMap<>();
        final Map<String, JsonNode> createdSpecs = new LinkedHashMap<>();
        final Map<String, String> commits = new LinkedHashMap<>();
        final Map<String, Boolean> processHaltAllowed = new LinkedHashMap<>();
        final Map<String, Boolean> stopped = new LinkedHashMap<>();
        final java.util.Set<String> removedComposeInstanceIds = new java.util.LinkedHashSet<>();
        int stopTimeoutSeconds;
        int inspectCalls;
        boolean leaveRunningAfterStop;
        String invalidManifestId;
        int observedPortOffset;
        boolean failAfterCreating;
        int createdContainers;
        String failRemoveName;
        String failRemoveComposeInstanceId;

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
            plans.put(name, plan);
            createdSpecs.put(name, spec.deepCopy());
            commits.put(name, manifest.path("gitCommit").asText());
            processHaltAllowed.put(name, spec.path("runtime").path("allowToolJobProcessHalt").asBoolean(false));
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
        @Override public void remove(String machineId, String name) {
            if (name.equals(failRemoveName))
                throw new ControllerException("CONTAINER_REMOVE_FAILED", "forced remove failure");
            values.remove(name);
        }
        @Override public ToolJobTestRuntime inspectToolJobTestRuntime(String machineId, String name) {
            CandidatePlan plan = plans.get(name);
            ContainerObservation container = values.get(name);
            if (plan == null || container == null) {
                throw new ControllerException("TOOL_JOB_TEST_CONTAINER_MISSING", "missing");
            }
            boolean lane = !"main-beta".equals(plan.trafficScopeId());
            return new ToolJobTestRuntime(container.containerId(), true, true, plan.deploymentId(),
                    plan.trafficScopeId(), plan.generationId(), commits.get(name), lane, lane,
                    lane && Boolean.TRUE.equals(processHaltAllowed.get(name)), true, 0,
                    "2026-09-20T00:00:00Z");
        }
        @Override public ContainerObservation restart(String machineId, String name, java.time.Duration timeout) {
            return inspect(machineId, name);
        }
        @Override public void removeCompose(String instanceId) {
            if (instanceId.equals(failRemoveComposeInstanceId))
                throw new ControllerException("COMPOSE_REMOVE_FAILED", "forced compose remove failure");
            removedComposeInstanceIds.add(instanceId);
        }
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant next) {
            this.instant = next;
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
