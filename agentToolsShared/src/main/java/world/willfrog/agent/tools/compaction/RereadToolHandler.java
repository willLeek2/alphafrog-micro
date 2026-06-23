package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
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

    @Tool("""
        重新读取被压缩的大型工具输出。
        当某个工具结果包含 data.rawRef（形如 raw-ref:...）且 summary 不够用时，调用本工具读取原始内容。
        rawRef 必须来自工具结果的 data.rawRef；不要把 rawRef 传给 loadDocument，loadDocument 只接收 ragSearch 返回的 oss_url。
        可选 keyword 用于在原始内容中搜索；offset/limit 用于分页读取。
        """)
    public String rereadToolResult(
            @P(value = "工具结果 data.rawRef 字段，形如 raw-ref:...", required = true) String rawRef,
            @P(value = "可选关键词；非空时只返回匹配片段", required = false) String keyword,
            @P(value = "可选读取偏移，默认 0", required = false) Integer offset,
            @P(value = "可选读取长度，0 表示使用服务默认", required = false) Integer limit
    ) {
        return reread(rawRef, keyword, offset, limit);
    }

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
