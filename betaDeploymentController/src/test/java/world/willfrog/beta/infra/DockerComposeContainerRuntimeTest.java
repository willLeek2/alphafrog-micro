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
import java.util.LinkedHashMap;
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
                 "runtime":{"containerPort":18080,"hostPorts":[28080,28081],"preStopPolicy":"AGENT_RETIRE_GENERATION_V1",
                            "shutdownProfile":"SPRING_BOOT_HTTP_DUBBO_V1","applicationDrainSeconds":55,
                            "drainGraceSeconds":60,"readinessTimeoutSeconds":120}}]}
                """);
        service = manifest.path("services").path(0);
        plan = new ContainerRuntime.CandidatePlan("beta-main-001", "main-beta", "i-one",
                JsonSupport.deploymentGeneration(manifest), "A", 28080);
    }

    @Test
    void createsFromAnImmutableImageWithoutPersistingTheRetirementSecret() throws Exception {
        FakeCommands commands = new FakeCommands(false, false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);
        runtime.validateManifest(manifest);

        ContainerRuntime.ContainerObservation created = runtime.create(manifest, service, plan, "s".repeat(48));

        assertTrue(created.running());
        assertEquals(28080, created.hostPort());
        Path compose = temporary.resolve("state/compose/i-one.json");
        String content = Files.readString(compose);
        assertFalse(content.contains("s".repeat(48)));
        assertTrue(content.contains("${AF_DEPLOYMENT_RETIREMENT_TOKEN:?missing}"));
        assertTrue(content.contains("SERVER_SHUTDOWN"));
        assertTrue(content.contains("DUBBO_SERVICE_SHUTDOWN_WAIT"));
        assertEquals("s".repeat(48), commands.composeEnvironment.get("AF_DEPLOYMENT_RETIREMENT_TOKEN"));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--quiet")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--no-env-resolution")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("up")));
    }

    @Test
    void createsCandidateWhenDefaultConfigWouldDiscardResolvedEnvFile() throws Exception {
        FakeCommands commands = new FakeCommands(false, false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ContainerRuntime.ContainerObservation created = runtime.create(manifest, service, plan, "s".repeat(48));

        assertTrue(created.running());
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--quiet")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("--no-env-resolution")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("up")));
    }

    @Test
    void refusesToAdoptAContainerWhosePersistedIdentityDoesNotMatch() {
        FakeCommands commands = new FakeCommands(true, true);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class,
                () -> runtime.create(manifest, service, plan, "s".repeat(48)));

        assertEquals("CONTAINER_IDENTITY_CONFLICT", failure.code());
        assertTrue(commands.commands.stream().noneMatch(command -> command.contains("up")));
    }

    @Test
    void refusesWholeProductionDotenvBeforeCreatingACandidate() throws Exception {
        Path production = temporary.resolve(".env");
        Files.writeString(production, "AF_DB_MAIN_PASSWORD=prod\n");
        try { Files.setPosixFilePermissions(production, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        properties.getServices().get("agent-service").setEnvFile(production);
        FakeCommands commands = new FakeCommands(false, false);
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class, () -> runtime.validateManifest(manifest));

        assertEquals("ENV_FILE_WHOLE_PRODUCTION", failure.code());
        assertTrue(commands.commands.isEmpty());
    }

    @Test
    void refusesEffectiveComposeThatAddsTheProductionDotenv() throws Exception {
        FakeCommands commands = new FakeCommands(false, false);
        commands.leakProductionDotenv = true;
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(mapper, commands, properties);

        ControllerException failure = assertThrows(ControllerException.class,
                () -> runtime.create(manifest, service, plan, "s".repeat(48)));

        assertEquals("ENV_FILE_MISMATCH", failure.code());
        assertTrue(commands.commands.stream().noneMatch(command -> command.contains("up")));
    }

    @Test
    void boundsComposeAndContainerNamesWithoutLosingDeterminism() {
        DockerComposeContainerRuntime runtime = new DockerComposeContainerRuntime(
                mapper, new FakeCommands(false, false), properties);
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
        private final boolean wrongIdentity;
        private int inspectCalls;
        private final List<List<String>> commands = new ArrayList<>();
        private Map<String, String> composeEnvironment = Map.of();
        private boolean leakProductionDotenv;

        private FakeCommands(boolean startsPresent, boolean wrongIdentity) {
            this.startsPresent = startsPresent;
            this.wrongIdentity = wrongIdentity;
        }

        @Override
        public String run(List<String> arguments, Map<String, String> environment, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("info")) return "27.0.0\n";
            if (arguments.contains("inspect") && arguments.contains("image"))
                return "sha256:" + "b".repeat(64) + "\n";
            if (arguments.contains("inspect")) {
                inspectCalls++;
                if (!startsPresent && inspectCalls == 1)
                    throw new ControllerException("COMMAND_FAILED", "missing");
                return inspectJson(wrongIdentity);
            }
            if (arguments.contains("config")) {
                if (arguments.contains("--quiet") || arguments.contains("-q")) return "";
                int file = arguments.indexOf("--file") + 1;
                try {
                    ObjectNode root = (ObjectNode) mapper.readTree(Files.readString(Path.of(arguments.get(file))));
                    ObjectNode app = (ObjectNode) root.path("services").path("app");
                    if (arguments.contains("--no-env-resolution")) {
                        if (leakProductionDotenv)
                            app.withArray("env_file")
                                    .add(temporary.resolve(".env").toAbsolutePath().normalize().toString());
                        return mapper.writeValueAsString(root);
                    }
                    mergeResolvedEnvironmentAndDiscardEnvFile(app);
                    return mapper.writeValueAsString(root);
                } catch (Exception exception) { throw new AssertionError(exception); }
            }
            if (arguments.contains("up")) composeEnvironment = new LinkedHashMap<>(environment);
            return "";
        }

        private void mergeResolvedEnvironmentAndDiscardEnvFile(ObjectNode app) throws Exception {
            ObjectNode environment = app.has("environment") && app.get("environment").isObject()
                    ? (ObjectNode) app.get("environment")
                    : app.putObject("environment");
            for (JsonNode item : app.path("env_file")) {
                String location = item.isTextual() ? item.asText() : item.path("path").asText();
                if (location.isBlank()) continue;
                for (String line : Files.readAllLines(Path.of(location))) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int separator = trimmed.indexOf('=');
                    if (separator <= 0) continue;
                    environment.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
                }
            }
            app.remove("env_file");
        }

        private String inspectJson(boolean wrong) {
            String instance = wrong ? "i-other" : "i-one";
            return """
                    [{"Id":"%s","Name":"/afb-beta-main-001-agent-service-i-one","Image":"sha256:%s",
                      "Config":{"Labels":{"alphafrog.deployment-id":"beta-main-001","alphafrog.traffic-scope-id":"main-beta",
                      "alphafrog.service-name":"agent-service","alphafrog.instance-id":"%s","alphafrog.release-id":"release-1",
                      "alphafrog.deployment-generation-id":"%s","alphafrog.host-port":"28080"}},"State":{"Running":true,"Health":{"Status":"healthy"}},
                      "NetworkSettings":{"Ports":{"18080/tcp":[{"HostIp":"127.0.0.1","HostPort":"28080"}]}}}]
                    """.formatted("d".repeat(64), "b".repeat(64), instance, plan.generationId());
        }
    }
}
