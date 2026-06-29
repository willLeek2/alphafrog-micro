package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * reread tool：按 rawRef 重新读取完整或片段化工具结果。
 */
@Component
public class RereadToolHandler {

    private static final int DEFAULT_RESULT_MAX_STRING_LENGTH = 2000;
    private static final int DEFAULT_REREAD_MAX_LIMIT = 4000;

    private final ToolOutputRefService toolOutputRefService;
    private final ObjectMapper objectMapper;
    private final Optional<AgentLlmLocalConfigLoader> localConfigLoader;

    public RereadToolHandler(ToolOutputRefService toolOutputRefService, ObjectMapper objectMapper) {
        this(toolOutputRefService, objectMapper, Optional.empty());
    }

    @Autowired
    public RereadToolHandler(ToolOutputRefService toolOutputRefService,
                             ObjectMapper objectMapper,
                             Optional<AgentLlmLocalConfigLoader> localConfigLoader) {
        this.toolOutputRefService = toolOutputRefService;
        this.objectMapper = objectMapper;
        this.localConfigLoader = localConfigLoader == null ? Optional.empty() : localConfigLoader;
    }

    @Tool("""
        重新读取被压缩的大型工具输出。
        当某个工具结果包含 data.rawRef（形如 raw-ref:...）且 summary 不够用时，调用本工具读取原始内容。
        优先使用工具结果中可见的结构化 data 字段；对于 JSON/CSV、dataset 或 manifest 的筛选、聚合、排序、对比、回测等任务，优先使用 listMyData + executePython 确定性处理，不要用本工具反复分页浏览。
        仅在缺少必要字段时，用 keyword/offset/limit 做少量定向补读。rawRef 必须来自工具结果的 data.rawRef；不要把 rawRef 传给 loadDocument，loadDocument 只接收 ragSearch 返回的 oss_url。
        可选 keyword 用于在原始内容中搜索；offset/limit 用于分页读取。若不提供 keyword，limit 必须大于工具结果 rawRef 阈值的一半；否则请提供 keyword 做定向筛选。
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
        if (keyword == null || keyword.isBlank()) {
            int minUsefulLimit = effectiveResultMaxStringLength() / 2;
            int effectiveLimit = safeLimit <= 0 ? effectiveRereadMaxLimit() : safeLimit;
            if (effectiveLimit <= minUsefulLimit) {
                return fail("LIMIT_TOO_SMALL_WITHOUT_KEYWORD",
                        "rereadToolResult without keyword must use limit greater than half of tools.result.max-string-length, or provide keyword for filtered reread",
                        Map.of(
                                "requestedLimit", safeLimit,
                                "effectiveLimit", effectiveLimit,
                                "minimumExclusive", minUsefulLimit,
                                "hint", "Set keyword for grep-style filtering, or set limit > " + minUsefulLimit
                        ));
            }
        }
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

    private int effectiveResultMaxStringLength() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getResult)
                .map(AgentLlmProperties.ToolResult::getMaxStringLength)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_RESULT_MAX_STRING_LENGTH);
    }

    private int effectiveRereadMaxLimit() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getMaxLimit)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_REREAD_MAX_LIMIT);
    }
}
