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

    @Test
    void processHaltArmRequiresTheStricterRuntimeTargetAndReturnsFilteredEvidence() {
        ToolJobTestTarget target = target();
        ToolJobTestStore.FaultRecord record = new ToolJobTestStore.FaultRecord("tj-one", "beta-lane-a",
                "lane-a", "gen-" + "1".repeat(64), "run-one", "AFTER_SANDBOX_ACCEPTED",
                "PROCESS_HALT", "ARMED", "2026-09-20T00:00:00Z", "2026-09-20T00:10:00Z",
                null, null, null, null);
        when(deployments.toolJobTestTarget(target.deploymentId(), target.generationId(), true)).thenReturn(target);
        when(store.armFault(target, "run-one", "AFTER_SANDBOX_ACCEPTED", "PROCESS_HALT", 600))
                .thenReturn(record);

        ToolJobTestControlService.FaultEvidence response = service.arm(target.deploymentId(),
                target.generationId(), "run-one", "AFTER_SANDBOX_ACCEPTED", "PROCESS_HALT", 600);

        assertEquals("tj-one", response.fault().scenarioId());
        assertTrue(response.runtime().processHaltEnabled());
        assertTrue(response.runtime().restartUnlessStopped());
        verify(deployments).toolJobTestTarget(target.deploymentId(), target.generationId(), true);
    }

    @Test
    void planInvalidationUsesTheNonHaltingLaneTargetAndPreservesExpectedVersions() {
        ToolJobTestTarget target = target();
        ToolJobTestStore.PlanInvalidation result = new ToolJobTestStore.PlanInvalidation(target.deploymentId(),
                target.trafficScopeId(), target.generationId(), "run-one", 3, 4, 7, "op-one");
        when(deployments.toolJobTestTarget(target.deploymentId(), target.generationId(), false)).thenReturn(target);
        when(store.invalidatePlan(target, "run-one", 3, 7, "op-one")).thenReturn(result);

        ToolJobTestStore.PlanInvalidation response = service.invalidatePlan(target.deploymentId(),
                target.generationId(), "run-one", 3, 7, "op-one");

        assertEquals(3, response.previousPlanGeneration());
        assertEquals(4, response.currentPlanGeneration());
        verify(store).invalidatePlan(target, "run-one", 3, 7, "op-one");
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
