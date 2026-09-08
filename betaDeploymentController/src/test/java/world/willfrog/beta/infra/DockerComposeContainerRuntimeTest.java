package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ContainerRuntime;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

class DockerComposeContainerRuntimeTest {
    @TempDir Path temporary;
    private ObjectMapper mapper;
    private BetaControllerProperties properties;
    private JsonNode manifest;
    private JsonNode service;
    private ContainerRuntime.CandidatePlan plan;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        properties = new BetaControllerProperties();
        properties.getNacos().setServerAddress("nacos.internal:8848");
        properties.setStateRoot(temporary.resolve("state"));
        Path health = temporary.resolve("tcp-healthcheck");
        Files.writeString(health, "#!/bin/sh\nexit 0\n");
        health.toFile().setExecutable(true, true);
        properties.setHealthcheckScript(health);
        Path environment = temporary.resolve("agent-service.env");
        Files.writeString(environment, "SERVER_PORT=18080\n");
        try { Files.setPosixFilePermissions(environment, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        BetaControllerProperties.ServiceTemplate template = new BetaControllerProperties.ServiceTemplate();
        template.setEnvFile(environment);
        properties.setServices(Map.of("agent-service", template));
        BetaControllerProperties.Machine machine = new BetaControllerProperties.Machine();
        machine.setDockerHost(URI.create("unix:///var/run/docker.sock"));
        machine.setBindIp("127.0.0.1");
        machine.setRoutableAddress("10.0.0.8");
        properties.setMachines(Map.of("beta-machine-1", machine));
        manifest = mapper.readTree("""
                {"deploymentId":"beta-main-001","trafficScopeId":"main-beta","gitCommit":"1111111111111111111111111111111111111111",
                 "manifestVersion":1,"services":[{"serviceName":"agent-service","releaseId":"release-1","machineId":"beta-machine-1",
                 "image":{"repositoryDigest":"registry.local/agent@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "localImageId":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
                 "runtime":{"containerPort":18080,"hostPorts":[28080,28081],
                            "shutdownProfile":"SPRING_BOOT_HTTP_DUBBO_V1","applicationDrainSeconds":60,
                            "drainGraceSeconds":60,"readinessTimeoutSeconds":120},
                 "registration":{"serviceName":"providers:com.alphafrog.AgentService::langchain",
                    "groupName":"alphafrog-beta","namespaceId":"public","clusterName":"DEFAULT",
                    "applicationName":"agent-langchain-service"}}]}
                """);
        service = manifest.path("services").path(0);
        plan = new ContainerRuntime.CandidatePlan("beta-main-001", "main-beta", "i-one",
                JsonSupport.deploymentGeneration(manifest), "A", 28080);
    }

    @Test
    void createsFromAnImmutableImageWithOneApplicationAndContainerDeadline() throws Exception {
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);
        runtime.validateManifest(manifest);

        ContainerRuntime.ContainerObservation created = runtime.create(manifest, service, plan);

        assertTrue(created.running());
        assertEquals(28080, created.hostPort());
        Path compose = temporary.resolve("state/compose/i-one.json");
        String content = Files.readString(compose);
        JsonNode effectiveRouting = mapper.readTree(mapper.readTree(content)
                .path("services").path("app").path("environment").path("SPRING_APPLICATION_JSON").asText());
        assertFalse(content.contains("RETIREMENT_TOKEN"));
        assertTrue(content.contains("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS"));
        assertTrue(content.contains("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS"));
        assertTrue(content.contains("AGENT_LANGCHAIN_GENERATION_REAPER_ENABLED"));
        assertTrue(content.contains("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_SERVER_ADDRESS"));
        assertTrue(content.contains("AGENT_LANGCHAIN_GENERATION_REAPER_ABSENCE_CONFIRMATION_SECONDS"));
        assertTrue(content.contains("OTEL_SERVICE_NAME"));
        assertTrue(content.contains("deployment.id=beta-main-001,lane.tag=main-beta,service.version=release-1"));
        assertTrue(content.contains("image.digest=sha256:" + "b".repeat(64)));
        assertFalse(content.contains("image.digest=registry.local"));
        assertTrue(content.contains("alphafrog-beta"));
        assertTrue(content.contains("zone-aware"));
        assertFalse(effectiveRouting.path("dubbo").path("registry").path("register").asBoolean());
        assertTrue(effectiveRouting.path("dubbo").path("registries").path("beta").path("register").asBoolean());
        assertFalse(effectiveRouting.path("dubbo").path("registries").path("production").path("register").asBoolean());
        assertTrue(effectiveRouting.path("dubbo").path("registries").path("beta").path("preferred").asBoolean());
        assertFalse(effectiveRouting.path("dubbo").path("registries").path("production").path("preferred").asBoolean());
        assertTrue(effectiveRouting.path("dubbo").path("registries").path("production").path("address").asText()
                .contains("group=DEFAULT_GROUP"));
        assertEquals("zone-aware", effectiveRouting.path("dubbo").path("consumer").path("cluster").asText());
        assertTrue(content.contains("SERVER_SHUTDOWN"));
        assertTrue(content.contains("DUBBO_SERVICE_SHUTDOWN_WAIT"));
        JsonNode environmentNode = mapper.readTree(content).path("services").path("app").path("environment");
        assertEquals("10.0.0.8", environmentNode.path("DUBBO_IP_TO_REGISTRY").asText());
        assertEquals("28080", environmentNode.path("DUBBO_PORT_TO_REGISTRY").asText());
        assertFalse(environmentNode.has("AF_DUBBO_PORT_TO_REGISTRY"));
        JsonNode providerParameters = effectiveRouting.path("dubbo").path("provider").path("parameters");
        assertEquals("beta-main-001", providerParameters.path("alphafrog.deployment-id").asText());
        assertEquals("main-beta", providerParameters.path("alphafrog.traffic-scope-id").asText());
        assertEquals("release-1", providerParameters.path("alphafrog.release-id").asText());
        assertEquals(plan.generationId(), providerParameters.path("alphafrog.deployment-generation-id").asText());
        assertEquals("i-one", providerParameters.path("alphafrog.instance-id").asText());
        assertEquals("beta", providerParameters.path("zone").asText());
        assertFalse(providerParameters.has("dubbo.tag"));
        assertEquals("60", environmentNode.path("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS").asText());
        assertEquals("5", environmentNode.path(
                "AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS").asText());
        assertEquals("0s", environmentNode.path("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE").asText());
        assertEquals("5000", environmentNode.path("DUBBO_SERVICE_SHUTDOWN_WAIT").asText());
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--quiet")));
        assertFalse(commands.commands.stream().anyMatch(command -> command.contains("--no-env-resolution")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("up")));
    }

    @Test
    void createsFromALocalTagWhenItsImageIdMatchesTheManifest() throws Exception {
        ((ObjectNode) service.path("image")).put("repositoryDigest", "agent-langchain-service:local");
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        runtime.create(manifest, service, plan);

        JsonNode compose = mapper.readTree(Files.readString(temporary.resolve("state/compose/i-one.json")));
        assertEquals("agent-langchain-service:local", compose.path("services").path("app").path("image").asText());
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("image")
                && command.contains("inspect") && command.contains("agent-langchain-service:local")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("up")));
    }

    @Test
    void refusesALocalTagWhenItsImageIdDiffersFromTheManifest() {
        ((ObjectNode) service.path("image")).put("repositoryDigest", "agent-langchain-service:local");
        FakeCommands commands = new FakeCommands(false);
        commands.imageInspectId = "sha256:" + "c".repeat(64);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class,
                () -> runtime.create(manifest, service, plan));

        assertEquals("IMAGE_ID_MISMATCH", failure.code());
        assertTrue(commands.commands.stream().noneMatch(command -> command.contains("up")));
    }

    @Test
    void everyBetaFrontendEnablesEntryWhileOnlyLanesInjectTheirScopeTag() throws Exception {
        ObjectNode frontend = (ObjectNode) service;
        frontend.put("serviceName", "frontend");
        frontend.remove("registration");
        Path environment = temporary.resolve("frontend.env");
        Files.writeString(environment, "SERVER_PORT=18080\n");
        try { Files.setPosixFilePermissions(environment, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        BetaControllerProperties.ServiceTemplate template = new BetaControllerProperties.ServiceTemplate();
        template.setEnvFile(environment);
        properties.setServices(Map.of("frontend", template));
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        // 主 Beta frontend 是共用入口：入口打标开启，但不注入泳道名，靠请求头指定泳道。
        runtime.create(manifest, frontend, plan);

        JsonNode compose = mapper.readTree(Files.readString(temporary.resolve("state/compose/i-one.json")));
        JsonNode environmentNode = compose.path("services").path("app").path("environment");
        JsonNode routing = mapper.readTree(environmentNode.path("SPRING_APPLICATION_JSON").asText());
        assertEquals("true", environmentNode.path("AF_LANE_ENTRY_ENABLED").asText());
        assertFalse(environmentNode.has("AF_LANE_TRAFFIC_SCOPE_ID"));
        assertFalse(environmentNode.has("DUBBO_IP_TO_REGISTRY"));
        assertFalse(environmentNode.has("DUBBO_PORT_TO_REGISTRY"));
        assertFalse(routing.path("dubbo").has("provider"));
        assertEquals("60s", environmentNode.path("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE").asText());
        assertEquals("60000", environmentNode.path("DUBBO_SERVICE_SHUTDOWN_WAIT").asText());

        // 泳道 frontend 是特判入口：入口开关与本部署的泳道名都注入。
        plan = new ContainerRuntime.CandidatePlan("beta-lane-a", "lane-a", "i-two",
                JsonSupport.deploymentGeneration(manifest), "A", 28081);
        DockerComposeContainerRuntime laneRuntime =
                new DockerComposeContainerRuntime(mapper, new FakeCommands(false), properties);
        laneRuntime.create(manifest, frontend, plan);

        compose = mapper.readTree(Files.readString(temporary.resolve("state/compose/i-two.json")));
        environmentNode = compose.path("services").path("app").path("environment");
        assertEquals("true", environmentNode.path("AF_LANE_ENTRY_ENABLED").asText());
        assertEquals("lane-a", environmentNode.path("AF_LANE_TRAFFIC_SCOPE_ID").asText());
    }

    @Test
    void laneProviderRegistersItsOfficialDubboTag() throws Exception {
        ((ObjectNode) manifest).put("trafficScopeId", "lane-a");
        plan = new ContainerRuntime.CandidatePlan("beta-lane-a", "lane-a", "i-one",
                JsonSupport.deploymentGeneration(manifest), "A", 28080);
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        runtime.create(manifest, service, plan);

        JsonNode compose = mapper.readTree(Files.readString(temporary.resolve("state/compose/i-one.json")));
        JsonNode environmentNode = compose.path("services").path("app").path("environment");
        JsonNode routing = mapper.readTree(environmentNode.path("SPRING_APPLICATION_JSON").asText());
        assertEquals("lane-a", routing.path("dubbo").path("provider").path("parameters")
                .path("dubbo.tag").asText());
    }

    @Test
    void nonDefaultAgentDeadlineKeepsOneFiveSecondFinalizationBudget() throws Exception {
        ((ObjectNode) service.path("runtime")).put("applicationDrainSeconds", 30);
        ((ObjectNode) service.path("runtime")).put("drainGraceSeconds", 30);
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        runtime.create(manifest, service, plan);

        JsonNode compose = mapper.readTree(Files.readString(temporary.resolve("state/compose/i-one.json")));
        JsonNode app = compose.path("services").path("app");
        JsonNode environmentNode = app.path("environment");
        assertEquals("30s", app.path("stop_grace_period").asText());
        assertEquals("30", environmentNode.path("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS").asText());
        assertEquals("5", environmentNode.path(
                "AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS").asText());
        assertEquals("0s", environmentNode.path("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE").asText());
        assertEquals("5000", environmentNode.path("DUBBO_SERVICE_SHUTDOWN_WAIT").asText());
    }

    @Test
    void validatesComposeSyntaxBeforeCreatingCandidate() throws Exception {
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ContainerRuntime.ContainerObservation created = runtime.create(manifest, service, plan);

        assertTrue(created.running());
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--quiet")));
        assertFalse(commands.commands.stream().anyMatch(command -> command.contains("--no-env-resolution")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("up")));
    }

    @Test
    void stopsBeforeCreatingCandidateWhenComposeSyntaxIsInvalid() {
        FakeCommands commands = new FakeCommands(false);
        commands.failComposeValidation = true;
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class,
                () -> runtime.create(manifest, service, plan));

        assertEquals("COMMAND_FAILED", failure.code());
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--quiet")));
        assertTrue(commands.commands.stream().noneMatch(command -> command.contains("up")));
    }

    @Test
    void refusesAnEnvironmentFileWhoseDigestDiffersFromTheManifest() {
        ((ObjectNode) service).put("runtimeConfigSha256", "c".repeat(64));
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(
                mapper, new FakeCommands(false), properties);

        ControllerException failure = assertThrows(ControllerException.class,
                () -> runtime.validateManifest(manifest));

        assertEquals("RUNTIME_CONFIG_MISMATCH", failure.code());
    }

    @Test
    void reusesTheDeterministicCandidateContainerNameAfterRestart() {
        FakeCommands commands = new FakeCommands(true);
        commands.returnOnlyObservedFields = true;
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ContainerRuntime.ContainerObservation existing = runtime.create(manifest, service, plan);

        assertTrue(existing.running());
        assertTrue(commands.commands.stream().noneMatch(command -> command.contains("up")));
    }

    @Test
    void refusesWholeProductionDotenvBeforeCreatingACandidate() throws Exception {
        Path production = temporary.resolve(".env");
        Files.writeString(production, "AF_DB_MAIN_PASSWORD=prod\n");
        try { Files.setPosixFilePermissions(production, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        properties.getServices().get("agent-service").setEnvFile(production);
        FakeCommands commands = new FakeCommands(false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class, () -> runtime.validateManifest(manifest));

        assertEquals("ENV_FILE_WHOLE_PRODUCTION", failure.code());
        assertTrue(commands.commands.isEmpty());
    }

    @Test
    void boundsComposeAndContainerNamesWithoutLosingDeterminism() {
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(
                mapper, new FakeCommands(false), properties);
        ContainerRuntime.CandidatePlan longPlan = new ContainerRuntime.CandidatePlan(
                "d".repeat(64), "scope", "i-" + "x".repeat(120), plan.generationId(), "A", 28080);

        String project = runtime.projectName(longPlan);
        String container = runtime.containerName(longPlan, "s".repeat(96));

        assertTrue(project.length() <= 63);
        assertTrue(container.length() <= 128);
        assertEquals(project, runtime.projectName(longPlan));
        assertEquals(container, runtime.containerName(longPlan, "s".repeat(96)));
    }

    private final class FakeCommands extends CommandRunner {
        private final boolean startsPresent;
        private int inspectCalls;
        private final List<List<String>> commands = new ArrayList<>();
        private boolean failComposeValidation;
        private boolean returnOnlyObservedFields;
        private String imageInspectId = "sha256:" + "b".repeat(64);

        private FakeCommands(boolean startsPresent) {
            this.startsPresent = startsPresent;
        }

        @Override
        public String run(List<String> arguments, Map<String, String> environment, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("info")) return "27.0.0\n";
            if (arguments.contains("inspect") && arguments.contains("image"))
                return imageInspectId + "\n";
            if (arguments.contains("inspect")) {
                inspectCalls++;
                if (!startsPresent && inspectCalls == 1)
                    throw new ControllerException("COMMAND_FAILED", "missing");
                return inspectJson();
            }
            if (arguments.contains("config") && arguments.contains("--quiet") && failComposeValidation)
                throw new ControllerException("COMMAND_FAILED", "invalid compose");
            return "";
        }

        private String inspectJson() {
            String serviceName = service.path("serviceName").asText();
            String name = "afb-" + plan.deploymentId() + '-' + serviceName + '-' + plan.instanceId();
            if (returnOnlyObservedFields) {
                return """
                        [{"Id":"%s","Name":"/%s","Config":{"Labels":{"alphafrog.host-port":"%d"}},
                          "State":{"Running":true,"Health":{"Status":"healthy"}}}]
                        """.formatted("d".repeat(64), name, plan.hostPort());
            }
            return """
                    [{"Id":"%s","Name":"/%s","Image":"%s",
                      "Config":{"Labels":{"alphafrog.deployment-id":"%s","alphafrog.traffic-scope-id":"%s",
                      "alphafrog.service-name":"%s","alphafrog.instance-id":"%s","alphafrog.release-id":"%s",
                      "alphafrog.deployment-generation-id":"%s","alphafrog.host-port":"%d"}},"State":{"Running":true,"Health":{"Status":"healthy"}},
                      "NetworkSettings":{"Ports":{"%d/tcp":[{"HostIp":"127.0.0.1","HostPort":"%d"}]}}}]
                    """.formatted("d".repeat(64), name,
                    service.path("image").path("localImageId").asText(), plan.deploymentId(), plan.trafficScopeId(),
                    serviceName, "i-one", service.path("releaseId").asText(), plan.generationId(), plan.hostPort(),
                    service.path("runtime").path("containerPort").asInt(), plan.hostPort());
        }
    }
}
