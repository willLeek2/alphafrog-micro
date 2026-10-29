package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.prompt.LightweightPromptPreparation;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchEvidenceJudgeService {

    public static final String STAGE = "search_evidence_judge";
    static final int JUDGE_MAX_TOKENS = 2048;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_SNIPPET_CHARS = 600;
    private static final int MAX_URL_CHARS = 400;

    private final ObjectMapper objectMapper;
    private final AgentAiServiceFactory aiServiceFactory;
    private final SearchEvidenceJudgeModelResolver judgeModelResolver;
    private final JudgeModelSelectorService judgeModelSelectorService;
    private final AgentRunObservabilityService observabilityService;
    @Autowired
    private LightweightPromptPreparation promptPreparation;
    @Autowired
    private AgentModelInputs modelInputs;

    public JudgeResult judge(String query,
                             List<String> requestedEntities,
                             List<Map<String, Object>> hits,
                             List<Map<String, Object>> citations) {
        List<Map<String, Object>> safeHits = hits == null ? List.of() : hits;
        List<Map<String, Object>> safeCitations = citations == null ? List.of() : citations;
        if (safeHits.isEmpty() && safeCitations.isEmpty()) {
            return new JudgeResult(true, "", List.of(), List.of());
        }

        SelectionAndModel selected = selectModel();
        if (selected == null) {
            return failOpen("NO_JUDGE_ROUTE", safeHits.size(), safeCitations.size());
        }

        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        long startedAtMillis = System.currentTimeMillis();
        try {
            AgentContext.clearStructuredOutputSpec();
            AgentContext.clearLastRecordedLlmTraceId();
            AgentContext.setLlmCallRequestMeta(buildJudgeRequestMeta(
                    selected, safeHits.size(), safeCitations.size()));
            AgentContext.setPhase(AgentRunObservabilityService.PHASE_SUMMARIZING);
            AgentContext.setStage(STAGE);
            List<PromptMessage> prepared = buildMessages(query, requestedEntities, safeHits, safeCitations);
            if (modelInputs == null) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "搜索证据判定缺少输入保存组件");
            }
            ChatResponse response;
            try (ModelInputScope ignored = modelInputs.open(selected.archiveEndpointName(), PromptMessage.toModelInputSources(prepared))) {
                response = selected.model().chat(prepared.stream().map(PromptMessage::message).toList());
            }
            long durationMs = System.currentTimeMillis() - startedAtMillis;
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            JsonNode root = parseJson(text);
            Validation validation = validate(root, safeHits.size(), safeCitations.size());
            if (!validation.valid()) {
                recordJudgeObservability(selected, durationMs, startedAtMillis, safeHits.size(), safeCitations.size(),
                        validation.error(), null, null);
                return failOpen(validation.error(), safeHits.size(), safeCitations.size());
            }
            TokenUsage tokenUsage = response.tokenUsage();
            recordJudgeObservability(selected, durationMs, startedAtMillis, safeHits.size(), safeCitations.size(),
                    null, tokenUsage, text);
            return new JudgeResult(
                    true,
                    "",
                    parseItems(root.path("hits"), safeHits.size(), ""),
                    parseItems(root.path("citations"), safeCitations.size(), "")
            );
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            long durationMs = System.currentTimeMillis() - startedAtMillis;
            log.warn("Search evidence judge failed: {}", e.getMessage());
            recordJudgeObservability(selected, durationMs, startedAtMillis, safeHits.size(), safeCitations.size(),
                    nvl(e.getMessage()), null, null);
            return failOpen("JUDGE_CALL_FAILED: " + nvl(e.getMessage()), safeHits.size(), safeCitations.size());
        } finally {
            restorePhaseAndStage(previousPhase, previousStage);
            if (previousSpec == null) AgentContext.clearStructuredOutputSpec();
            else AgentContext.setStructuredOutputSpec(previousSpec);
        }
    }

    private SelectionAndModel selectModel() {
        Optional<SearchEvidenceJudgeModelResolver.ResolvedStageModel> resolvedStage = judgeModelResolver.resolve();
        if (resolvedStage.isPresent()) {
            StageLlmConfig cfg = resolvedStage.get().config();
            try {
                AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(
                        cfg.getEndpointName(), cfg.getModelName());
                List<String> providerOrder = cfg.getProviderOrder() == null ? List.of() : cfg.getProviderOrder();
                ChatModel model = aiServiceFactory.buildArchivedChatModelWithProviderOrderAndTemperature(
                        resolved, providerOrder, 0.0D, JUDGE_MAX_TOKENS);
                return new SelectionAndModel(
                        cfg.getEndpointName(),
                        cfg.getModelName(),
                        resolvedStage.get().source(),
                        model, resolved.endpointName());
            } catch (Exception e) {
                ModelSendBlocks.rethrowIfBlocked(e);
                log.warn("Init search evidence judge from stage config failed: endpoint={}, model={}, err={}",
                        cfg.getEndpointName(), cfg.getModelName(), e.getMessage());
            }
        }
        List<JudgeModelSelectorService.Selection> deprecatedRoutes = judgeModelSelectorService.selectCandidates();
        if (!deprecatedRoutes.isEmpty()) {
            log.warn("Search evidence judge falling back to deprecated runtime.judge.routes; "
                    + "prefer stage_config.search_judge or inherit current phase model");
        }
        for (JudgeModelSelectorService.Selection candidate : deprecatedRoutes) {
            try {
                AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(
                        candidate.getEndpointName(), candidate.getModelName());
                ChatModel model = aiServiceFactory.buildArchivedChatModelWithProviderOrderAndTemperature(
                        resolved, List.of(), 0.0D, JUDGE_MAX_TOKENS);
                return new SelectionAndModel(
                        candidate.getEndpointName(),
                        candidate.getModelName(),
                        SearchEvidenceJudgeModelResolver.ModelSource.ROUTE_FALLBACK,
                        model, resolved.endpointName());
            } catch (Exception e) {
                ModelSendBlocks.rethrowIfBlocked(e);
                log.warn("Init search evidence judge model failed: endpoint={}, model={}, err={}",
                        candidate.getEndpointName(), candidate.getModelName(), e.getMessage());
            }
        }
        return null;
    }

    private void recordJudgeObservability(SelectionAndModel selected,
                                          long durationMs,
                                          long startedAtMillis,
                                          int hitCount,
                                          int citationCount,
                                          String errorMessage,
                                          TokenUsage tokenUsage,
                                          String responseText) {
        try {
            String runId = AgentContext.getRunId();
            if (runId == null || runId.isBlank()) {
                return;
            }
            Map<String, Object> judgeMeta = buildJudgeRequestMeta(selected, hitCount, citationCount);
            judgeMeta.put("judge_duration_ms", durationMs);
            String providerTraceId = AgentContext.getAndClearLastRecordedLlmTraceId();
            if (providerTraceId != null) {
                observabilityService.enrichLlmTrace(
                        runId, providerTraceId, errorMessage, responseText, judgeMeta);
                return;
            }
            Map<String, Object> requestSnapshot = new LinkedHashMap<>(judgeMeta);
            requestSnapshot.put("stage", STAGE);
            observabilityService.recordLlmCall(
                    runId,
                    AgentRunObservabilityService.PHASE_SUMMARIZING,
                    tokenUsage,
                    durationMs,
                    startedAtMillis,
                    startedAtMillis + durationMs,
                    selected.endpointName(),
                    selected.modelName(),
                    errorMessage,
                    requestSnapshot,
                    responseText);
        } catch (Exception e) {
            log.warn("Search evidence judge observability failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildJudgeRequestMeta(SelectionAndModel selected,
                                                      int hitCount,
                                                      int citationCount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stage", STAGE);
        meta.put("judge_model_source", selected.source().name().toLowerCase());
        meta.put("hit_count", hitCount);
        meta.put("citation_count", citationCount);
        meta.put("judge_model", selected.modelName());
        meta.put("judge_endpoint", selected.endpointName());
        return meta;
    }

    private List<PromptMessage> buildMessages(String query,
                                            List<String> requestedEntities,
                                            List<Map<String, Object>> hits,
                                            List<Map<String, Object>> citations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", nvl(query));
        payload.put("requested_entities", requestedEntities == null ? List.of() : requestedEntities);
        payload.put("hits", compactItems(hits, true));
        payload.put("citations", compactItems(citations, false));
        if (promptPreparation == null) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "搜索证据判定缺少原根政策读取组件");
        }
        return List.of(PromptMessage.system(promptPreparation.policy("searchEvidenceJudgeSystemPrompt")),
                PromptMessage.runtimeData(new UserMessage(writeJson(payload))));
    }

    private List<Map<String, Object>> compactItems(List<Map<String, Object>> items, boolean includeSnippet) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> source = items.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", i);
            row.put("title", truncate(stringValue(source.get("title")), MAX_TITLE_CHARS));
            row.put("url", truncate(stringValue(source.get("url")), MAX_URL_CHARS));
            if (includeSnippet) {
                row.put("snippet", truncate(stringValue(source.get("snippet")), MAX_SNIPPET_CHARS));
                row.put("source", truncate(stringValue(source.get("source")), MAX_TITLE_CHARS));
                row.put("published_date", truncate(stringValue(source.get("published_date")), MAX_TITLE_CHARS));
            }
            out.add(row);
        }
        return out;
    }

    private Validation validate(JsonNode root, int hitCount, int citationCount) {
        if (root == null || !root.isObject()) {
            return Validation.invalid("JUDGE_BAD_JSON");
        }
        if (!root.path("hits").isArray() || !root.path("citations").isArray()) {
            return Validation.invalid("JUDGE_MISSING_ARRAYS");
        }
        if (root.path("hits").size() != hitCount || root.path("citations").size() != citationCount) {
            return Validation.invalid("JUDGE_COUNT_MISMATCH");
        }
        if (!positionsValid(root.path("hits")) || !positionsValid(root.path("citations"))) {
            return Validation.invalid("JUDGE_POSITION_MISMATCH");
        }
        return Validation.ok();
    }

    private boolean positionsValid(JsonNode items) {
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject() || item.path("position").asInt(-1) != i) {
                return false;
            }
        }
        return true;
    }

    private List<ItemJudgement> parseItems(JsonNode items, int expectedSize, String error) {
        List<ItemJudgement> out = new ArrayList<>();
        for (int i = 0; i < expectedSize; i++) {
            JsonNode item = items.get(i);
            out.add(new ItemJudgement(
                    item.path("entityMatch").asBoolean(true),
                    stringList(item.path("matchedEntities")),
                    stringList(item.path("outOfScopeEntities")),
                    item.path("relevanceWarning").asText(""),
                    true,
                    nvl(error)
            ));
        }
        return out;
    }

    private JudgeResult failOpen(String error, int hitCount, int citationCount) {
        String message = "搜索证据相关性 judge 未完成: " + nvl(error);
        return new JudgeResult(
                false,
                nvl(error),
                defaultItems(hitCount, message, error),
                defaultItems(citationCount, message, error)
        );
    }

    private List<ItemJudgement> defaultItems(int count, String warning, String error) {
        List<ItemJudgement> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ItemJudgement(true, List.of(), List.of(), warning, false, nvl(error)));
        }
        return out;
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int firstLineEnd = trimmed.indexOf('\n', fenceStart);
            int contentStart = firstLineEnd < 0 ? fenceStart + 3 : firstLineEnd + 1;
            int fenceEnd = trimmed.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                trimmed = trimmed.substring(contentStart, fenceEnd).trim();
                if (trimmed.regionMatches(true, 0, "json", 0, 4)) {
                    trimmed = trimmed.substring(4).trim();
                }
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        String json = (start >= 0 && end > start) ? trimmed.substring(start, end + 1) : trimmed;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private void restorePhaseAndStage(String previousPhase, String previousStage) {
        if (previousPhase == null || previousPhase.isBlank()) {
            AgentContext.clearPhase();
        } else {
            AgentContext.setPhase(previousPhase);
        }
        if (previousStage == null || previousStage.isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(previousStage);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int maxChars) {
        String text = nvl(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public record JudgeResult(
            boolean relevanceJudged,
            String relevanceJudgeError,
            List<ItemJudgement> hits,
            List<ItemJudgement> citations
    ) {
    }

    public record ItemJudgement(
            boolean entityMatch,
            List<String> matchedEntities,
            List<String> outOfScopeEntities,
            String relevanceWarning,
            boolean relevanceJudged,
            String relevanceJudgeError
    ) {
    }

    private record SelectionAndModel(
            String endpointName,
            String modelName,
            SearchEvidenceJudgeModelResolver.ModelSource source,
            ChatModel model,
            String archiveEndpointName) {
    }

    private record Validation(boolean valid, String error) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation invalid(String error) {
            return new Validation(false, error == null ? "" : error);
        }
    }
}
