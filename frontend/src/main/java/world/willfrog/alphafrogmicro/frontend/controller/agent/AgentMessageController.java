package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendResponse;

/** HTTP boundary for follow-up Agent messages and message history. */
@RestController
@RequiredArgsConstructor
public class AgentMessageController {

    private static final String AGENT_RUNS = "/api/agent/runs";

    private final AgentController safeHandlers;

    @PostMapping(AGENT_RUNS + "/{runId}/messages")
    public ResponseWrapper<AgentMessageSendResponse> sendMessage(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestBody AgentMessageSendRequest request) {
        return safeHandlers.sendMessage(authentication, runId, request);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/messages")
    public ResponseWrapper<AgentMessageListResponse> listMessages(
            Authentication authentication,
            @PathVariable("runId") String runId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(value = "include_initial", required = false, defaultValue = "true") boolean includeInitial) {
        return safeHandlers.listMessages(authentication, runId, limit, offset, includeInitial);
    }
}
