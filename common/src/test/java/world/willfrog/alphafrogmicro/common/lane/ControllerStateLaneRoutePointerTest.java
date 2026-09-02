package world.willfrog.alphafrogmicro.common.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControllerStateLaneRoutePointerTest {

    private static final String OLD_GEN = "gen-" + "a".repeat(64);
    private static final String NEW_GEN = "gen-" + "b".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void current_shouldParseExactInstanceAndRereadFileEveryTime() throws Exception {
        Path file = tempDir.resolve("controller-state.json");
        Files.writeString(file, stateJson("instance-old", "release-1", OLD_GEN, 7, 28080), StandardCharsets.UTF_8);
        ControllerStateLaneRoutePointer pointer = new ControllerStateLaneRoutePointer(objectMapper, file);
        LaneCallRouter router = new LaneCallRouter(pointer);

        LaneCallBinding first = router.bindNewCall("main-beta", "agent-service");
        Files.writeString(file, stateJson("instance-new", "release-2", NEW_GEN, 8, 28081), StandardCharsets.UTF_8);
        LaneCallBinding second = router.bindNewCall("main-beta", "agent-service");

        assertThat(first.instanceId()).isEqualTo("instance-old");
        assertThat(first.endpoint().port()).isEqualTo(28080);
        assertThat(second.instanceId()).isEqualTo("instance-new");
        assertThat(second.deploymentGenerationId()).isEqualTo(NEW_GEN);
        assertThat(router.readCount()).isEqualTo(2);
    }

    @Test
    void current_shouldFailClosedWhenFileMissingOrBroken() throws Exception {
        Path file = tempDir.resolve("missing.json");
        ControllerStateLaneRoutePointer pointer = new ControllerStateLaneRoutePointer(objectMapper, file);
        LaneCallRouter router = new LaneCallRouter(pointer);
        assertThatThrownBy(() -> router.bindNewCall("main-beta", "agent-service"))
                .isInstanceOf(LaneRouteUnavailableException.class);

        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> router.bindNewCall("main-beta", "agent-service"))
                .isInstanceOf(LaneRouteUnavailableException.class);
    }

    @Test
    void current_shouldTreatNullDefaultInstanceAsUnavailable() throws Exception {
        Path file = tempDir.resolve("controller-state.json");
        Files.writeString(file, stateJson(null, null, null, 0, 28080), StandardCharsets.UTF_8);
        LaneCallRouter router = new LaneCallRouter(new ControllerStateLaneRoutePointer(objectMapper, file));
        assertThatThrownBy(() -> router.bindNewCall("main-beta", "agent-service"))
                .isInstanceOf(LaneRouteUnavailableException.class);
    }

    @Test
    void current_shouldRejectReleaseMismatchAsUncertainFacts() throws Exception {
        Path file = tempDir.resolve("controller-state.json");
        Files.writeString(
                file,
                """
                {
                  "deployments": [
                    {
                      "trafficScopeId": "main-beta",
                      "services": [
                        {
                          "serviceName": "agent-service",
                          "activeInstance": {
                            "instanceId": "instance-old",
                            "releaseId": "release-1",
                            "deploymentGenerationId": "%s",
                            "endpoint": {"address": "10.0.0.8", "port": 28080}
                          },
                          "candidateInstance": null,
                          "drainingInstance": null,
                          "route": {
                            "defaultInstanceId": "instance-old",
                            "defaultReleaseId": "release-other",
                            "defaultDeploymentGenerationId": "%s",
                            "routeVersion": 7
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted("gen-" + "a".repeat(64), "gen-" + "a".repeat(64)),
                StandardCharsets.UTF_8);
        LaneCallRouter router = new LaneCallRouter(new ControllerStateLaneRoutePointer(objectMapper, file));
        assertThatThrownBy(() -> router.bindNewCall("main-beta", "agent-service"))
                .isInstanceOf(LaneRouteFactsUncertainException.class)
                .hasMessage(LaneRouteFactsUncertainException.CODE);
    }

    private static String stateJson(
            String instanceId,
            String releaseId,
            String generationId,
            long routeVersion,
            int port) {
        String defaultInstance = instanceId == null ? "null" : quote(instanceId);
        String defaultRelease = releaseId == null ? "null" : quote(releaseId);
        String defaultGeneration = generationId == null ? "null" : quote(generationId);
        String active = instanceId == null
                ? "null"
                : """
                {
                  "instanceId": %s,
                  "releaseId": %s,
                  "deploymentGenerationId": %s,
                  "endpoint": {"address": "10.0.0.8", "port": %d},
                  "registration": {"serviceName": "com.alphafrog.AgentService:1.0@@providers"}
                }
                """.formatted(quote(instanceId), quote(releaseId), quote(generationId), port);
        return """
                {
                  "schemaVersion": 1,
                  "stateVersion": 1,
                  "updatedAt": "2026-09-01T00:00:00Z",
                  "deployments": [
                    {
                      "trafficScopeId": "main-beta",
                      "services": [
                        {
                          "serviceName": "agent-service",
                          "activeInstance": %s,
                          "candidateInstance": null,
                          "drainingInstance": null,
                          "route": {
                            "defaultInstanceId": %s,
                            "defaultReleaseId": %s,
                            "defaultDeploymentGenerationId": %s,
                            "routeVersion": %d,
                            "updatedAt": "2026-09-01T00:00:00Z"
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(active, defaultInstance, defaultRelease, defaultGeneration, routeVersion);
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
