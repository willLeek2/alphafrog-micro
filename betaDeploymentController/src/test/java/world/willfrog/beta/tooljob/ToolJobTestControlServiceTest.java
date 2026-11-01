package world.willfrog.beta.tooljob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.beta.core.BetaDeploymentService;
import world.willfrog.beta.core.BetaDeploymentService.ToolJobTestTarget;
import world.willfrog.beta.core.ContainerRuntime;

class ToolJobTestControlServiceTest {
    private BetaDeploymentService deployments;
    private ToolJobTestStore store;
    private ToolJobTestControlService service;

    @BeforeEach
    void setUp() {
        deployments = mock(BetaDeploymentService.class);
        store = mock(ToolJobTestStore.class);
        service = new ToolJobTestControlService(deployments, store);
    }



    private ToolJobTestTarget target() {
        String generation = "gen-" + "1".repeat(64);
        ContainerRuntime.ToolJobTestRuntime runtime = new ContainerRuntime.ToolJobTestRuntime(
                "container-one", true, true, "beta-lane-a", "lane-a", generation,
                "a".repeat(40), true, true, true, true, 1, "2026-09-20T00:00:00Z");
        return new ToolJobTestTarget("beta-lane-a", "lane-a", generation, "a".repeat(40),
                "beta-machine-1", "container-one", "container-one", true, runtime);
    }
}
