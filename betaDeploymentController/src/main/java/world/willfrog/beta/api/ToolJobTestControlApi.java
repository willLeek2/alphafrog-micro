package world.willfrog.beta.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.beta.tooljob.ToolJobTestControlService;
import world.willfrog.beta.tooljob.ToolJobTestStore;

@RestController
@RequestMapping("/internal/beta/tool-job-tests/deployments/{deploymentId}/generations/{generationId}/runs/{runId}")
@ConditionalOnProperty(prefix = "alphafrog.beta-controller.tool-job-test-control",
        name = "enabled", havingValue = "true")
public class ToolJobTestControlApi {
    private final ToolJobTestControlService service;

    public ToolJobTestControlApi(ToolJobTestControlService service) { this.service = service; }

    @PostMapping("/faults")
    public ToolJobTestControlService.FaultEvidence arm(@PathVariable String deploymentId,
            @PathVariable String generationId, @PathVariable String runId, @RequestBody FaultRequest request) {
        return service.arm(deploymentId, generationId, runId, request.checkpoint(), request.action(),
                request.ttlSeconds());
    }

    @GetMapping("/faults/{scenarioId}")
    public ToolJobTestControlService.FaultEvidence read(@PathVariable String deploymentId,
            @PathVariable String generationId, @PathVariable String runId,
            @PathVariable String scenarioId) {
        return service.read(deploymentId, generationId, runId, scenarioId);
    }

    @PostMapping("/invalidate-plan-generation")
    public ToolJobTestStore.PlanInvalidation invalidatePlan(@PathVariable String deploymentId,
            @PathVariable String generationId, @PathVariable String runId,
            @RequestBody PlanInvalidationRequest request) {
        return service.invalidatePlan(deploymentId, generationId, runId, request.expectedPlanGeneration(),
                request.expectedRunControlVersion(), request.expectedOperationId());
    }

    public record FaultRequest(String checkpoint, String action, int ttlSeconds) { }
    public record PlanInvalidationRequest(int expectedPlanGeneration, long expectedRunControlVersion,
                                          String expectedOperationId) { }
}
