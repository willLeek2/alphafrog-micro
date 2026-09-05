package world.willfrog.beta.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ContainerRuntime;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class DockerComposeContainerRuntime implements ContainerRuntime {
    private final ObjectMapper mapper;
    private final CommandRunner commands;
    private final BetaControllerProperties properties;
    private final Path composeRoot;
    private final ServiceEnvironmentFileGuard environmentFiles = new ServiceEnvironmentFileGuard();

    public DockerComposeContainerRuntime(ObjectMapper mapper, CommandRunner commands, BetaControllerProperties properties) {
        this.mapper = mapper;
        this.commands = commands;
        this.properties = properties;
        this.composeRoot = properties.getStateRoot().resolve("compose");
    }

    @Override
    public void validateManifest(JsonNode manifest) {
        if (properties.getMachines().isEmpty() || properties.getMachines().size() > 8)
            throw new ControllerException("MACHINE_CONFIG_INVALID", "Between one and eight Beta machines must be configured");
        requireSafeRegularFile(properties.getHealthcheckScript(), true, "Health-check script");
        Map<String, Path> assignedEnvFiles = new LinkedHashMap<>();
        for (JsonNode service : manifest.path("services")) {
            String machineId = service.path("machineId").asText();
            BetaControllerProperties.Machine machine = machine(machineId);
            String scheme = machine.getDockerHost().getScheme();
            if (!("unix".equals(scheme) || "tcp".equals(scheme) || "ssh".equals(scheme)))
                throw new ControllerException("MACHINE_CONFIG_INVALID", "Docker host scheme is not supported");
            requireIpLiteral(machine.getBindIp(), "Docker bind address");
            requireIpLiteral(machine.getRoutableAddress(), "Routable machine address");
            String serviceName = service.path("serviceName").asText();
            BetaControllerProperties.ServiceTemplate template = properties.getServices().get(serviceName);
            if (template == null || template.getEnvFile() == null)
                throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
            environmentFiles.requireDedicatedFile(serviceName, template.getEnvFile(), assignedEnvFiles);
            assignedEnvFiles.put(serviceName, template.getEnvFile().toAbsolutePath().normalize());
            JsonNode expectedConfigDigest = service.path("runtimeConfigSha256");
            if (!expectedConfigDigest.isMissingNode() && !expectedConfigDigest.isNull()
                    && !expectedConfigDigest.asText().equals(fileSha256(template.getEnvFile())))
                throw new ControllerException("RUNTIME_CONFIG_MISMATCH", "Service environment file differs from the manifest digest");
            if (template.getVolumes().stream().anyMatch(value -> value == null || value.isBlank()
                    || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")))
                throw new ControllerException("SERVICE_CONFIG_INVALID", "Service volume configuration is invalid");
            template.getVolumes().forEach(environmentFiles::rejectProductionDotenvVolume);
        }
    }

    @Override
    public ContainerObservation create(JsonNode manifest, JsonNode service, CandidatePlan plan) {
        String machineId = service.path("machineId").asText();
        BetaControllerProperties.Machine machine = machine(machineId);
        String name = containerName(plan, service.path("serviceName").asText());
        JsonNode existing = inspectDocument(machineId, name);
        if (!existing.isMissingNode()) {
            verifyContainerIdentity(existing, manifest, service, plan, name, machine);
            return observation(machineId, name, existing);
        }
        verifyImage(machineId, service);
        BetaControllerProperties.ServiceTemplate template = properties.getServices()
                .get(service.path("serviceName").asText());
        if (template == null || template.getEnvFile() == null)
            throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
        environmentFiles.requireDedicatedFile(service.path("serviceName").asText(), template.getEnvFile(), Map.of());
        Path compose = writeCompose(manifest, service, plan, name, machine);
        Map<String, String> environment = new HashMap<>();
        verifyEffectiveCompose(manifest, service, plan, name, machine, compose, environment);
        commands.run(docker(machineId, "compose", "--project-name", projectName(plan), "--file", compose.toString(),
                "up", "--detach", "--no-deps", "app"), environment, Duration.ofMinutes(5));
        JsonNode createdDocument = inspectDocument(machineId, name);
        if (createdDocument.isMissingNode())
            throw new ControllerException("CONTAINER_START_FAILED", "Candidate container was not created");
        verifyContainerIdentity(createdDocument, manifest, service, plan, name, machine);
        ContainerObservation created = observation(machineId, name, createdDocument);
        if (!created.running()) throw new ControllerException("CONTAINER_START_FAILED", "Candidate container did not start");
        return created;
    }

    @Override
    public void verifyPersistedInstance(JsonNode manifest, JsonNode service, JsonNode instance) {
        String machineId = instance.path("machineId").asText();
        String name = instance.path("containerName").asText();
        JsonNode actual = inspectDocument(machineId, name);
        if (actual.isMissingNode()) return;
        JsonNode labels = actual.path("Config").path("Labels");
        JsonNode bindings = actual.path("NetworkSettings").path("Ports")
                .path(service.path("runtime").path("containerPort").asText() + "/tcp");
        boolean identityMatches = name.equals(actual.path("Name").asText().replaceFirst("^/", ""))
                && manifest.path("deploymentId").asText().equals(labels.path("alphafrog.deployment-id").asText())
                && manifest.path("trafficScopeId").asText().equals(labels.path("alphafrog.traffic-scope-id").asText())
                && service.path("serviceName").asText().equals(labels.path("alphafrog.service-name").asText())
                && instance.path("instanceId").asText().equals(labels.path("alphafrog.instance-id").asText())
                && instance.path("releaseId").asText().equals(labels.path("alphafrog.release-id").asText())
                && instance.path("deploymentGenerationId").asText()
                    .equals(labels.path("alphafrog.deployment-generation-id").asText())
                && instance.path("hostPort").asText().equals(labels.path("alphafrog.host-port").asText())
                && bindings.isArray() && bindings.size() == 1
                && machine(machineId).getBindIp().equals(bindings.path(0).path("HostIp").asText())
                && instance.path("hostPort").asText().equals(bindings.path(0).path("HostPort").asText());
        if (instance.path("manifestVersion").asLong() == manifest.path("manifestVersion").asLong()) {
            identityMatches = identityMatches && service.path("image").path("localImageId").asText()
                    .equals(actual.path("Image").asText());
        }
        if (!identityMatches || !instance.path("containerId").asText().equals(actual.path("Id").asText()))
            throw new ControllerException("CONTAINER_IDENTITY_CONFLICT", "Container identifier differs from persisted state");
    }

    @Override
    public void assertNoUntrackedInstances(JsonNode manifest, JsonNode service, Set<String> trackedContainerIds) {
        String machineId = service.path("machineId").asText();
        String output = commands.run(docker(machineId, "ps", "--all", "--no-trunc",
                "--filter", "label=alphafrog.deployment-id=" + manifest.path("deploymentId").asText(),
                "--filter", "label=alphafrog.traffic-scope-id=" + manifest.path("trafficScopeId").asText(),
                "--filter", "label=alphafrog.service-name=" + service.path("serviceName").asText(),
                "--format", "{{.ID}}"), Map.of(), Duration.ofSeconds(15));
        for (String containerId : output.split("\\R")) {
            String value = containerId.strip();
            if (!value.isEmpty() && !trackedContainerIds.contains(value))
                throw new ControllerException("UNTRACKED_CONTAINER", "Docker contains an untracked deployment container");
        }
    }

    @Override
    public ContainerObservation inspect(String machineId, String containerName) {
        JsonNode item = inspectDocument(machineId, containerName);
        return item.isMissingNode() ? missing(containerName) : observation(machineId, containerName, item);
    }

    private ContainerObservation observation(String machineId, String containerName, JsonNode item) {
        try {
            String status = item.path("State").path("Health").path("Status").asText("missing");
            ContainerObservation.Health health = switch (status) {
                case "starting" -> ContainerObservation.Health.STARTING;
                case "healthy" -> ContainerObservation.Health.HEALTHY;
                case "unhealthy" -> ContainerObservation.Health.UNHEALTHY;
                default -> ContainerObservation.Health.MISSING;
            };
            JsonNode labels = item.path("Config").path("Labels");
            String machineAddress = machine(machineId).getRoutableAddress();
            int hostPort = Integer.parseInt(labels.path("alphafrog.host-port").asText("0"));
            return new ContainerObservation(item.path("Id").asText(), containerName, machineAddress, hostPort,
                    item.path("State").path("Running").asBoolean(), health);
        } catch (NumberFormatException exception) {
            throw new ControllerException("CONTAINER_INSPECT_INVALID", "Docker returned an invalid container observation", exception);
        }
    }

    private JsonNode inspectDocument(String machineId, String containerName) {
        commands.run(docker(machineId, "info", "--format", "{{.ServerVersion}}"), Map.of(), Duration.ofSeconds(15));
        try {
            String output = commands.run(docker(machineId, "inspect", containerName), Map.of(), Duration.ofSeconds(15));
            JsonNode item = mapper.readTree(output).path(0);
            if (!item.isObject())
                throw new ControllerException("CONTAINER_INSPECT_INVALID", "Docker returned an invalid container observation");
            return item;
        } catch (ControllerException exception) {
            if ("COMMAND_FAILED".equals(exception.code())) return mapper.missingNode();
            throw exception;
        } catch (IOException exception) {
            throw new ControllerException("CONTAINER_INSPECT_INVALID", "Docker returned an invalid container observation", exception);
        }
    }

    @Override
    public void stop(String machineId, String containerName, int timeoutSeconds) {
        commands.run(docker(machineId, "stop", "--signal", "SIGTERM", "--timeout",
                Integer.toString(timeoutSeconds), containerName), Map.of(), Duration.ofSeconds(timeoutSeconds + 30L));
    }

    @Override
    public void remove(String machineId, String containerName) {
        if (inspect(machineId, containerName).containerId().isEmpty()) return;
        commands.run(docker(machineId, "rm", "--force", containerName), Map.of(), Duration.ofSeconds(30));
        if (!inspect(machineId, containerName).containerId().isEmpty())
            throw new ControllerException("CONTAINER_REMOVE_NOT_CONFIRMED", "Candidate container still exists after removal");
    }

    private Path writeCompose(JsonNode manifest, JsonNode service, CandidatePlan plan, String name,
                              BetaControllerProperties.Machine machine) {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", projectName(plan));
        ObjectNode services = root.putObject("services");
        ObjectNode app = services.putObject("app");
        app.put("image", service.path("image").path("repositoryDigest").asText());
        app.put("container_name", name);
        app.put("pull_policy", "never");
        app.put("stop_signal", "SIGTERM");
        app.put("stop_grace_period", service.path("runtime").path("drainGraceSeconds").asInt() + "s");
        ObjectNode labels = app.putObject("labels");
        labels.put("alphafrog.deployment-id", plan.deploymentId());
        labels.put("alphafrog.traffic-scope-id", plan.trafficScopeId());
        labels.put("alphafrog.service-name", service.path("serviceName").asText());
        labels.put("alphafrog.instance-id", plan.instanceId());
        labels.put("alphafrog.release-id", service.path("releaseId").asText());
        labels.put("alphafrog.deployment-generation-id", plan.generationId());
        labels.put("alphafrog.host-port", Integer.toString(plan.hostPort()));
        ObjectNode environment = app.putObject("environment");
        environment.put("AF_DEPLOYMENT_ID", plan.deploymentId());
        environment.put("AF_DEPLOYMENT_GENERATION_ID", plan.generationId());
        environment.put("AF_LANE_TAG", plan.trafficScopeId());
        environment.put("AF_SERVICE_VERSION", service.path("releaseId").asText());
        environment.put("AF_GIT_COMMIT", manifest.path("gitCommit").asText());
        String localImageId = service.path("image").path("localImageId").asText();
        environment.put("AF_IMAGE_DIGEST", localImageId);
        environment.put("OTEL_SERVICE_NAME", service.path("serviceName").asText());
        environment.put("OTEL_RESOURCE_ATTRIBUTES", otelResourceAttributes(manifest, service, plan, localImageId));
        if ("frontend".equals(service.path("serviceName").asText())) {
            boolean laneEntry = !"main-beta".equals(plan.trafficScopeId());
            environment.put("AF_LANE_ENTRY_ENABLED", Boolean.toString(laneEntry));
            environment.put("AF_LANE_TRAFFIC_SCOPE_ID", plan.trafficScopeId());
        }
        environment.put("AF_DUBBO_PORT_TO_REGISTRY", Integer.toString(plan.hostPort()));
        environment.put("AF_DUBBO_REGISTRY_REGISTER", "false");
        environment.put("SPRING_APPLICATION_JSON", betaRoutingConfiguration());
        int applicationDrainSeconds = service.path("runtime").path("applicationDrainSeconds").asInt();
        environment.put("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS",
                Integer.toString(applicationDrainSeconds));
        JsonNode registration = service.path("registration");
        boolean coordinatedAgentShutdown = coordinatedAgentShutdown(registration);
        int finalizationMarginSeconds = finalizationMarginSeconds(applicationDrainSeconds);
        environment.put("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS",
                Integer.toString(finalizationMarginSeconds));
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_ENABLED", "true");
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_SERVER_ADDRESS",
                properties.getNacos().getServerAddress());
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_NAMESPACE", normalizedNacosNamespace());
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_GROUP_NAME",
                registration.path("groupName").asText());
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_ABSENCE_CONFIRMATION_SECONDS",
                Integer.toString(applicationDrainSeconds));
        environment.put("SERVER_SHUTDOWN", "graceful");
        // Agent 自己在 ContextClosedEvent 内等待自然处理窗口，并在返回前把 Spring
        // 后续 phase 等待设为 0；其余服务仍由 Spring 的有序关闭等待完整期限。
        environment.put("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE",
                coordinatedAgentShutdown ? "0s" : applicationDrainSeconds + "s");
        String shutdownProfile = service.path("runtime").path("shutdownProfile").asText();
        if ("SPRING_BOOT_DUBBO_V1".equals(shutdownProfile)
                || "SPRING_BOOT_HTTP_DUBBO_V1".equals(shutdownProfile)) {
            // Agent 的 Dubbo 阶段只拿收尾余量，并由进程内登记的绝对截止时间再次截短。
            // 其它 Dubbo 服务继续使用公共处理期限，Docker 仍是所有服务的最终硬边界。
            int dubboWaitSeconds = coordinatedAgentShutdown
                    ? finalizationMarginSeconds
                    : applicationDrainSeconds;
            environment.put("DUBBO_SERVICE_SHUTDOWN_WAIT", Integer.toString(dubboWaitSeconds * 1000));
        }
        BetaControllerProperties.ServiceTemplate template = properties.getServices().get(service.path("serviceName").asText());
        if (template != null && template.getEnvFile() != null)
            app.putArray("env_file").add(template.getEnvFile().toAbsolutePath().normalize().toString());
        ArrayNode ports = app.putArray("ports");
        ObjectNode port = ports.addObject();
        port.put("target", service.path("runtime").path("containerPort").asInt());
        port.put("published", Integer.toString(plan.hostPort()));
        port.put("host_ip", machine.getBindIp());
        port.put("protocol", "tcp");
        port.put("mode", "host");
        ArrayNode volumes = app.putArray("volumes");
        volumes.add(properties.getHealthcheckScript().toString() + ':' + properties.getHealthcheckScript() + ":ro");
        if (template != null) template.getVolumes().forEach(volumes::add);
        ObjectNode health = app.putObject("healthcheck");
        health.putArray("test").add("CMD").add(properties.getHealthcheckScript().toString())
                .add("127.0.0.1").add(Integer.toString(service.path("runtime").path("containerPort").asInt()));
        health.put("interval", "2s");
        health.put("timeout", "1s");
        health.put("retries", Math.max(3, service.path("runtime").path("readinessTimeoutSeconds").asInt() / 2));
        try {
            if (Files.isSymbolicLink(properties.getStateRoot()) || Files.isSymbolicLink(composeRoot))
                throw new ControllerException("COMPOSE_PATH_UNSAFE", "Compose state path must not be a symbolic link");
            Files.createDirectories(composeRoot);
            Path target = composeRoot.resolve(plan.instanceId() + ".json");
            if (!target.normalize().startsWith(composeRoot.normalize()) || Files.isSymbolicLink(target))
                throw new ControllerException("COMPOSE_PATH_UNSAFE", "Compose state path is unsafe");
            Path temporary = Files.createTempFile(composeRoot, "." + plan.instanceId(), ".tmp");
            try {
                Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + '\n');
                try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            try (var channel = java.nio.channels.FileChannel.open(composeRoot, java.nio.file.StandardOpenOption.READ)) {
                channel.force(true);
            }
            return target;
        } catch (IOException exception) {
            throw new ControllerException("COMPOSE_WRITE_FAILED", "Unable to write candidate Compose file", exception);
        }
    }

    private String betaRoutingConfiguration() {
        String server = properties.getNacos().getServerAddress();
        if (server == null || server.isBlank())
            throw new ControllerException("NACOS_CONFIG_INVALID", "Nacos server address is required for Beta routing");
        String namespace = normalizedNacosNamespace();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode dubbo = root.putObject("dubbo");
        dubbo.putObject("registry").put("register", false);
        ObjectNode registries = dubbo.putObject("registries");
        registryConfig(registries.putObject("beta"), server, namespace, "alphafrog-beta", "beta", true);
        registryConfig(registries.putObject("production"), server, namespace, "DEFAULT_GROUP", "prod", false);
        dubbo.putObject("consumer").put("cluster", "zone-aware");
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to render Beta routing configuration", impossible);
        }
    }

    private String normalizedNacosNamespace() {
        String namespace = properties.getNacos().getNamespace();
        return namespace == null || namespace.isBlank() ? "public" : namespace;
    }

    private void registryConfig(ObjectNode target, String server, String namespace, String group, String zone,
                                boolean preferred) {
        target.put("address", "nacos://" + server + "?namespace=" + namespace + "&group=" + group + "&zone=" + zone);
        target.put("username", "${AF_CONFIG_NACOS_USERNAME:}");
        target.put("password", "${AF_CONFIG_NACOS_PASSWORD:}");
        target.put("register", false);
        target.put("preferred", preferred);
        target.put("use-as-config-center", false);
        target.put("use-as-metadata-center", false);
    }

    private void verifyImage(String machineId, JsonNode service) {
        String actual = commands.run(docker(machineId, "image", "inspect", service.path("image").path("repositoryDigest").asText(),
                "--format", "{{.Id}}"), Map.of(), Duration.ofSeconds(30)).strip();
        if (!actual.equals(service.path("image").path("localImageId").asText()))
            throw new ControllerException("IMAGE_ID_MISMATCH", "Installed image does not match the manifest Image ID");
    }

    private void verifyEffectiveCompose(JsonNode manifest, JsonNode service, CandidatePlan plan, String name,
                                        BetaControllerProperties.Machine machine, Path compose,
                                        Map<String, String> processEnvironment) {
        String machineId = service.path("machineId").asText();
        commands.run(docker(machineId, "compose", "--project-name", projectName(plan), "--file", compose.toString(),
                "config", "--quiet"), processEnvironment, Duration.ofSeconds(30));
        String output = commands.run(docker(machineId, "compose", "--project-name", projectName(plan),
                "--file", compose.toString(), "config", "--no-env-resolution", "--format", "json"),
                processEnvironment, Duration.ofSeconds(30));
        try {
            JsonNode app = mapper.readTree(output).path("services").path("app");
            JsonNode environment = app.path("environment");
            JsonNode port = app.path("ports").path(0);
            JsonNode health = app.path("healthcheck").path("test");
            boolean valid = app.isObject()
                    && service.path("image").path("repositoryDigest").asText().equals(app.path("image").asText())
                    && name.equals(app.path("container_name").asText())
                    && "never".equals(app.path("pull_policy").asText())
                    && "SIGTERM".equals(app.path("stop_signal").asText())
                    && Integer.toString(plan.hostPort()).equals(port.path("published").asText())
                    && service.path("runtime").path("containerPort").asText().equals(port.path("target").asText())
                    && machine.getBindIp().equals(port.path("host_ip").asText())
                    && "tcp".equals(port.path("protocol").asText())
                    && "host".equals(port.path("mode").asText())
                    && plan.deploymentId().equals(environment.path("AF_DEPLOYMENT_ID").asText())
                    && plan.generationId().equals(environment.path("AF_DEPLOYMENT_GENERATION_ID").asText())
                    && plan.trafficScopeId().equals(environment.path("AF_LANE_TAG").asText())
                    && service.path("releaseId").asText().equals(environment.path("AF_SERVICE_VERSION").asText())
                    && manifest.path("gitCommit").asText().equals(environment.path("AF_GIT_COMMIT").asText())
                    && service.path("image").path("localImageId").asText()
                        .equals(environment.path("AF_IMAGE_DIGEST").asText())
                    && service.path("serviceName").asText().equals(environment.path("OTEL_SERVICE_NAME").asText())
                    && otelResourceAttributes(manifest, service, plan,
                        service.path("image").path("localImageId").asText())
                        .equals(environment.path("OTEL_RESOURCE_ATTRIBUTES").asText())
                    && Integer.toString(plan.hostPort()).equals(environment.path("AF_DUBBO_PORT_TO_REGISTRY").asText())
                    && "false".equals(environment.path("AF_DUBBO_REGISTRY_REGISTER").asText())
                    && betaRoutingConfiguration().equals(environment.path("SPRING_APPLICATION_JSON").asText())
                    && Integer.toString(service.path("runtime").path("applicationDrainSeconds").asInt())
                        .equals(environment.path("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS").asText())
                    && Integer.toString(finalizationMarginSeconds(
                        service.path("runtime").path("applicationDrainSeconds").asInt())).equals(environment
                        .path("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS").asText())
                    && "true".equals(environment.path("AGENT_LANGCHAIN_GENERATION_REAPER_ENABLED").asText())
                    && properties.getNacos().getServerAddress().equals(environment
                        .path("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_SERVER_ADDRESS").asText())
                    && normalizedNacosNamespace().equals(environment
                        .path("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_NAMESPACE").asText())
                    && service.path("registration").path("groupName").asText().equals(environment
                        .path("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_GROUP_NAME").asText())
                    && Integer.toString(service.path("runtime").path("applicationDrainSeconds").asInt())
                        .equals(environment.path("AGENT_LANGCHAIN_GENERATION_REAPER_ABSENCE_CONFIRMATION_SECONDS").asText())
                    && "graceful".equals(environment.path("SERVER_SHUTDOWN").asText())
                    && (coordinatedAgentShutdown(service.path("registration"))
                        ? "0s"
                        : service.path("runtime").path("applicationDrainSeconds").asInt() + "s")
                        .equals(environment.path("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE").asText())
                    && health.isArray() && health.size() == 4
                    && "CMD".equals(health.path(0).asText())
                    && properties.getHealthcheckScript().toString().equals(health.path(1).asText())
                    && "127.0.0.1".equals(health.path(2).asText())
                    && service.path("runtime").path("containerPort").asText().equals(health.path(3).asText());
            String shutdownProfile = service.path("runtime").path("shutdownProfile").asText();
            if ("SPRING_BOOT_DUBBO_V1".equals(shutdownProfile)
                    || "SPRING_BOOT_HTTP_DUBBO_V1".equals(shutdownProfile)) {
                int applicationDrainSeconds = service.path("runtime").path("applicationDrainSeconds").asInt();
                int dubboWaitSeconds = coordinatedAgentShutdown(service.path("registration"))
                        ? finalizationMarginSeconds(applicationDrainSeconds)
                        : applicationDrainSeconds;
                valid = valid && Integer.toString(dubboWaitSeconds * 1000)
                        .equals(environment.path("DUBBO_SERVICE_SHUTDOWN_WAIT").asText());
            }
            if ("frontend".equals(service.path("serviceName").asText())) {
                valid = valid
                        && Boolean.toString(!"main-beta".equals(plan.trafficScopeId()))
                        .equals(environment.path("AF_LANE_ENTRY_ENABLED").asText())
                        && plan.trafficScopeId().equals(environment.path("AF_LANE_TRAFFIC_SCOPE_ID").asText());
            }
            if (!valid)
                throw new ControllerException("COMPOSE_CONFIG_INVALID", "Effective Compose configuration does not match the deployment contract");
            BetaControllerProperties.ServiceTemplate template = properties.getServices()
                    .get(service.path("serviceName").asText());
            if (template == null || template.getEnvFile() == null)
                throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
            environmentFiles.requireEffectiveCompose(app, template.getEnvFile());
        } catch (IOException exception) {
            throw new ControllerException("COMPOSE_CONFIG_INVALID", "Docker Compose returned invalid effective configuration", exception);
        }
    }

    private static boolean coordinatedAgentShutdown(JsonNode registration) {
        return "agent-langchain-service".equals(registration.path("applicationName").asText());
    }

    private static int finalizationMarginSeconds(int totalSeconds) {
        int normalizedTotal = Math.max(0, totalSeconds);
        return normalizedTotal <= 1 ? 0 : Math.min(5, normalizedTotal - 1);
    }

    private String otelResourceAttributes(JsonNode manifest, JsonNode service, CandidatePlan plan,
                                          String localImageId) {
        return "deployment.id=" + plan.deploymentId()
                + ",lane.tag=" + plan.trafficScopeId()
                + ",service.version=" + service.path("releaseId").asText()
                + ",git.commit=" + manifest.path("gitCommit").asText()
                + ",image.digest=" + localImageId;
    }

    private void verifyContainerIdentity(JsonNode actual, JsonNode manifest, JsonNode service,
                                         CandidatePlan plan, String name,
                                         BetaControllerProperties.Machine machine) {
        JsonNode labels = actual.path("Config").path("Labels");
        boolean identityMatches = name.equals(actual.path("Name").asText().replaceFirst("^/", ""))
                && plan.deploymentId().equals(labels.path("alphafrog.deployment-id").asText())
                && plan.trafficScopeId().equals(labels.path("alphafrog.traffic-scope-id").asText())
                && service.path("serviceName").asText().equals(labels.path("alphafrog.service-name").asText())
                && plan.instanceId().equals(labels.path("alphafrog.instance-id").asText())
                && service.path("releaseId").asText().equals(labels.path("alphafrog.release-id").asText())
                && plan.generationId().equals(labels.path("alphafrog.deployment-generation-id").asText())
                && Integer.toString(plan.hostPort()).equals(labels.path("alphafrog.host-port").asText())
                && service.path("image").path("localImageId").asText().equals(actual.path("Image").asText());
        JsonNode bindings = actual.path("NetworkSettings").path("Ports")
                .path(service.path("runtime").path("containerPort").asText() + "/tcp");
        identityMatches = identityMatches && bindings.isArray() && bindings.size() == 1
                && machine.getBindIp().equals(bindings.path(0).path("HostIp").asText())
                && Integer.toString(plan.hostPort()).equals(bindings.path(0).path("HostPort").asText());
        if (!identityMatches)
            throw new ControllerException("CONTAINER_IDENTITY_CONFLICT", "Existing container does not match the persisted candidate plan");
        if (!JsonSupport.deploymentGeneration(manifest).equals(plan.generationId()))
            throw new ControllerException("CONTAINER_IDENTITY_CONFLICT", "Candidate generation differs from the accepted manifest");
    }

    private List<String> docker(String machineId, String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("--host");
        command.add(machine(machineId).getDockerHost().toString());
        command.addAll(List.of(args));
        return command;
    }

    private BetaControllerProperties.Machine machine(String id) {
        BetaControllerProperties.Machine value = properties.getMachines().get(id);
        if (value == null || value.getDockerHost() == null || value.getBindIp() == null || value.getRoutableAddress() == null)
            throw new ControllerException("MACHINE_UNKNOWN", "Machine is not configured: " + id);
        return value;
    }

    private void requireSafeRegularFile(Path path, boolean executable, String label) {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || (executable && !Files.isExecutable(path)))
            throw new ControllerException("SERVICE_CONFIG_INVALID", label + " is missing or unsafe");
    }

    private void requireIpLiteral(String value, String label) {
        if (value == null || !value.matches("[0-9A-Fa-f:.]+"))
            throw new ControllerException("MACHINE_CONFIG_INVALID", label + " must be an IP literal");
        try { InetAddress.getByName(value); }
        catch (IOException exception) {
            throw new ControllerException("MACHINE_CONFIG_INVALID", label + " is not a valid IP literal", exception);
        }
    }

    private String fileSha256(Path path) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ControllerException("SERVICE_CONFIG_INVALID", "Unable to verify the service environment file", exception);
        }
    }

    String projectName(CandidatePlan plan) {
        return boundedName(("afb-" + plan.deploymentId() + '-' + plan.instanceId()).toLowerCase(), 63);
    }

    String containerName(CandidatePlan plan, String service) {
        return boundedName(("afb-" + plan.deploymentId() + '-' + service + '-' + plan.instanceId()).toLowerCase(), 128);
    }

    private String boundedName(String value, int maximumLength) {
        if (value.length() <= maximumLength) return value;
        String suffix = JsonSupport.hexSha256(value).substring(0, 12);
        return value.substring(0, maximumLength - suffix.length() - 1) + '-' + suffix;
    }
    private ContainerObservation missing(String name) { return new ContainerObservation("", name, "", 0, false, ContainerObservation.Health.MISSING); }
}
