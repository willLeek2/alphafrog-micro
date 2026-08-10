package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventsPageResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceListResponse;

/** Agent 可观测性、trace 与 timeline 视图的 HTTP 边界。 */
@RestController
@RequiredArgsConstructor
public class AgentObservabilityController {

    private static final String AGENT_RUNS = "/api/agent/runs";

    private final AgentController safeHandlers;

    @GetMapping(AGENT_RUNS + "/{runId}/events")
    public ResponseWrapper<AgentRunEventsPageResponse> events(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "after_seq", required = false, defaultValue = "0") int afterSeq,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        return safeHandlers.events(authentication, runId, afterSeq, limit);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/timeline")
    public ResponseWrapper<TimelineResponse> timeline(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "after_seq", required = false, defaultValue = "0") int afterSeq,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        return safeHandlers.timeline(authentication, runId, afterSeq, limit);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/observability/full")
    public ResponseWrapper<Object> observabilityFull(Authentication authentication,
                                                      @PathVariable("runId") String runId) {
        return safeHandlers.observabilityFull(authentication, runId);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces")
    public ResponseWrapper<TraceListResponse> traces(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "type", required = false, defaultValue = "") String type,
            @RequestParam(value = "phase", required = false, defaultValue = "") String phase,
            @RequestParam(value = "after", required = false, defaultValue = "0") int after,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        return safeHandlers.traces(authentication, runId, type, phase, after, limit);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}")
    public ResponseWrapper<TraceDetailResponse> traceDetail(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("traceId") String traceId,
            @RequestParam(value = "full", required = false, defaultValue = "false") boolean full,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.traceDetail(authentication, runId, traceId, full, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/llm-calls/{llmCallId}/detail")
    public ResponseWrapper<AgentCallDetailResponse> llmCallDetail(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("llmCallId") String llmCallId,
            @RequestParam(value = "includeThinking", defaultValue = "false") boolean includeThinking) {
        return safeHandlers.llmCallDetail(authentication, runId, llmCallId, includeThinking);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/tool-calls/{toolCallId}/detail")
    public ResponseWrapper<AgentCallDetailResponse> toolCallDetail(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("toolCallId") String toolCallId) {
        return safeHandlers.toolCallDetail(authentication, runId, toolCallId);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}/full/parts")
    public ResponseWrapper<TraceDetailResponse.FullDetailParts> traceFullParts(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("traceId") String traceId,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.traceFullParts(authentication, runId, traceId, maxPartSize);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}/full/parts/{partIndex}")
    public ResponseEntity<byte[]> traceFullPart(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @PathVariable("traceId") String traceId,
            @PathVariable("partIndex") int partIndex,
            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        return safeHandlers.traceFullPart(authentication, runId, traceId, partIndex, maxPartSize);
    }
}
