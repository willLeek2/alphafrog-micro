package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
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
    private final Optional<RunRawRefStore> runRawRefStore;

    public RereadToolHandler(ToolOutputRefService toolOutputRefService, ObjectMapper objectMapper) {
        this(toolOutputRefService, objectMapper, Optional.empty(), Optional.empty());
    }

    public RereadToolHandler(ToolOutputRefService toolOutputRefService,
                             ObjectMapper objectMapper,
                             Optional<AgentLlmLocalConfigLoader> localConfigLoader) {
        this(toolOutputRefService, objectMapper, localConfigLoader, Optional.empty());
    }

    @Autowired
    public RereadToolHandler(ToolOutputRefService toolOutputRefService,
                             ObjectMapper objectMapper,
                             Optional<AgentLlmLocalConfigLoader> localConfigLoader,
                             Optional<RunRawRefStore> runRawRefStore) {
        this.toolOutputRefService = toolOutputRefService;
        this.objectMapper = objectMapper;
        this.localConfigLoader = localConfigLoader == null ? Optional.empty() : localConfigLoader;
        this.runRawRefStore = runRawRefStore == null ? Optional.empty() : runRawRefStore;
    }

    @Tool
    public String rereadToolResult(
            @P(value = "工具结果 data.rawRef 字段，形如 raw_ref_001 或 raw-ref:...", required = true) String rawRef,
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
            int minUsefulLimit = effectiveRangeMinLimitWithoutKeyword();
            int maxRangeLimit = effectiveRangeMaxLimit();
            int effectiveLimit = safeLimit <= 0 ? effectiveRereadMaxLimit() : safeLimit;
            if (effectiveLimit < minUsefulLimit) {
                return fail("LIMIT_TOO_SMALL_WITHOUT_KEYWORD",
                        "rereadToolResult without keyword must use configured minimum range limit, or provide keyword for filtered reread",
                        Map.of(
                                "requestedLimit", safeLimit,
                                "effectiveLimit", effectiveLimit,
                                "minimumInclusive", minUsefulLimit,
                                "hint", "Set keyword for grep-style filtering, or set limit >= " + minUsefulLimit
                        ));
            }
            if (effectiveLimit > maxRangeLimit) {
                return fail("LIMIT_TOO_LARGE_WITHOUT_KEYWORD",
                        "rereadToolResult without keyword must not exceed configured range max limit",
                        Map.of(
                                "requestedLimit", effectiveLimit,
                                "maximumInclusive", maxRangeLimit
                        ));
            }
        } else {
            int kwMax = effectiveKeywordMaxLimit();
            int effectiveLimit = safeLimit <= 0 ? kwMax : safeLimit;
            if (effectiveLimit > kwMax) {
                return fail("LIMIT_TOO_LARGE_WITH_KEYWORD",
                        "rereadToolResult keyword mode limit must not exceed configured keyword max limit",
                        Map.of(
                                "requestedLimit", safeLimit,
                                "maximumInclusive", kwMax
                        ));
            }
            safeLimit = effectiveLimit;
        }
        ToolOutputReadResult read = readRawRef(rawRef, safeOffset, safeLimit, keyword);
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

    private ToolOutputReadResult readRawRef(String rawRef, int offset, int limit, String keyword) {
        String runId = AgentContext.getRunId();
        if (isShortRawRef(rawRef) && runId != null && !runId.isBlank() && runRawRefStore.isPresent()) {
            // 严格归属校验：短格式读取必须携带当前 user 上下文（AgentContext.getUserId()）。
            // 映射层只能证明 shortId 属于该 run；内容放行还要 userId 与制品 meta 严格相等，
            // 空白或不匹配的 userId 会在 registry.readContentStrict 处 fail-closed 拒绝。
            return runRawRefStore.get().read(runId, AgentContext.getUserId(), rawRef, offset, limit, keyword);
        }
        return toolOutputRefService.read(rawRef, offset, limit, keyword);
    }

    private boolean isShortRawRef(String rawRef) {
        return rawRef != null && rawRef.matches("raw_ref_\\d{3}");
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

    private int effectiveKeywordMaxLimit() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getKeywordCharLimit)
                .filter(v -> v != null && v > 0)
                .orElse(effectiveRereadMaxLimit());
    }

    private int effectiveRangeMaxLimit() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getRangeMaxLimit)
                .filter(v -> v != null && v > 0)
                .orElse(effectiveRereadMaxLimit());
    }

    private int effectiveRangeMinLimitWithoutKeyword() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getRangeMinLimitWithoutKeyword)
                .filter(v -> v != null && v > 0)
                .orElse(effectiveResultMaxStringLength() / 2 + 1);
    }
}
