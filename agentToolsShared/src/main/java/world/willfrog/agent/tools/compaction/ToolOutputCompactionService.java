package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistration;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果进入模型前的截断/摘要/rawRef 绑定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolOutputCompactionService {

    private static final int DEFAULT_MAX_STRING_LENGTH = 2000;
    private static final int FALLBACK_TRUNCATE_CHARS = 4000;

    private final ToolSummaryService toolSummaryService;
    private final ToolOutputRefService toolOutputRefService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final ObjectMapper objectMapper;

    public CompactionResult compact(String toolName, String rawOutput, String todoGoal) {
        return compact(toolName, rawOutput, null, todoGoal);
    }

    public CompactionResult compactPrompt(String toolName, PromptText rawPrompt, String todoGoal) {
        if (rawPrompt == null) throw missingSource();
        return compact(toolName, rawPrompt.text(), rawPrompt, todoGoal);
    }

    private CompactionResult compact(String toolName, String rawOutput, PromptText rawPrompt, String todoGoal) {
        if (!CompactionEligibleTools.isEligible(toolName) || blank(rawOutput)) {
            return passThrough(rawOutput, rawPrompt);
        }
        if (rawOutput.length() <= effectiveMaxLength()) {
            return passThrough(rawOutput, rawPrompt);
        }
        String logicalId = firstNonBlank(AgentContext.getToolCallId(), toolName, "tool-output");
        String displayName = nvl(toolName);
        PersistentArtifactRegistration registration = rawPrompt == null
                ? toolOutputRefService.registerRawOutput(logicalId, displayName, rawOutput)
                : toolOutputRefService.registerRawPrompt(logicalId, displayName, rawPrompt);
        String rawRef = registration.getArtifactId();
        RawPayloadLocator locator = registration.getLocator();
        PromptText summaryPrompt = rawPrompt == null ? null
                : toolSummaryService.summarizePrompt(toolName, rawPrompt, todoGoal);
        if (rawPrompt != null && summaryPrompt == null) throw missingSource();
        String summary = rawPrompt == null ? toolSummaryService.summarize(toolName, rawOutput, todoGoal)
                : summaryPrompt.text();
        boolean truncated = summary.isBlank();
        if (truncated) {
            summary = "[summary 生成失败，以下为截断内容] " + truncate(rawOutput, FALLBACK_TRUNCATE_CHARS);
        }
        Map<String, Object> compactSearchItems = buildCompactSearchItems(toolName, rawOutput, effectiveMaxLength());
        SerializedOutput modelOutput = buildModelOutput(toolName, summary, rawRef, truncated, compactSearchItems);
        SerializedOutput cacheTemplate = buildModelOutput(toolName, summary, "", truncated, compactSearchItems);
        List<PromptVersionSource> actualSources = rawPrompt == null ? List.of()
                : truncated ? rawPrompt.sources() : summaryPrompt.sources();
        return CompactionResult.builder()
                .modelOutput(modelOutput.text())
                .modelPrompt(rawPrompt == null ? null : new PromptText(modelOutput.text(),
                        modelOutput.includesPayload() ? actualSources : List.of()))
                .cacheTemplate(cacheTemplate.text())
                .cacheTemplatePrompt(rawPrompt == null ? null : new PromptText(cacheTemplate.text(),
                        cacheTemplate.includesPayload() ? actualSources : List.of()))
                .rawLocator(locator)
                .compactionApplied(true)
                .observabilityOutput(rawOutput)
                .observabilityPrompt(rawPrompt)
                .build();
    }

    public PromptText rebindPromptForCacheHit(PromptText cacheTemplate, RawPayloadLocator locator) {
        if (cacheTemplate == null) throw missingSource();
        return new PromptText(rebindForCacheHit(cacheTemplate.text(), locator), cacheTemplate.sources());
    }

    public String rebindForCacheHit(String cacheTemplate, RawPayloadLocator locator) {
        if (blank(cacheTemplate) || locator == null) {
            return cacheTemplate;
        }
        String logicalId = firstNonBlank(AgentContext.getToolCallId(), "cache-hit", "tool-output");
        PersistentArtifactRegistration rebound =
                toolOutputRefService.rebindFromLocator(logicalId, "cache-hit", locator);
        String rawRef = rebound.getArtifactId();
        if (blank(rawRef)) {
            return cacheTemplate;
        }
        try {
            JsonNode root = objectMapper.readTree(cacheTemplate);
            if (!root.isObject()) {
                return injectRawRef(cacheTemplate, rawRef);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(root, Map.class);
            Object dataObj = map.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) dataMap);
                data.put("rawRef", rawRef);
                map.put("data", data);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return injectRawRef(cacheTemplate, rawRef);
        }
    }

    private CompactionResult passThrough(String rawOutput, PromptText rawPrompt) {
        return CompactionResult.builder()
                .modelOutput(rawOutput)
                .modelPrompt(rawPrompt)
                .cacheTemplate(rawOutput)
                .cacheTemplatePrompt(rawPrompt)
                .rawLocator(null)
                .compactionApplied(false)
                .observabilityOutput(rawOutput)
                .observabilityPrompt(rawPrompt)
                .build();
    }

    private SerializedOutput buildModelOutput(String toolName, String summary, String rawRef, boolean truncated,
                                    Map<String, Object> compactSearchItems) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", truncated ? "tool_result truncated" : "tool_result summary");
        if (!blank(rawRef)) {
            data.put("rawRef", rawRef);
        }
        data.put("summary", summary);
        if (compactSearchItems != null && !compactSearchItems.isEmpty()) {
            data.put("compactSearchItems", compactSearchItems);
        }
        if (truncated) {
            data.put("truncated", true);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", nvl(toolName));
        payload.put("data", data);
        try {
            return new SerializedOutput(objectMapper.writeValueAsString(payload), true);
        } catch (Exception e) {
            return new SerializedOutput(rawRef, false);
        }
    }

    private Map<String, Object> buildCompactSearchItems(String toolName, String rawOutput, int maxStringLength) {
        if (!isSearchTool(toolName) || blank(rawOutput)) {
            return Map.of();
        }
        List<Map<String, Object>> dailyItems = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rawOutput);
            collectHasDailyItems(root.path("data"), dailyItems);
        } catch (Exception e) {
            return Map.of();
        }
        if (dailyItems.isEmpty()) {
            return Map.of();
        }
        int budget = Math.max(128, maxStringLength / 4);
        List<Map<String, Object>> visibleItems = new ArrayList<>();
        int omitted = 0;
        for (Map<String, Object> item : dailyItems) {
            visibleItems.add(item);
            Map<String, Object> candidate = compactPayload(visibleItems, Math.max(0, dailyItems.size() - visibleItems.size()));
            if (jsonLength(candidate) > budget) {
                visibleItems.remove(visibleItems.size() - 1);
                omitted = Math.max(1, dailyItems.size() - visibleItems.size());
                break;
            }
        }
        return compactPayload(visibleItems, omitted);
    }

    private void collectHasDailyItems(JsonNode node, List<Map<String, Object>> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode hasDaily = node.get("has_daily");
            if (isHasDailyOne(hasDaily)) {
                String tsCode = text(node.get("ts_code"));
                String name = text(node.get("name"));
                if (!blank(tsCode) || !blank(name)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ts_code", tsCode);
                    item.put("name", name);
                    out.add(item);
                }
            }
            node.fields().forEachRemaining(entry -> collectHasDailyItems(entry.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectHasDailyItems(child, out));
        }
    }

    private Map<String, Object> compactPayload(List<Map<String, Object>> items, int omitted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filter", "has_daily=1");
        payload.put("fields", List.of("ts_code", "name"));
        payload.put("items", items);
        if (omitted > 0) {
            payload.put("omitted", omitted);
        }
        return payload;
    }

    private boolean isSearchTool(String toolName) {
        return switch (nvl(toolName)) {
            case "searchStock", "searchFund", "searchIndex", "searchAssetInfo" -> true;
            default -> false;
        };
    }

    private boolean isHasDailyOne(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isNumber()) {
            return node.asInt() == 1;
        }
        String text = node.asText("");
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private int jsonLength(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private String injectRawRef(String template, String rawRef) {
        if (blank(template) || blank(rawRef)) {
            return template;
        }
        return template.replace("\"rawRef\":\"\"", "\"rawRef\":\"" + escapeJson(rawRef) + "\"");
    }

    private int effectiveMaxLength() {
        return localConfigLoader.current()
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getResult)
                .map(AgentLlmProperties.ToolResult::getMaxStringLength)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_MAX_STRING_LENGTH);
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String escapeJson(String text) {
        return nvl(text).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private static ModelSendBlockedException missingSource() {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                "工具结果压缩缺少已分类的正文来源");
    }

    private record SerializedOutput(String text, boolean includesPayload) { }

    @Data
    @Builder
    public static class CompactionResult {
        private String modelOutput;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText modelPrompt;
        private String cacheTemplate;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText cacheTemplatePrompt;
        private RawPayloadLocator rawLocator;
        private boolean compactionApplied;
        private String observabilityOutput;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText observabilityPrompt;
    }
}
