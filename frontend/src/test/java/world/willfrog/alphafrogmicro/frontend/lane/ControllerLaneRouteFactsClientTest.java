package world.willfrog.alphafrogmicro.frontend.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ControllerLaneRouteFactsClientTest {

    private final ControllerLaneRouteFactsClient client =
            new ControllerLaneRouteFactsClient(new LaneEntryProperties(), new ObjectMapper());

    @Test
    void acceptsOnlyRouteThatExactlyMatchesHealthyActiveInstance() {
        var facts = client.parseStatus(validStatus(), "lane-test", "agent-langchain-service").orElseThrow();

        assertEquals("lane-test", facts.trafficScopeId());
        assertEquals("beta-main-001", facts.deploymentIdentity().deploymentId());
        assertEquals("gen-" + "a".repeat(64), facts.deploymentIdentity().generationId());
        assertEquals(17, facts.stateVersion());
    }

    @Test
    void rejectsMismatchedGenerationAndFailedOrDisabledFacts() {
        String mismatched = validStatus().replace(
                "\"defaultDeploymentGenerationId\":\"gen-" + "a".repeat(64) + "\"",
                "\"defaultDeploymentGenerationId\":\"gen-" + "b".repeat(64) + "\"");
        String failed = validStatus().replace("\"phase\":\"STABLE\"", "\"phase\":\"FAILED\"");
        String disabled = validStatus().replace("\"enabled\":true", "\"enabled\":false");

        assertTrue(client.parseStatus(mismatched, "lane-test", "agent-langchain-service").isEmpty());
        assertTrue(client.parseStatus(failed, "lane-test", "agent-langchain-service").isEmpty());
        assertTrue(client.parseStatus(disabled, "lane-test", "agent-langchain-service").isEmpty());
    }

    @Test
    void treatsControllerNotFoundConflictAsAuthoritativeEmptyButKeepsOtherConflictsTransient() {
        assertTrue(client.isAuthoritativeNotFound(404, ""));
        assertTrue(client.isAuthoritativeNotFound(409,
                "{\"code\":\"STATUS_NOT_FOUND\",\"message\":\"missing\"}"));
        assertTrue(!client.isAuthoritativeNotFound(409,
                "{\"code\":\"ROUTE_READBACK_FAILED\",\"message\":\"temporary\"}"));
    }

    private static String validStatus() {
        String generation = "gen-" + "a".repeat(64);
        return """
                {
                  "serviceName":"agent-langchain-service",
                  "phase":"STABLE",
                  "deploymentId":"beta-main-001",
                  "trafficScopeId":"lane-test",
                  "stateVersion":17,
                  "route":{
                    "defaultInstanceId":"instance-a",
                    "defaultReleaseId":"release-a",
                    "defaultDeploymentGenerationId":"%s"
                  },
                  "activeInstance":{
                    "instanceId":"instance-a",
                    "releaseId":"release-a",
                    "deploymentGenerationId":"%s",
                    "registration":{
                      "enabled":true,
                      "healthy":true,
                      "weight":1,
                      "metadata":{
                        "alphafrog.traffic-scope-id":"lane-test",
                        "alphafrog.instance-id":"instance-a",
                        "alphafrog.release-id":"release-a",
                        "alphafrog.deployment-generation-id":"%s"
                      }
                    }
                  }
                }
                """.formatted(generation, generation, generation);
    }
}
