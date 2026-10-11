package world.willfrog.beta.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.beta.tooljob.ToolJobTestControlService;

@RestController
@RequestMapping("/internal/beta/tool-job-tests/deployments/{deploymentId}/generations/{generationId}/agent-service")
@ConditionalOnProperty(prefix = "alphafrog.beta-controller.tool-job-test-control",
        name = "enabled", havingValue = "true")
public class ToolJobTestRestartApi {
    private final ToolJobTestControlService service;

    public ToolJobTestRestartApi(ToolJobTestControlService service) { this.service = service; }

    @PostMapping("/restart")
    public ToolJobTestControlService.RuntimeEvidence restart(@PathVariable String deploymentId,
                                                              @PathVariable String generationId) {
        return service.restartAgent(deploymentId, generationId);
    }
}
