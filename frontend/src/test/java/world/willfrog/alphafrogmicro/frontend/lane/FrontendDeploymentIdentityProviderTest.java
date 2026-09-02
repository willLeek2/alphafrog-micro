package world.willfrog.alphafrogmicro.frontend.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

class FrontendDeploymentIdentityProviderTest {

    @AfterEach
    void clearRequestFacts() {
        LaneRequestContext.clear();
    }

    @Test
    void untaggedRequestUsesOnlyTheIdentityInjectedIntoTheFrontendInstance() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setLocalDeploymentId("stable");
        properties.setLocalDeploymentGenerationId("gen-" + "1".repeat(64));

        DeploymentIdentity identity = new FrontendDeploymentIdentityProvider(properties).current();

        assertEquals("stable", identity.deploymentId());
        assertEquals("gen-" + "1".repeat(64), identity.generationId());
    }

    @Test
    void taggedRequestUsesControllerFactsInsteadOfTheFrontendInstanceIdentity() {
        LaneEntryProperties properties = new LaneEntryProperties();
        properties.setLocalDeploymentId("stable");
        properties.setLocalDeploymentGenerationId("gen-" + "1".repeat(64));
        LaneRequestContext.set(new LaneRouteFacts(
                "lane-test",
                "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", "gen-" + "a".repeat(64)),
                LaneRouteFactsTestData.DUBBO_SERVICE_KEY,
                LaneRouteFactsTestData.REGISTRATION,
                LaneRouteFactsTestData.facts("instance-a", 28081, 7).callBinding(),
                11));

        DeploymentIdentity identity = new FrontendDeploymentIdentityProvider(properties).current();

        assertEquals("beta-main-001", identity.deploymentId());
        assertEquals("gen-" + "a".repeat(64), identity.generationId());
    }

    @Test
    void missingStableIdentityFailsInsteadOfInventingADeploymentGeneration() {
        LaneEntryProperties properties = new LaneEntryProperties();

        assertThrows(IllegalStateException.class,
                () -> new FrontendDeploymentIdentityProvider(properties).current());
    }
}
