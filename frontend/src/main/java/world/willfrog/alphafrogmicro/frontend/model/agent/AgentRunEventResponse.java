package world.willfrog.alphafrogmicro.frontend.model.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/** schemaVersion=1 的 REST/SSE 共享 Agent event envelope。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunEventResponse(
        int schemaVersion,
        String type,
        Long id,
        String runId,
        int seq,
        String eventType,
        Map<String, Object> payload,
        String createdAt,
        long ts,
        boolean durable
) {

    /** SSE 使用显式 Map，确保可选 PostgreSQL id 在缺失时不进入 wire payload。 */
    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("schemaVersion", schemaVersion);
        wire.put("type", type);
        if (id != null) {
            wire.put("id", id);
        }
        wire.put("runId", runId);
        wire.put("seq", seq);
        wire.put("eventType", eventType);
        wire.put("payload", payload);
        wire.put("createdAt", createdAt);
        wire.put("ts", ts);
        wire.put("durable", durable);
        return wire;
    }
}
