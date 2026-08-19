package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentArtifactPartsMetaResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentArtifactResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentSnapshotPartsMetaResponse;

import java.util.List;

/** snapshot 与 Artifact 载荷传输的 HTTP 边界。 */
@RestController
@RequiredArgsConstructor
public class AgentArtifactController {

    private static final String AGENT_RUNS = "/api/agent/runs";

    private final AgentController safeHandlers;

    @GetMapping(AGENT_RUNS + "/{runId}/snapshot/parts")
    public ResponseWrapper<AgentSnapshotPartsMetaResponse> snapshotParts(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.snapshotParts(authentication, runId, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/snapshot/parts/{partIndex}")
    public ResponseEntity<byte[]> snapshotPart(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("partIndex") int partIndex,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.snapshotPart(authentication, runId, partIndex, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts")
    public ResponseWrapper<List<AgentArtifactResponse>> artifacts(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "skip_lazy_registration", required = false, defaultValue = "false")
            boolean skipLazyRegistration) {
        return safeHandlers.artifacts(authentication, runId, skipLazyRegistration);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/parts")
    public ResponseWrapper<AgentArtifactPartsMetaResponse> artifactParts(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("artifactId") String artifactId,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.artifactParts(authentication, runId, artifactId, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/parts/{partIndex}")
    public ResponseEntity<byte[]> artifactPart(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("artifactId") String artifactId,
            @PathVariable("partIndex") int partIndex,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.artifactPart(authentication, runId, artifactId, partIndex, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadArtifact(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("artifactId") String artifactId) {
        return safeHandlers.downloadArtifact(authentication, runId, artifactId);
    }
}
