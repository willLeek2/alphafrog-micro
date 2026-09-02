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
        assertEquals("instance-a", facts.callBinding().instanceId());
        assertEquals("release-a", facts.callBinding().releaseId());
        assertEquals(9, facts.callBinding().routeVersion());
        assertEquals("10.0.0.8", facts.callBinding().endpoint().address());
        assertEquals(28081, facts.callBinding().endpoint().port());
        assertEquals(
                "providers:world.willfrog.alphafrogmicro.agent.idl.AgentDubboService::langchain",
                facts.registrationServiceName());
        assertEquals(
                "langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService",
                facts.dubboServiceKey().value());
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
    void rejectsPersistedDubboCallIdentityThatDoesNotMatchTheConfiguredIdentity() {
        String anotherGroup = validStatus().replace(
                "langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService",
                "experimental/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService");
        String anotherVersion = validStatus().replace(
                "langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService",
                "langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService:2.0");

        assertTrue(client.parseStatus(anotherGroup, "lane-test", "agent-langchain-service").isEmpty());
        assertTrue(client.parseStatus(anotherVersion, "lane-test", "agent-langchain-service").isEmpty());
    }

    @Test
    void rejectsARegistrationNameThatDoesNotBelongToThePersistedDubboIdentity() {
        String wrongRegistrationGroup = validStatus().replace(
                "providers:world.willfrog.alphafrogmicro.agent.idl.AgentDubboService::langchain",
                "providers:world.willfrog.alphafrogmicro.agent.idl.AgentDubboService::experimental");

        assertTrue(client.parseStatus(wrongRegistrationGroup, "lane-test", "agent-langchain-service").isEmpty());
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
                  "dubboServiceKey":"langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService",
                  "phase":"STABLE",
                  "deploymentId":"beta-main-001",
                  "trafficScopeId":"lane-test",
                  "stateVersion":17,
                  "route":{
                    "defaultInstanceId":"instance-a",
                    "defaultReleaseId":"release-a",
                    "defaultDeploymentGenerationId":"%s",
                    "routeVersion":9
                  },
                  "activeInstance":{
                    "instanceId":"instance-a",
                    "releaseId":"release-a",
                    "deploymentGenerationId":"%s",
                    "endpoint":{"address":"10.0.0.8","port":28081},
                    "registration":{
                      "serviceName":"providers:world.willfrog.alphafrogmicro.agent.idl.AgentDubboService::langchain",
                      "ip":"10.0.0.8",
                      "port":28081,
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
