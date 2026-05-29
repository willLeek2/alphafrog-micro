package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads per-call detail blobs from Redis. Key layout must match {@code AgentRunStateStore}.
 */
@Component
@RequiredArgsConstructor
public class AgentCallDetailBlobReader {

    private static final String PREFIX = "agent:run:";
    private static final String DETAIL_LLM = ":detail:llm:";
    private static final String DETAIL_TOOL = ":detail:tool:";

    private final StringRedisTemplate redisTemplate;

    public Optional<String> loadLlmCallDetail(String runId, String llmCallId) {
        return load(runId, llmCallId, DETAIL_LLM);
    }

    public Optional<String> loadToolCallDetail(String runId, String toolCallId) {
        return load(runId, toolCallId, DETAIL_TOOL);
    }

    private Optional<String> load(String runId, String callId, String detailSegment) {
        if (runId == null || runId.isBlank() || callId == null || callId.isBlank()) {
            return Optional.empty();
        }
        String key = PREFIX + runId + detailSegment + callId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(json);
    }
}
