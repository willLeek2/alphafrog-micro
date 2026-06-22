package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * reread tool：按 rawRef 重新读取完整或片段化工具结果。
 */
@Component
@RequiredArgsConstructor
public class RereadToolHandler {

    private final ToolOutputRefService toolOutputRefService;
    private final ObjectMapper objectMapper;

    public String reread(String rawRef, String keyword, Integer offset, Integer limit) {
        if (rawRef == null || rawRef.isBlank()) {
            return fail("INVALID_ARGUMENT", "rawRef is required", Map.of());
        }
        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        int safeLimit = limit == null ? 0 : Math.max(0, limit);
        ToolOutputReadResult read = toolOutputRefService.read(rawRef, safeOffset, safeLimit, keyword);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rawRef", rawRef);
        data.put("content", read.getContent());
        data.put("hasMore", read.isHasMore());
        data.put("nextOffset", read.getNextOffset());
        data.put("totalLength", read.getTotalLength());
        if (keyword != null && !keyword.isBlank()) {
            data.put("keyword", keyword);
        }
        return ok(data);
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "rereadToolResult");
        payload.put("data", data);
        return writeJson(payload);
    }

    private String fail(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "rereadToolResult");
        payload.put("data", Map.of());
        payload.put("error", Map.of(
                "code", code,
                "message", message,
                "details", details == null ? Map.of() : details
        ));
        return writeJson(payload);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"rereadToolResult\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"serialize failed\"}}";
        }
    }
}
