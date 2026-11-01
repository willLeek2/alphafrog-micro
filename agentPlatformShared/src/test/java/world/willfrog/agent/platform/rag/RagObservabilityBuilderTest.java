package world.willfrog.agent.platform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagObservabilityBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagObservabilityBuilder builder = new RagObservabilityBuilder(objectMapper);




    private AgentRunObservabilityService.ToolTrace toolTrace(String traceId, String toolName) {
        AgentRunObservabilityService.ToolTrace trace = new AgentRunObservabilityService.ToolTrace();
        trace.setTraceId(traceId);
        trace.setToolName(toolName);
        trace.setSuccess(true);
        return trace;
    }

    private String toolDetail(Map<String, Object> params, String output) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "tool",
                    "traceId", "trace-1",
                    "params", params,
                    "output", output
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
