package world.willfrog.agent.platform.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Slf4j
public class RagObservabilityBuilder {

    private static final String RAG_OBSERVABILITY_VERSION = "v1";
    private static final String CITATION_MARKER_VERSION = "xml_rag_cite_v1";
    private static final Set<String> RAG_TOOLS = Set.of("ragSearch", "loadDocumentSection", "loadDocument");
    private static final Set<String> REREAD_TOOLS = Set.of("rereadToolResult");

    private final ObjectMapper objectMapper;
    private final RagCitationParser citationParser;

    public RagObservabilityBuilder(ObjectMapper objectMapper) {
        this(objectMapper, new RagCitationParser());
    }

    RagObservabilityBuilder(ObjectMapper objectMapper, RagCitationParser citationParser) {
        this.objectMapper = objectMapper;
        this.citationParser = citationParser;
    }

    public Map<String, Object> build(String runId,
                                     String finalAnswerText,
                                     List<AgentRunObservabilityService.ToolTrace> toolTraces,
                                     Function<String, Optional<String>> toolDetailLoader) {
        List<Map<String, Object>> ragToolCalls = new ArrayList<>();
        List<Map<String, Object>> rereadToolCalls = new ArrayList<>();
        List<String> sourceIncomplete = new ArrayList<>();
        LinkedHashSet<String> visibleRefRegistry = new LinkedHashSet<>();
        LinkedHashSet<String> rereadModes = new LinkedHashSet<>();
        Aggregate aggregate = new Aggregate();

        if (toolTraces != null) {
            for (AgentRunObservabilityService.ToolTrace trace : toolTraces) {
                if (trace == null || trace.getToolName() == null) {
                    continue;
                }
                String toolName = trace.getToolName();
                if (!RAG_TOOLS.contains(toolName) && !REREAD_TOOLS.contains(toolName)) {
                    continue;
                }
                Optional<ToolDetail> detail = loadToolDetail(runId, trace, toolDetailLoader, sourceIncomplete);
                if (detail.isEmpty()) {
                    continue;
                }
                if (RAG_TOOLS.contains(toolName)) {
                    collectRagToolCall(trace, detail.get(), visibleRefRegistry, aggregate, ragToolCalls);
                } else if (REREAD_TOOLS.contains(toolName)) {
                    collectRereadToolCall(trace, detail.get(), rereadModes, rereadToolCalls);
                }
            }
        }

        RagCitationParseResult citationResult = citationParser.parse(finalAnswerText, visibleRefRegistry);
        boolean hasMarkerSignal = !citationResult.citedRefs().isEmpty()
                || !citationResult.invalidCitedRefs().isEmpty()
                || citationResult.invalidCitationsIgnored() > 0;
        if (ragToolCalls.isEmpty() && rereadToolCalls.isEmpty() && sourceIncomplete.isEmpty() && !hasMarkerSignal) {
            return Map.of();
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("run_id", nvl(runId));
        root.put("rag_observability_version", RAG_OBSERVABILITY_VERSION);
        root.put("citation_marker_version", CITATION_MARKER_VERSION);
        Map<String, Object> aggregateMap = new LinkedHashMap<>();
        aggregateMap.put("retrieved_count", aggregate.retrievedCount);
        aggregateMap.put("visible_count", aggregate.visibleCount);
        aggregateMap.put("omitted_count", aggregate.omittedCount);
        aggregateMap.put("visible_chars", aggregate.visibleChars);
        aggregateMap.put("budget_hit", aggregate.budgetHit);
        aggregateMap.put("raw_ref_created", aggregate.rawRefCreated);
        aggregateMap.put("reread_called", !rereadToolCalls.isEmpty());
        aggregateMap.put("reread_modes", List.copyOf(rereadModes));
        aggregateMap.put("final_answer_cited_refs", citationResult.citedRefs());
        aggregateMap.put("invalid_cited_refs", citationResult.invalidCitedRefs());
        aggregateMap.put("invalid_citations_ignored", citationResult.invalidCitationsIgnored());
        aggregateMap.put("visible_ref_registry", List.copyOf(visibleRefRegistry));
        root.put("aggregate", aggregateMap);
        root.put("rag_tool_calls", ragToolCalls);
        root.put("reread_tool_calls", rereadToolCalls);
        if (!sourceIncomplete.isEmpty()) {
            root.put("source_incomplete", true);
            root.put("source_incomplete_reasons", sourceIncomplete);
        }
        return root;
    }

    private Optional<ToolDetail> loadToolDetail(String runId,
                                                AgentRunObservabilityService.ToolTrace trace,
                                                Function<String, Optional<String>> toolDetailLoader,
                                                List<String> sourceIncomplete) {
        if (trace.getTraceId() == null || trace.getTraceId().isBlank() || toolDetailLoader == null) {
            sourceIncomplete.add(reason(trace, "missing_trace_id_or_loader"));
            return Optional.empty();
        }
        Optional<String> detailJson;
        try {
            detailJson = toolDetailLoader.apply(trace.getTraceId());
        } catch (Exception e) {
            sourceIncomplete.add(reason(trace, "detail_load_failed"));
            log.debug("Failed to load RAG tool detail: runId={}, traceId={}, error={}",
                    runId, trace.getTraceId(), e.getMessage());
            return Optional.empty();
        }
        if (detailJson.isEmpty() || detailJson.get().isBlank()) {
            sourceIncomplete.add(reason(trace, "detail_missing_or_expired"));
            return Optional.empty();
        }
        try {
            JsonNode detailRoot = objectMapper.readTree(detailJson.get());
            JsonNode output = parseOutput(detailRoot.path("output"));
            Map<String, Object> params = mapValue(detailRoot.path("params"));
            return Optional.of(new ToolDetail(output, params));
        } catch (Exception e) {
            sourceIncomplete.add(reason(trace, "detail_parse_failed"));
            log.debug("Failed to parse RAG tool detail: runId={}, traceId={}, error={}",
                    runId, trace.getTraceId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void collectRagToolCall(AgentRunObservabilityService.ToolTrace trace,
                                    ToolDetail detail,
                                    LinkedHashSet<String> visibleRefRegistry,
                                    Aggregate aggregate,
                                    List<Map<String, Object>> ragToolCalls) {
        JsonNode output = detail.output();
        JsonNode data = output.path("data");
        JsonNode summary = data.path("summary");
        List<Map<String, Object>> topRefs = convertTopRefs(data.path("top_refs"), visibleRefRegistry);
        int omittedCount = intValue(summary, "omitted_count", intValue(data, "omitted_count", 0));
        int visibleChars = intValue(summary, "visible_chars", 0);
        int retrievedCount = intValue(summary, "retrieved_count", intValue(summary, "hit_count", topRefs.size() + omittedCount));
        boolean budgetHit = boolValue(summary, "budget_hit", boolValue(summary, "budgetHit", false));
        String rawRef = textValue(data.path("rawRef"));

        aggregate.retrievedCount += Math.max(0, retrievedCount);
        aggregate.visibleCount += topRefs.size();
        aggregate.omittedCount += Math.max(0, omittedCount);
        aggregate.visibleChars += Math.max(0, visibleChars);
        aggregate.budgetHit = aggregate.budgetHit || budgetHit;
        aggregate.rawRefCreated = aggregate.rawRefCreated || !rawRef.isBlank();

        Map<String, Object> call = baseCall(trace);
        call.put("query", asString(detail.params().get("query")));
        call.put("retrieved_count", Math.max(0, retrievedCount));
        call.put("visible_count", topRefs.size());
        call.put("omitted_count", Math.max(0, omittedCount));
        call.put("visible_chars", Math.max(0, visibleChars));
        call.put("budget_hit", budgetHit);
        call.put("rawRef", rawRef);
        call.put("top_refs", topRefs);
        ragToolCalls.add(call);
    }

    private void collectRereadToolCall(AgentRunObservabilityService.ToolTrace trace,
                                       ToolDetail detail,
                                       LinkedHashSet<String> rereadModes,
                                       List<Map<String, Object>> rereadToolCalls) {
        JsonNode data = detail.output().path("data");
        Map<String, Object> params = detail.params();
        String keyword = firstNonBlank(asString(params.get("keyword")), textValue(data.path("keyword")));
        String mode = keyword.isBlank() ? "range" : "keyword";
        rereadModes.add(mode);
        String content = textValue(data.path("content"));
        Map<String, Object> call = baseCall(trace);
        call.put("mode", mode);
        call.put("rawRef", firstNonBlank(asString(params.get("rawRef")), textValue(data.path("rawRef"))));
        call.put("keyword", keyword);
        call.put("keyword_present", !keyword.isBlank());
        call.put("offset", firstNonNull(params.get("offset"), nodeNumber(data.path("offset"))));
        call.put("limit", firstNonNull(params.get("limit"), nodeNumber(data.path("limit"))));
        call.put("returned_chars", content.length());
        call.put("hasMore", boolValue(data, "hasMore", false));
        call.put("nextOffset", nodeNumber(data.path("nextOffset")));
        rereadToolCalls.add(call);
    }

    private List<Map<String, Object>> convertTopRefs(JsonNode topRefsNode, LinkedHashSet<String> visibleRefRegistry) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (!topRefsNode.isArray()) {
            return refs;
        }
        for (JsonNode refNode : topRefsNode) {
            Map<String, Object> ref = mapValue(refNode);
            String refId = asString(ref.get("ref_id"));
            if (!refId.isBlank()) {
                visibleRefRegistry.add(refId);
            }
            refs.add(ref);
        }
        return refs;
    }

    private Map<String, Object> baseCall(AgentRunObservabilityService.ToolTrace trace) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool_call_id", nvl(trace.getTraceId()));
        call.put("tool", nvl(trace.getToolName()));
        call.put("success", trace.isSuccess());
        return call;
    }

    private JsonNode parseOutput(JsonNode outputNode) throws Exception {
        if (outputNode == null || outputNode.isMissingNode() || outputNode.isNull()) {
            return objectMapper.nullNode();
        }
        if (outputNode.isTextual()) {
            String text = outputNode.asText();
            if (text.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(text);
        }
        return outputNode;
    }

    private Map<String, Object> mapValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private static boolean boolValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static Object nodeNumber(JsonNode node) {
        return node != null && node.isNumber() ? node.numberValue() : null;
    }

    private static Object firstNonNull(Object left, Object right) {
        return left != null ? left : right;
    }

    private static String firstNonBlank(String left, String right) {
        return !left.isBlank() ? left : right;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String reason(AgentRunObservabilityService.ToolTrace trace, String reason) {
        return nvl(trace.getToolName()) + ":" + nvl(trace.getTraceId()) + ":" + reason;
    }

    private static final class Aggregate {
        private int retrievedCount;
        private int visibleCount;
        private int omittedCount;
        private int visibleChars;
        private boolean budgetHit;
        private boolean rawRefCreated;
    }

    private record ToolDetail(JsonNode output, Map<String, Object> params) {
    }
}
