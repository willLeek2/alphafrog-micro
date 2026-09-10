package world.willfrog.beta.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ContainerRuntime;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class DockerComposeContainerRuntime implements ContainerRuntime {
    private static final Logger log = LoggerFactory.getLogger(DockerComposeContainerRuntime.class);
    private final ObjectMapper mapper;
    private final CommandRunner commands;
    private final BetaControllerProperties properties;
    private final Path composeRoot;
    private final ServiceEnvironmentFileGuard environmentFiles = new ServiceEnvironmentFileGuard();

    public DockerComposeContainerRuntime(ObjectMapper mapper, CommandRunner commands, BetaControllerProperties properties) {
        this.mapper = mapper;
        this.commands = commands;
        this.properties = properties;
        this.composeRoot = properties.getStateRoot().toAbsolutePath().normalize().resolve("compose");
    }

    @Override
    public void validateHostPrerequisites() {
        if (properties.getMachines().isEmpty() || properties.getMachines().size() > 8)
            throw new ControllerException("MACHINE_CONFIG_INVALID", "Between one and eight Beta machines must be configured");
        requireSafeRegularFile(properties.getHealthcheckScript(), true, "Health-check script");
        requireTracesEndpoint();
        for (String machineId : properties.getMachines().keySet()) {
            BetaControllerProperties.Machine machine = machine(machineId);
            String scheme = machine.getDockerHost().getScheme();
            if (!("unix".equals(scheme) || "tcp".equals(scheme) || "ssh".equals(scheme)))
                throw new ControllerException("MACHINE_CONFIG_INVALID",
                        "Beta machine " + machineId + " uses unsupported Docker host " + machine.getDockerHost());
            requireBetaNetworkConfig(machineId, machine);
        }
    }

    // 宿主 PostgreSQL 的 pg_hba 只放行 172.16.0.0/12：固定网络的子网必须留在这一段内，
    // 否则容器会被数据库在连接建立时直接拒绝（不改 pg_hba 是本设计的硬约束）。
    private static void requireBetaNetworkConfig(String machineId, BetaControllerProperties.Machine machine) {
        if (isBlank(machine.getNetworkName()) || isBlank(machine.getNetworkSubnet())
                || isBlank(machine.getNetworkGateway()))
            throw new ControllerException("MACHINE_CONFIG_INVALID",
                    "Beta machine " + machineId + " network name, subnet and gateway are required");
        long[] subnet = parseIpv4Cidr(machineId, machine.getNetworkSubnet());
        if (subnet[1] < 12 || (subnet[0] & PREFIX_12_MASK) != (IPV4_172_16 & PREFIX_12_MASK))
            throw new ControllerException("MACHINE_CONFIG_INVALID",
                    "Beta machine " + machineId + " subnet " + machine.getNetworkSubnet()
                            + " must stay inside 172.16.0.0/12 to remain reachable for the host PostgreSQL");
    }

    private static final long IPV4_172_16 = (172L << 24) | (16L << 16);
    private static final long PREFIX_12_MASK = 0xFFF00000L;

    private static long[] parseIpv4Cidr(String machineId, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) throw invalidSubnet(machineId, cidr);
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) throw invalidSubnet(machineId, cidr);
        long address = 0;
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet.trim());
                if (value < 0 || value > 255) throw invalidSubnet(machineId, cidr);
                address = (address << 8) | value;
            } catch (NumberFormatException exception) {
                throw invalidSubnet(machineId, cidr);
            }
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException exception) {
            throw invalidSubnet(machineId, cidr);
        }
        if (prefix < 0 || prefix > 32) throw invalidSubnet(machineId, cidr);
        return new long[] {address, prefix};
    }

    private static ControllerException invalidSubnet(String machineId, String cidr) {
        return new ControllerException("MACHINE_CONFIG_INVALID",
                "Beta machine " + machineId + " subnet " + cidr + " is not a valid IPv4 CIDR");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void validateManifestEnvironment(JsonNode manifest) {
        for (JsonNode service : manifest.path("services")) {
            String machineId = service.path("machineId").asText();
            machine(machineId);
            String serviceName = service.path("serviceName").asText();
            BetaControllerProperties.ServiceTemplate template = properties.getServices().get(serviceName);
            if (template == null || template.getEnvFile() == null)
                throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
            environmentFiles.requireDedicatedFile(template.getEnvFile());
            JsonNode expectedConfigDigest = service.path("runtimeConfigSha256");
            if (!expectedConfigDigest.isMissingNode() && !expectedConfigDigest.isNull()
                    && !expectedConfigDigest.asText().equals(fileSha256(template.getEnvFile())))
                throw new ControllerException("RUNTIME_CONFIG_MISMATCH", "Service environment file differs from the manifest digest");
            if (template.getVolumes().stream().anyMatch(value -> value == null || value.isBlank()
                    || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")))
                throw new ControllerException("SERVICE_CONFIG_INVALID", "Service volume configuration is invalid");
            template.getVolumes().forEach(environmentFiles::rejectProductionDotenvVolume);
            if (template.getVolumes().stream().anyMatch(this::usesControllerManagedMount))
                throw new ControllerException("SERVICE_CONFIG_INVALID",
                        "Service volumes must not replace controller-managed observability mounts");
            validateJavaAgent(template);
            prepareLogDirectory(serviceName);
        }
    }

    @Override
    public ContainerObservation create(JsonNode manifest, JsonNode service, CandidatePlan plan) {
        String machineId = service.path("machineId").asText();
        BetaControllerProperties.Machine machine = machine(machineId);
        String name = containerName(plan, service.path("serviceName").asText());
        JsonNode existing = inspectDocument(machineId, name);
        if (!existing.isMissingNode()) {
            return observation(machineId, name, existing);
        }
        verifyImage(machineId, service);
        BetaControllerProperties.ServiceTemplate template = properties.getServices()
                .get(service.path("serviceName").asText());
        if (template == null || template.getEnvFile() == null)
            throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
        environmentFiles.requireDedicatedFile(template.getEnvFile());
        if (template.getVolumes().stream().anyMatch(this::usesControllerManagedMount))
            throw new ControllerException("SERVICE_CONFIG_INVALID",
                    "Service volumes must not replace controller-managed observability mounts");
        validateJavaAgent(template);
        ensureBetaNetwork(machineId, machine);
        Path compose = writeCompose(manifest, service, plan, name, machine);
        Map<String, String> environment = Map.of();
        boolean containerMayExist = false;
        try {
            commands.run(docker(machineId, "compose", "--project-name", projectName(plan), "--file", compose.toString(),
                    "config", "--quiet"), environment, Duration.ofSeconds(30));
            containerMayExist = true;
            commands.run(docker(machineId, "compose", "--project-name", projectName(plan), "--file", compose.toString(),
                    "up", "--detach", "--no-deps", "app"), environment, Duration.ofMinutes(5));
            JsonNode createdDocument = inspectDocument(machineId, name);
            if (createdDocument.isMissingNode())
                throw new ControllerException("CONTAINER_START_FAILED", "Candidate container was not created");
            ContainerObservation created = observation(machineId, name, createdDocument);
            if (!created.running())
                throw new ControllerException("CONTAINER_START_FAILED", "Candidate container did not start");
            return created;
        } catch (RuntimeException failure) {
            if (containerMayExist) bestEffortRemoveFailedCandidate(machineId, name, failure);
            bestEffortRemoveCompose(plan.instanceId(), failure);
            throw failure;
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

    @Override
    public void removeCompose(String instanceId) {
        Path target = composePath(instanceId);
        try {
            if (Files.isSymbolicLink(target))
                throw new ControllerException("COMPOSE_PATH_UNSAFE", "Compose state file must not be a symbolic link");
            if (!Files.deleteIfExists(target)) return;
            try (var channel = java.nio.channels.FileChannel.open(composeRoot, java.nio.file.StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new ControllerException("COMPOSE_DELETE_FAILED", "Unable to remove candidate Compose file", exception);
        }
    }

    private void bestEffortRemoveFailedCandidate(String machineId, String name, RuntimeException original) {
        try {
            remove(machineId, name);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            log.error("Unable to remove candidate container {} after create failed", name, cleanupFailure);
        }
    }

    private void bestEffortRemoveCompose(String instanceId, RuntimeException original) {
        try {
            removeCompose(instanceId);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            log.error("Unable to remove Compose file for candidate {} after create failed", instanceId, cleanupFailure);
        }
    }

    private void ensureBetaNetwork(String machineId, BetaControllerProperties.Machine machine) {
        if (betaNetworkExists(machineId, machine.getNetworkName())) return;
        try {
            commands.run(docker(machineId, "network", "create", "--driver", "bridge",
                    "--subnet", machine.getNetworkSubnet(), "--gateway", machine.getNetworkGateway(),
                    machine.getNetworkName()), Map.of(), Duration.ofSeconds(30));
        } catch (ControllerException creationFailure) {
            // 两个候选并发创建会撞「网络已存在」；网络确实在就视为成功。
            if (!betaNetworkExists(machineId, machine.getNetworkName())) throw creationFailure;
        }
    }

    private boolean betaNetworkExists(String machineId, String networkName) {
        try {
            commands.run(docker(machineId, "network", "inspect", networkName), Map.of(), Duration.ofSeconds(15));
            return true;
        } catch (ControllerException failure) {
            if ("COMMAND_FAILED".equals(failure.code())) return false;
            throw failure;
        }
    }

    private Path writeCompose(JsonNode manifest, JsonNode service, CandidatePlan plan, String name,
                              BetaControllerProperties.Machine machine) {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", projectName(plan));
        // 不声明网络时 compose 会按项目名自动建一次性网络：每次滚动一个新项目就多一个
        // 网络、退役也不回收，docker 默认地址池被占满后容器漂出 172.16/12，被宿主
        // PostgreSQL 的 pg_hba 拒绝。照生产 docker-compose.yml 的固定网络做法，所有
        // 实例共用一个 external 网络（由控制器幂等创建，compose 不建也不删）。
        ObjectNode fixedNetwork = root.putObject("networks").putObject("default");
        fixedNetwork.put("name", machine.getNetworkName());
        fixedNetwork.put("external", true);
        ObjectNode services = root.putObject("services");
        ObjectNode app = services.putObject("app");
        app.put("image", service.path("image").path("localImageId").asText());
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
        environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", requireTracesEndpoint());
        environment.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        environment.put("OTEL_TRACES_EXPORTER", "otlp");
        environment.put("OTEL_METRICS_EXPORTER", "none");
        environment.put("OTEL_LOGS_EXPORTER", "none");
        if ("frontend".equals(service.path("serviceName").asText())) {
            // 所有 Beta frontend 都开入口：主 Beta frontend 是共用入口，靠请求头指定泳道；
            // 泳道名只注入给非 main-beta 的泳道 frontend，从该口进来的流量一律打本部署的标。
            environment.put("AF_LANE_ENTRY_ENABLED", "true");
            if (!"main-beta".equals(plan.trafficScopeId())) {
                environment.put("AF_LANE_TRAFFIC_SCOPE_ID", plan.trafficScopeId());
            }
        }
        if (service.path("registration").isObject()) {
            environment.put("DUBBO_IP_TO_REGISTRY", machine.getRoutableAddress());
            environment.put("DUBBO_PORT_TO_REGISTRY", Integer.toString(plan.hostPort()));
        }
        environment.put("AF_DUBBO_REGISTRY_REGISTER", "false");
        environment.put("SPRING_APPLICATION_JSON", betaRoutingConfiguration(service, plan));
        int applicationDrainSeconds = service.path("runtime").path("applicationDrainSeconds").asInt();
        environment.put("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_AWAIT_SECONDS",
                Integer.toString(applicationDrainSeconds));
        boolean coordinatedAgentShutdown = coordinatedAgentShutdown(service);
        int finalizationMarginSeconds = finalizationMarginSeconds(applicationDrainSeconds);
        environment.put("AGENT_LANGCHAIN_RUN_EXECUTOR_SHUTDOWN_FINALIZATION_MARGIN_SECONDS",
                Integer.toString(finalizationMarginSeconds));
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_ENABLED", "true");
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_SERVER_ADDRESS",
                properties.getNacos().getServerAddress());
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_NAMESPACE", normalizedNacosNamespace());
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_NACOS_GROUP_NAME", "alphafrog-beta");
        environment.put("AGENT_LANGCHAIN_GENERATION_REAPER_ABSENCE_CONFIRMATION_SECONDS",
                Integer.toString(applicationDrainSeconds));
        environment.put("SERVER_SHUTDOWN", "graceful");
        // Agent 自己在 ContextClosedEvent 内等待自然处理窗口，Spring 后续阶段不再
        // 追加等待；其余服务仍由 Spring 的有序关闭等待完整期限。
        environment.put("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE",
                coordinatedAgentShutdown ? "0s" : applicationDrainSeconds + "s");
        String shutdownProfile = service.path("runtime").path("shutdownProfile").asText();
        if ("SPRING_BOOT_DUBBO_V1".equals(shutdownProfile)
                || "SPRING_BOOT_HTTP_DUBBO_V1".equals(shutdownProfile)) {
            // Agent 的 Dubbo 阶段只使用收尾余量；其它 Dubbo 服务继续使用公共处理
            // 期限，Docker 仍是所有服务的最终强制停止边界。
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
        if (template != null) {
            if (template.isJavaAgentEnabled()) {
                String javaAgentContainerPath = "/otel/javaagent.jar";
                environment.put("JAVA_TOOL_OPTIONS", javaToolOptions(template, javaAgentContainerPath));
                volumes.add(properties.getObservability().getJavaAgentJar().toString()
                        + ':' + javaAgentContainerPath + ":ro");
            }
            volumes.add(prepareLogDirectory(service.path("serviceName").asText()) + ":/app/logs");
            template.getVolumes().forEach(volumes::add);
        }
        // Compose 在建容器前会先对文件里的 ${...} 做变量插值，而环境值里的
        // ${AF_CONFIG_NACOS_USERNAME:} 这类 Spring 占位符不是合法的插值语法，会让
        // docker compose config 直接拒绝。控制器写入的环境值都要原样进容器，统一把
        // $ 转义成 $$（compose 会还原成字面 $，Spring 照常解析占位符）。
        List<String> environmentNames = new ArrayList<>();
        environment.fieldNames().forEachRemaining(environmentNames::add);
        for (String environmentName : environmentNames)
            environment.put(environmentName, environment.path(environmentName).asText().replace("$", "$$"));
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
            Path target = composePath(plan.instanceId());
            if (Files.isSymbolicLink(target))
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

    private String betaRoutingConfiguration(JsonNode service, CandidatePlan plan) {
        String server = properties.getNacos().getServerAddress();
        if (server == null || server.isBlank())
            throw new ControllerException("NACOS_CONFIG_INVALID", "Nacos server address is required for Beta routing");
        String namespace = normalizedNacosNamespace();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode dubbo = root.putObject("dubbo");
        dubbo.putObject("registry").put("register", false);
        ObjectNode registries = dubbo.putObject("registries");
        registryConfig(registries.putObject("beta"), server, namespace, "alphafrog-beta", "beta", true, true);
        registryConfig(registries.putObject("production"), server, namespace, "DEFAULT_GROUP", "prod", false, false);
        // 不写 dubbo.consumer.cluster：多注册中心时 Dubbo 的外层合并集群默认就是 zone-aware，
        // 写到全局 consumer 会让单注册中心内部也用 zone-aware，内层把直连提供者强转成
        // ClusterInvoker 直接 ClassCastException（ZoneAwareClusterInvoker.doInvoke 72 行）
        if (service.path("registration").isObject()) {
            ObjectNode providerParameters = dubbo.putObject("provider").putObject("parameters");
            providerParameters.put("alphafrog.deployment-id", plan.deploymentId());
            providerParameters.put("alphafrog.traffic-scope-id", plan.trafficScopeId());
            providerParameters.put("alphafrog.release-id", service.path("releaseId").asText());
            providerParameters.put("alphafrog.deployment-generation-id", plan.generationId());
            providerParameters.put("alphafrog.instance-id", plan.instanceId());
            providerParameters.put("zone", "beta");
            if (!"main-beta".equals(plan.trafficScopeId())) {
                providerParameters.put("dubbo.tag", plan.trafficScopeId());
            }
        }
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

    // simplified 注册时 Dubbo 只保留白名单参数 + 这些键；TagStateRouter 读实例的
    // dubbo.tag，其余是控制器注入、从实例 metadata 读的代际与流量信息。
    private static final String ROUTING_EXTRA_KEYS = "application,zone,dubbo.tag,"
            + "alphafrog.deployment-id,alphafrog.traffic-scope-id,alphafrog.release-id,"
            + "alphafrog.deployment-generation-id,alphafrog.instance-id";

    private void registryConfig(ObjectNode target, String server, String namespace, String group, String zone,
                                boolean register, boolean preferred) {
        target.put("address", "nacos://" + server + "?namespace=" + namespace + "&group=" + group + "&zone=" + zone);
        target.put("username", "${AF_CONFIG_NACOS_USERNAME:}");
        target.put("password", "${AF_CONFIG_NACOS_PASSWORD:}");
        target.put("register", register);
        target.put("preferred", preferred);
        target.put("use-as-config-center", false);
        target.put("use-as-metadata-center", false);
        // beta 注入的路由参数会把 provider 实例 metadata 顶过 Nacos 1024 字符上限，注册被拒、
        // 应用启动失败。simplified 让注册 URL 只保留 Dubbo 白名单参数 + extra-keys（丢掉
        // methods 这类大参数）；extra-keys 保住泳道路由（dubbo.tag）和代际治理要从实例
        // metadata 读的全部键。production 注册中心只订阅，带上无副作用。
        target.put("simplified", true);
        target.put("extra-keys", ROUTING_EXTRA_KEYS);
    }

    // 直接按部署单里的本机 Image ID 校验和启动：标签可以被挪到另一张镜像上，按
    // Image ID 定位则不受标签移动影响。repositoryDigest 只作为人类可读的镜像说明保留。
    private void verifyImage(String machineId, JsonNode service) {
        commands.run(docker(machineId, "image", "inspect", service.path("image").path("localImageId").asText(),
                "--format", "{{.Id}}"), Map.of(), Duration.ofSeconds(30));
    }

    private static boolean coordinatedAgentShutdown(JsonNode service) {
        return "agent-service".equals(service.path("serviceName").asText());
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

    private String requireTracesEndpoint() {
        java.net.URI endpoint = properties.getObservability().getTracesEndpoint();
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                    "OpenTelemetry traces endpoint is invalid: " + endpoint);
        }
        return endpoint.toString();
    }

    private void validateJavaAgent(BetaControllerProperties.ServiceTemplate template) {
        String options = template.getJavaToolOptions();
        if (options == null || options.indexOf('\0') >= 0 || options.contains("\n") || options.contains("\r")) {
            throw new ControllerException("OBSERVABILITY_CONFIG_INVALID", "Java tool options are invalid");
        }
        if (!template.isJavaAgentEnabled()) {
            if (!options.isBlank()) {
                throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                        "Java tool options require the Java Agent to be enabled");
            }
            return;
        }
        if (options.contains("-javaagent:")) {
            throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                    "Java tool options must not configure a second Java Agent");
        }
        requireSafeRegularFile(properties.getObservability().getJavaAgentJar(), false, "OpenTelemetry Java Agent");
    }

    private String javaToolOptions(BetaControllerProperties.ServiceTemplate template, String javaAgentContainerPath) {
        String configured = template.getJavaToolOptions().strip();
        String javaAgent = "-javaagent:" + javaAgentContainerPath;
        return configured.isEmpty() ? javaAgent : configured + ' ' + javaAgent;
    }

    private boolean usesControllerManagedMount(String volume) {
        String normalized = volume.replace(" ", "");
        return normalized.matches(".*:/app/logs(?::(?:ro|rw))?$")
                || normalized.matches(".*:/otel/javaagent\\.jar(?::(?:ro|rw))?$");
    }

    private Path prepareLogDirectory(String serviceName) {
        Path stateRoot = properties.getStateRoot().toAbsolutePath().normalize();
        Path dataRoot = stateRoot.resolve("data");
        Path logRoot = dataRoot.resolve("logs");
        Path serviceLog = logRoot.resolve(serviceName).normalize();
        if (!serviceLog.startsWith(logRoot)) {
            throw new ControllerException("OBSERVABILITY_CONFIG_INVALID", "Service log path escapes the state root");
        }
        try {
            for (Path path : List.of(stateRoot, dataRoot, logRoot, serviceLog)) {
                if (Files.isSymbolicLink(path)) {
                    throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                            "Service log path must not contain a symbolic link");
                }
            }
            Files.createDirectories(serviceLog);
            if (Files.isSymbolicLink(serviceLog)) {
                throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                        "Service log path must not be a symbolic link");
            }
            return serviceLog;
        } catch (IOException exception) {
            throw new ControllerException("OBSERVABILITY_CONFIG_INVALID",
                    "Unable to prepare the service log directory", exception);
        }
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
            throw new ControllerException("MACHINE_UNKNOWN", "Beta machine is not fully configured: " + id);
        if (value.getBindIp().isBlank() || value.getRoutableAddress().isBlank())
            throw new ControllerException("MACHINE_CONFIG_INVALID", "Beta machine addresses must not be blank: " + id);
        return value;
    }

    private void requireSafeRegularFile(Path path, boolean executable, String label) {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || (executable && !Files.isExecutable(path)))
            throw new ControllerException("SERVICE_CONFIG_INVALID", label + " is missing or unsafe: " + path);
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

    @Override
    public String containerName(CandidatePlan plan, String service) {
        return boundedName(("afb-" + plan.deploymentId() + '-' + service + '-' + plan.instanceId()).toLowerCase(), 128);
    }

    private Path composePath(String instanceId) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ControllerException("COMPOSE_PATH_UNSAFE", "Candidate instance identifier is invalid");
        Path target = composeRoot.resolve(instanceId + ".json").normalize();
        if (!target.startsWith(composeRoot))
            throw new ControllerException("COMPOSE_PATH_UNSAFE", "Compose state path escapes the state root");
        return target;
    }

    private String boundedName(String value, int maximumLength) {
        if (value.length() <= maximumLength) return value;
        String suffix = JsonSupport.hexSha256(value).substring(0, 12);
        return value.substring(0, maximumLength - suffix.length() - 1) + '-' + suffix;
    }
    private ContainerObservation missing(String name) { return new ContainerObservation("", name, "", 0, false, ContainerObservation.Health.MISSING); }
}
