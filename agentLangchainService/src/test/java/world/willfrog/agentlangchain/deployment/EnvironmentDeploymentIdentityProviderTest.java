package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentDeploymentIdentityProviderTest {

    private static final String GENERATION = "gen-" + "1".repeat(64);

    @Test
    void readsTrustedEnvironmentValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AF_DEPLOYMENT_ID", "stable")
                .withProperty("AF_DEPLOYMENT_GENERATION_ID", GENERATION);

        DeploymentIdentity identity = new EnvironmentDeploymentIdentityProvider(environment).current();

        assertEquals("stable", identity.deploymentId());
        assertEquals(GENERATION, identity.generationId());
    }

    @Test
    void rejectsMissingOrLegacyGeneration() {
        MockEnvironment missing = new MockEnvironment().withProperty("AF_DEPLOYMENT_ID", "stable");
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentDeploymentIdentityProvider(missing).current());

        MockEnvironment legacy = new MockEnvironment()
                .withProperty("AF_DEPLOYMENT_ID", "stable")
                .withProperty("AF_DEPLOYMENT_GENERATION_ID", DeploymentIdentity.LEGACY_GENERATION_ID);
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentDeploymentIdentityProvider(legacy).current());
    }
}
