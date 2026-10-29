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
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.docs.DocumentToolPromptPreparation;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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
    @Autowired
    private DocumentToolPromptPreparation promptPreparation;

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
        return rereadToolResultPrompt(rawRef, keyword, offset, limit).text();
    }

    public String reread(String rawRef, String keyword, Integer offset, Integer limit) {
        return rereadToolResultPrompt(rawRef, keyword, offset, limit).text();
    }

    public PromptText rereadToolResultPrompt(String rawRef, String keyword, Integer offset, Integer limit) {
        if (rawRef == null || rawRef.isBlank()) {
            return fail("INVALID_ARGUMENT", "rawRef is required", Map.of(), List.of());
        }
        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        int safeLimit = limit == null ? 0 : Math.max(0, limit);
        if (keyword == null || keyword.isBlank()) {
            int minUsefulLimit = effectiveRangeMinLimitWithoutKeyword();
            int maxRangeLimit = effectiveRangeMaxLimit();
            int effectiveLimit = safeLimit <= 0 ? effectiveRereadMaxLimit() : safeLimit;
            if (effectiveLimit < minUsefulLimit) {
                PromptText message = policy("rereadRangeTooSmallMessage", Map.of());
                PromptText hint = policy("rereadRangeTooSmallHint", Map.of("minimumInclusive", String.valueOf(minUsefulLimit)));
                return fail("LIMIT_TOO_SMALL_WITHOUT_KEYWORD", message.text(),
                        Map.of("requestedLimit", safeLimit, "effectiveLimit", effectiveLimit,
                                "minimumInclusive", minUsefulLimit, "hint", hint.text()),
                        PromptText.concat(message, hint).sources());
            }
            if (effectiveLimit > maxRangeLimit) {
                PromptText message = policy("rereadRangeTooLargeMessage", Map.of());
                return fail("LIMIT_TOO_LARGE_WITHOUT_KEYWORD", message.text(),
                        Map.of("requestedLimit", effectiveLimit, "maximumInclusive", maxRangeLimit), message.sources());
            }
        } else {
            int kwMax = effectiveKeywordMaxLimit();
            int effectiveLimit = safeLimit <= 0 ? kwMax : safeLimit;
            if (effectiveLimit > kwMax) {
                PromptText message = policy("rereadKeywordTooLargeMessage", Map.of());
                return fail("LIMIT_TOO_LARGE_WITH_KEYWORD", message.text(),
                        Map.of("requestedLimit", safeLimit, "maximumInclusive", kwMax), message.sources());
            }
            safeLimit = effectiveLimit;
        }
        ToolOutputReadResult read = readRawRef(rawRef, safeOffset, safeLimit, keyword);
        if (read == null || read.getContentPrompt() == null
                || !Objects.equals(read.getContent(), read.getContentPrompt().text())) {
            throw DocumentToolPromptPreparation.missing("工具旧输出缺少与本次片段一致的原指令来源");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rawRef", rawRef);
        data.put("content", read.getContent());
        data.put("hasMore", read.isHasMore());
        data.put("nextOffset", read.getNextOffset());
        data.put("totalLength", read.getTotalLength());
        if (keyword != null && !keyword.isBlank()) {
            data.put("keyword", keyword);
        }
        return ok(data, read.getContentPrompt().sources());
    }

    private ToolOutputReadResult readRawRef(String rawRef, int offset, int limit, String keyword) {
        String runId = AgentContext.getRunId();
        if (isShortRawRef(rawRef) && runId != null && !runId.isBlank() && runRawRefStore.isPresent()) {
            // 严格归属校验：短格式读取必须携带当前 user 上下文（AgentContext.getUserId()）。
            // 映射层只能证明 shortId 属于该 run；内容放行还要 userId 与制品 meta 严格相等，
            // 空白或不匹配的 userId 会在 registry.readContentStrict 处 fail-closed 拒绝。
            return runRawRefStore.get().read(runId, AgentContext.getUserId(), rawRef, offset, limit, keyword);
        }
        return toolOutputRefService.read(runId, AgentContext.getUserId(), rawRef, offset, limit, keyword);
    }

    private boolean isShortRawRef(String rawRef) {
        return rawRef != null && rawRef.matches("raw_ref_\\d{3}");
    }

    private PromptText policy(String fragment, Map<String, String> values) {
        if (promptPreparation == null) {
            throw DocumentToolPromptPreparation.missing("工具重读建议缺少原根正文读取组件");
        }
        return promptPreparation.policy(fragment, values);
    }

    private PromptText ok(Map<String, Object> data, List<PromptVersionSource> sources) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "rereadToolResult");
        payload.put("data", data);
        return DocumentToolPromptPreparation.envelope(objectMapper, payload, sources);
    }

    private PromptText fail(String code, String message, Map<String, Object> details, List<PromptVersionSource> sources) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        err.put("details", details == null ? Map.of() : details);
        ToolFailureCategory.putCategoryIfResolved(err, code, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "rereadToolResult");
        payload.put("data", Map.of());
        payload.put("error", err);
        return DocumentToolPromptPreparation.envelope(objectMapper, payload, sources);
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
