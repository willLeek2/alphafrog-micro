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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.registry.integration.DefaultServiceURLCustomizer;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ContainerRuntime;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

import static org.apache.dubbo.common.constants.CommonConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;

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
        properties.getObservability().setTracesEndpoint(URI.create("http://jaeger.internal:4318"));
        properties.setStateRoot(temporary.resolve("state"));
        Path health = temporary.resolve("tcp-healthcheck");
        Files.writeString(health, "#!/bin/sh\nexit 0\n");
        health.toFile().setExecutable(true, true);
        properties.setHealthcheckScript(health);
        Path javaAgent = temporary.resolve("opentelemetry-javaagent.jar");
        Files.writeString(javaAgent, "test java agent");
        properties.getObservability().setJavaAgentJar(javaAgent);
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
                            "drainGraceSeconds":65,"readinessTimeoutSeconds":120},
                 "registration":{"serviceName":"providers:com.alphafrog.AgentService::langchain",
                    "groupName":"alphafrog-beta","namespaceId":"public","clusterName":"DEFAULT",
                    "applicationName":"agent-langchain-service"}}]}
                """);
        service = manifest.path("services").path(0);
        plan = new ContainerRuntime.CandidatePlan("beta-main-001", "main-beta", "i-one",
                JsonSupport.deploymentGeneration(manifest), "A", 28080);
    }































    private final class FakeCommands extends CommandRunner {
        private final boolean startsPresent;
        private int inspectCalls;
        private int infoCalls;
        private final List<List<String>> commands = new ArrayList<>();
        private boolean failComposeValidation;
        private boolean returnOnlyObservedFields;
        private int failInfoCall;
        private boolean removed;
        private boolean networkExists;
        private boolean imageInspectFails;
        private String imageInspectId = "sha256:" + "b".repeat(64);

        private FakeCommands(boolean startsPresent) {
            this.startsPresent = startsPresent;
        }

        @Override
        public String run(List<String> arguments, Map<String, String> environment, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("info")) {
                infoCalls++;
                if (failInfoCall == infoCalls) throw new ControllerException("COMMAND_FAILED", "docker unavailable");
                return "27.0.0\n";
            }
            if (arguments.contains("network")) {
                if (arguments.contains("inspect")) {
                    if (!networkExists) throw new ControllerException("COMMAND_FAILED", "no such network");
                    return "[{\"Name\":\"alphafrog-beta\"}]\n";
                }
                networkExists = true;
                return "";
            }
            if (arguments.contains("inspect") && arguments.contains("image")) {
                if (imageInspectFails) throw new ControllerException("COMMAND_FAILED", "no such image");
                return imageInspectId + "\n";
            }
            if (arguments.contains("inspect")) {
                inspectCalls++;
                if (removed) throw new ControllerException("COMMAND_FAILED", "missing");
                if (!startsPresent && inspectCalls == 1)
                    throw new ControllerException("COMMAND_FAILED", "missing");
                return inspectJson();
            }
            if (arguments.contains("rm")) {
                removed = true;
                return "";
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
            boolean lane = !"main-beta".equals(plan.trafficScopeId());
            boolean processHalt = lane && service.path("runtime").path("allowToolJobProcessHalt").asBoolean(false);
            return """
                    [{"Id":"%s","Name":"/%s","Image":"%s",
                      "Config":{"Labels":{"alphafrog.deployment-id":"%s","alphafrog.traffic-scope-id":"%s",
                      "alphafrog.service-name":"%s","alphafrog.instance-id":"%s","alphafrog.release-id":"%s",
                      "alphafrog.deployment-generation-id":"%s","alphafrog.host-port":"%d"},
                      "Env":["AF_GIT_COMMIT=%s","AF_AGENT_TOOL_JOB_DURABLE_RECOVERY_ENABLED=%s",
                      "AF_AGENT_TOOL_JOB_FAULT_INJECTION_ENABLED=%s",
                      "AF_AGENT_TOOL_JOB_FAULT_INJECTION_ALLOW_PROCESS_HALT=%s"]},
                      "HostConfig":{"RestartPolicy":{"Name":"unless-stopped"}},"RestartCount":1,
                      "State":{"Running":true,"StartedAt":"2026-09-20T00:00:00Z","Health":{"Status":"healthy"}},
                      "NetworkSettings":{"Ports":{"%d/tcp":[{"HostIp":"127.0.0.1","HostPort":"%d"}]}}}]
                    """.formatted("d".repeat(64), name,
                    service.path("image").path("localImageId").asText(), plan.deploymentId(), plan.trafficScopeId(),
                    serviceName, "i-one", service.path("releaseId").asText(), plan.generationId(), plan.hostPort(),
                    manifest.path("gitCommit").asText(), lane, lane, processHalt,
                    service.path("runtime").path("containerPort").asInt(), plan.hostPort());
        }
    }

    private static boolean containsVolume(JsonNode volumes, String expected) {
        for (JsonNode volume : volumes) {
            if (expected.equals(volume.asText())) return true;
        }
        return false;
    }

    private static boolean containsTarget(JsonNode volumes, String target) {
        for (JsonNode volume : volumes) {
            if (volume.asText().contains(':' + target)) return true;
        }
        return false;
    }
}
