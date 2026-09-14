package world.willfrog.alphafrogmicro.common.deployment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentIdentityTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void acceptsStableAndBetaDeploymentIdentities() {
        assertEquals("stable", new DeploymentIdentity("stable", GENERATION).deploymentId());
        assertEquals("beta-test", new DeploymentIdentity("beta-test", GENERATION).deploymentId());
    }

    @Test
    void rejectsInvalidOrLegacyRuntimeIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentIdentity("Beta_Test", GENERATION));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentIdentity("stable", DeploymentIdentity.LEGACY_GENERATION_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentIdentity("stable", "gen-short"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentIdentity(" stable ", GENERATION));
    }

    @Test
    void legacyGenerationIsOnlyValidAsPersistedHistory() {
        assertEquals(DeploymentIdentity.LEGACY_GENERATION_ID,
                DeploymentIdentity.requirePersistedGenerationId("legacy-stable"));
    }

    @Test
    void exactMatchRejectsFallbackToAnotherInstance() {
        DeploymentIdentity identity = new DeploymentIdentity("beta-test", GENERATION);
        assertDoesNotThrow(() -> identity.requireExactMatch("beta-test", GENERATION));
        assertThrows(DeploymentIdentityMismatchException.class,
                () -> identity.requireExactMatch("stable", GENERATION));
        assertThrows(DeploymentIdentityMismatchException.class,
                () -> identity.requireExactMatch("beta-test", "gen-" + "b".repeat(64)));
    }
}
