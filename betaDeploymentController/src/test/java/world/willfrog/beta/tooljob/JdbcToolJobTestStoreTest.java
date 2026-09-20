package world.willfrog.beta.tooljob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import world.willfrog.beta.core.BetaDeploymentService.ToolJobTestTarget;
import world.willfrog.beta.core.ContainerRuntime;

class JdbcToolJobTestStoreTest {

    @Test
    void faultArmLockIsSharedByEveryRunInTheSameDeploymentGeneration() {
        ToolJobTestTarget first = target("beta-lane-a", "gen-a");
        ToolJobTestTarget second = target("beta-lane-a", "gen-a");

        assertEquals(JdbcToolJobTestStore.faultArmLockIdentity(first),
                JdbcToolJobTestStore.faultArmLockIdentity(second));
    }

    @Test
    void faultArmLockChangesWhenTheDeploymentGenerationChanges() {
        ToolJobTestTarget first = target("beta-lane-a", "gen-a");
        ToolJobTestTarget second = target("beta-lane-a", "gen-b");

        assertNotEquals(JdbcToolJobTestStore.faultArmLockIdentity(first),
                JdbcToolJobTestStore.faultArmLockIdentity(second));
    }

    private ToolJobTestTarget target(String deploymentId, String generationId) {
        ContainerRuntime.ToolJobTestRuntime runtime = new ContainerRuntime.ToolJobTestRuntime(
                "container-one", true, true, deploymentId, "lane-a", generationId,
                "a".repeat(40), true, true, true, true, 1, "2026-09-20T00:00:00Z");
        return new ToolJobTestTarget(deploymentId, "lane-a", generationId, "a".repeat(40),
                "beta-machine-1", "container-one", "container-one", true, runtime);
    }
}
