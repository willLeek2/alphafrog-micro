package world.willfrog.beta.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.beta.core.BetaDeploymentService;

@RestController
@RequestMapping("/internal/beta")
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class BetaControllerApi {
    private final BetaDeploymentService service;

    public BetaControllerApi(BetaDeploymentService service) { this.service = service; }

    @PutMapping("/deployments/{deploymentId}/manifest")
    public ObjectNode submit(@PathVariable String deploymentId, @RequestBody ObjectNode manifest) {
        if (!deploymentId.equals(manifest.path("deploymentId").asText()))
            throw new IllegalArgumentException("Path deployment identifier differs from the manifest");
        return service.submitManifest(manifest);
    }

    @DeleteMapping("/deployments/{deploymentId}")
    public ObjectNode delete(@PathVariable String deploymentId) { return service.requestDelete(deploymentId); }

    @PostMapping("/deployments/{deploymentId}/services/{serviceName}/retry")
    public ObjectNode retry(@PathVariable String deploymentId, @PathVariable String serviceName) {
        return service.retry(deploymentId, serviceName);
    }

    @PostMapping("/reconcile")
    public ObjectNode reconcile() { return service.reconcileOne(); }

    @GetMapping("/status/{trafficScopeId}/{serviceName}")
    public ObjectNode status(@PathVariable String trafficScopeId, @PathVariable String serviceName) {
        return service.status(trafficScopeId, serviceName);
    }

    @GetMapping("/deployments/{deploymentId}")
    public ObjectNode deployment(@PathVariable String deploymentId) { return service.statusByDeployment(deploymentId); }
}
