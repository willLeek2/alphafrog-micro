package world.willfrog.agent.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResultOrigin;
import world.willfrog.agent.platform.experiment.AgentExperimentClient;
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.agent.platform.service.SearchEvidenceJudgeService;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRagPrefetch;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.RacingError;
import world.willfrog.racing.idl.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchTools {

    @DubboReference(timeout = 60000, retries = 0)
    private ExternalInfoDubboService externalInfoDubboService;

    @Autowired(required = false)
    private AgentPromptTextReader promptReader;

    private final ObjectMapper objectMapper;
    private final SearchEvidenceJudgeService searchEvidenceJudgeService;
    private final ObjectProvider<AgentExperimentClient> experiments;

    @Value("${RACING_SEARCH_MAX_RESULTS_EXPERIMENT_ID:}")
    private String maxResultsExperimentId = "";

    public SearchTools(ObjectMapper objectMapper, SearchEvidenceJudgeService searchEvidenceJudgeService) {
        this(objectMapper, searchEvidenceJudgeService, null);
    }

    @Autowired
    public SearchTools(ObjectMapper objectMapper, SearchEvidenceJudgeService searchEvidenceJudgeService,
                       ObjectProvider<AgentExperimentClient> experiments) {
        this.objectMapper = objectMapper;
        this.searchEvidenceJudgeService = searchEvidenceJudgeService;
        this.experiments = experiments;
    }

    @Tool
    public String searchWeb(@P(value = "搜索查询文本，必填", required = true) String query,
                            @P(value = "搜索场景：general、finance 或 news，可选", required = false) String scene,
                            @P(value = "后端或 preset 覆盖：perplexity、tavily、exa 或 preset 名，可选", required = false) String backend,
                            @P(value = "搜索强度档位，可选", required = false) String strength,
                            @P(value = "是否跳过热点缓存，可选，默认 false", required = false) boolean skipHotCache,
                            @P(value = "是否跳过 RAG 预检，可选，默认 false", required = false) boolean skipRagPrefetch,
                            @P(value = "时间范围起始 ISO 8601，可选", required = false) String timeRangeStart,
                            @P(value = "时间范围结束 ISO 8601，可选", required = false) String timeRangeEnd,
                            @P(value = "最大返回结果数，可选，默认 5", required = false) int maxResults) {
        return searchWebPrepared(prepareArguments(query, scene, backend, strength, skipHotCache,
                skipRagPrefetch, timeRangeStart, timeRangeEnd, maxResults));
    }

    /** 缓存读取之前固定实际参数，后续执行不再读取运行配置或重新取值。 */
    public PreparedSearchArguments prepareArguments(String query, String scene, String backend, String strength,
            boolean skipHotCache, boolean skipRagPrefetch, String timeRangeStart, String timeRangeEnd, int maxResults) {
        AgentContext.WebSearchConfig runConfig = AgentContext.getWebSearchConfig();
        int limit = resolveMaxResults(runConfig.maxResults(), maxResults);
        boolean effectiveSkipHotCache = runConfig.skipHotCache() != null ? runConfig.skipHotCache() : skipHotCache;
        boolean effectiveSkipRagPrefetch = runConfig.skipRagPrefetch() != null ? runConfig.skipRagPrefetch() : skipRagPrefetch;
        Map<String, Object> details = new LinkedHashMap<>();
        String experimentId = maxResultsExperimentId == null ? "" : maxResultsExperimentId.strip();
        if (!experimentId.isEmpty()) {
            AgentExperimentClient client = experiments == null ? null : experiments.getIfAvailable();
            if (client == null) throw RacingCallException.failure("SCOPE_UNAVAILABLE", "搜索实验调用组件不可用");
            String ordinarySource = writeJson(Map.of("business_position", "searchWeb.maxResults",
                    "source", runConfig.maxResults() != null && runConfig.maxResults() > 0
                            ? "run_config" : maxResults > 0 ? "tool_argument" : "built_in_default",
                    "source_revision", "run:" + nvl(AgentContext.getRunId()), "ordinary_value", limit));
            EvaluationValue evaluated = client.evaluate(AgentContext.getRunId(), experimentId,
                    Integer.toString(limit), ordinarySource);
            ValueSource source = evaluated.getSource();
            try {
                limit = new BigDecimal(evaluated.getValueJson().strip()).intValueExact();
                if (limit <= 0) throw new ArithmeticException();
            } catch (NumberFormatException | ArithmeticException failure) {
                RacingError.Builder error = RacingError.newBuilder().setCode("INVALID_ARGUMENT")
                        .setMessage("搜索实验返回的最大结果条数必须是正整数")
                        .putObjectLocator("experiment_id", experimentId)
                        .putDetails("source_kind", source.getKind().name())
                        .putDetails("configuration_scope_instance_id", source.getConfigurationScopeInstanceId());
                if (!evaluated.getAssignmentId().isEmpty()) error.putObjectLocator("assignment_id", evaluated.getAssignmentId());
                if (source.hasConfigurationVersion()) error.putDetails("configuration_version", Long.toString(source.getConfigurationVersion()));
                throw new RacingCallException(error.build());
            }
            effectiveSkipHotCache = true;
            details.put("experiment_id", experimentId);
            details.put("environment", evaluated.getActualTarget().getEnvironment());
            details.put("configuration_scope_instance_id", source.getConfigurationScopeInstanceId());
            details.put("source_kind", source.getKind().name());
            details.put("value_json", evaluated.getValueJson());
            details.put("run_id", nvl(AgentContext.getRunId()));
            if (!evaluated.getAssignmentId().isEmpty()) details.put("assignment_id", evaluated.getAssignmentId());
            if (source.hasConfigurationVersion()) details.put("configuration_version", source.getConfigurationVersion());
            if (source.hasCandidateId()) details.put("candidate_id", source.getCandidateId());
            if (!source.getBusinessDefaultSourceJson().isEmpty()) {
                details.put("ordinary_default_source_json", source.getBusinessDefaultSourceJson());
            }
        }
        WebSearchRequest req = WebSearchRequest.newBuilder()
                .setQuery(nvl(query))
                .setScene(nvl(scene))
                .setBackend(firstText(runConfig.backend(), backend))
                .setStrength(firstText(runConfig.strength(), strength))
                .setSkipHotCache(effectiveSkipHotCache)
                .setSkipRagPrefetch(effectiveSkipRagPrefetch)
                .setTimeRangeStart(nvl(timeRangeStart))
                .setTimeRangeEnd(nvl(timeRangeEnd))
                .setMaxResults(limit)
                .setRunId(nvl(AgentContext.getRunId()))
                .setUserId(nvl(AgentContext.getUserId()))
                .build();
        Map<String, Object> actual = Map.of("query", req.getQuery(), "scene", req.getScene(),
                "backend", req.getBackend(), "strength", req.getStrength(),
                "skipHotCache", req.getSkipHotCache(), "skipRagPrefetch", req.getSkipRagPrefetch(),
                "timeRangeStart", req.getTimeRangeStart(), "timeRangeEnd", req.getTimeRangeEnd(),
                "maxResults", req.getMaxResults());
        String discriminator;
        try {
            discriminator = details.isEmpty() ? "" : objectMapper.writeValueAsString(details);
        } catch (Exception failure) {
            throw RacingCallException.failure("INVALID_ARGUMENT", "搜索实验来源无法生成缓存标识");
        }
        return new PreparedSearchArguments(req, actual, discriminator, details);
    }

    public PromptText searchWebPrompt(String query, String scene, String backend, String strength,
            boolean skipHotCache, boolean skipRagPrefetch, String timeRangeStart, String timeRangeEnd, int maxResults) {
        return searchWebPreparedPrompt(prepareArguments(query, scene, backend, strength, skipHotCache,
                skipRagPrefetch, timeRangeStart, timeRangeEnd, maxResults));
    }

    public String searchWebPrepared(PreparedSearchArguments prepared) {
        return searchWebPreparedPrompt(prepared).text();
    }

    public PromptText searchWebPreparedPrompt(PreparedSearchArguments prepared) {
        WebSearchRequest req = prepared.request();
        {
            if (promptReader == null) throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                    "网页搜索缺少原根正文读取组件");
            var root = promptReader.current();
            if (!req.getRunId().equals(root.context().getCurrentRunId())) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE,
                        "网页搜索参数与当前原根运行不一致");
            }
            req = req.toBuilder().setRuntimeCallContext(root.context().toByteString())
                    .setPromptSelection(root.selection().racingSelection().toByteString()).build();
        }
        String query = req.getQuery();
        String scene = req.getScene();
        try {
            WebSearchResponse resp = externalInfoDubboService.webSearch(req);
            if (resp.hasModelSendBlock()) {
                var block = resp.getModelSendBlock();
                if (!ModelSendBlocks.isBlockedCode(block.getCode())) {
                    throw new ModelSendBlockedException(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                            "外部搜索返回的发送阻止原因无效");
                }
                throw new ModelSendBlockedException(block.getCode(), block.getReason(),
                        new RacingFailure(block.getRacingCode(), block.getRacingReasonCode(), block.getObjectLocatorMap()));
            }
            if (!resp.getOk() && ModelSendBlocks.isBlockedCode(resp.getErrorCode())) {
                throw new ModelSendBlockedException(resp.getErrorCode(), "外部搜索的模型发送前检查未通过");
            }
            if (resp.getResultOrigin() != WebSearchResultOrigin.WEB_SEARCH_RESULT_RUNTIME_DATA) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                        "外部搜索结果缺少明确来源，不能用于模型续轮");
            }

            // 如果 Dubbo 层返回 ok=false，则按错误处理
            if (!resp.getOk()) {
                // 上游错误码里保留着 HTTP 状态事实（各后端统一把状态写成 HTTP_<code>）：
                // 429 是限流，5xx 是上游故障；取值来自既有状态字段，不解析自由文本。上游码原样透传。
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", false);
                payload.put("tool", "searchWeb");
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("code", nvl(resp.getErrorCode()));
                err.put("message", nvl(resp.getErrorMessage()));
                ToolFailureCategory.putCategoryIfResolved(err, resp.getErrorCode(),
                        upstreamFailureCategory(resp.getErrorCode()));
                payload.put("error", err);
                payload.put("data", preparedDetails(prepared));
                return PromptText.runtimeData(objectMapper.writeValueAsString(payload));
            }

            // 组装 hits
            List<Map<String, Object>> hits = new ArrayList<>();
            List<String> requestedEntities = AgentContext.getExtractedEntities();
            for (WebSearchHit hit : resp.getHitsList().subList(0, Math.min(req.getMaxResults(), resp.getHitsCount()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", hit.getTitle());
                row.put("url", hit.getUrl());
                row.put("snippet", hit.getSnippet());
                row.put("source", hit.getSource());
                row.put("published_date", hit.getPublishedDate());
                row.put("score", hit.getScore());
                hits.add(row);
            }

            // 组装 citations
            List<Map<String, Object>> citations = new ArrayList<>();
            // 原生回答可能引用任意原序号，保留全部引用才能继续定位其证据。
            for (WebSearchCitation citation : resp.getCitationsList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", citation.getIndex());
                row.put("url", citation.getUrl());
                row.put("title", citation.getTitle());
                citations.add(row);
            }

            SearchEvidenceJudgeService.JudgeResult judgeResult = searchEvidenceJudgeService.judge(
                    query, requestedEntities, hits, citations);
            applyJudgeResult(hits, judgeResult.hits());
            applyJudgeResult(citations, judgeResult.citations());

            // 组装 backend_meta
            Map<String, Object> backendMeta = new LinkedHashMap<>();
            WebSearchBackendMeta bm = resp.getBackendMeta();
            if (bm != null) {
                backendMeta.put("backend", bm.getBackend());
                backendMeta.put("model_or_strength", bm.getModelOrStrength());
                backendMeta.put("cost_estimate_ms", bm.getCostEstimateMs());
                backendMeta.put("raw_query_sent", bm.getRawQuerySent());
            }

            // 组装 rag_prefetch
            Map<String, Object> ragPrefetch = new LinkedHashMap<>();
            WebSearchRagPrefetch rp = resp.getRagPrefetch();
            if (rp != null) {
                ragPrefetch.put("used", rp.getUsed());
                ragPrefetch.put("relevance_score", rp.getRelevanceScore());
                ragPrefetch.put("rag_summary", rp.getRagSummary());
            }

            Map<String, Object> answerMeta = new LinkedHashMap<>();
            WebSearchAnswerMeta am = resp.getAnswerMeta();
            if (am != null) {
                answerMeta.put("answer_type", am.getAnswerType());
                answerMeta.put("model_used", am.getModelUsed());
            }

            Map<String, Object> data = preparedDetails(prepared);
            data.put("hits", hits);
            data.put("answer", resp.getAnswer());
            data.put("citations", citations);
            data.put("answer_meta", answerMeta);
            data.put("backend_meta", backendMeta);
            data.put("rag_prefetch", ragPrefetch);
            data.put("canonical_query", resp.getCanonicalQuery());
            data.put("slot_signature", resp.getSlotSignature());
            data.put("result_hash", resp.getResultHash());
            data.put("requested_entities", requestedEntities);
            data.put("relevanceJudged", judgeResult.relevanceJudged());
            data.put("relevanceJudgeError", judgeResult.relevanceJudgeError());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("tool", "searchWeb");
            payload.put("data", data);
            return PromptText.runtimeData(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            // 本地异常包装行：RPC/序列化一类的上游或系统故障。
            return PromptText.runtimeData(writeJson(Map.of(
                    "ok", false,
                    "tool", "searchWeb",
                    "data", preparedDetails(prepared),
                    "error", Map.of(
                            "code", "TOOL_ERROR",
                            "message", nvl(e.getMessage()),
                            "category", ToolFailureCategory.UPSTREAM_OR_SYSTEM.name()
                    )
            )));
        }
    }

    private Map<String, Object> preparedDetails(PreparedSearchArguments prepared) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", prepared.request().getQuery());
        data.put("scene", prepared.request().getScene());
        data.put("actual_arguments", prepared.parameters());
        if (!prepared.experimentDetails().isEmpty()) data.put("experiment", prepared.experimentDetails());
        return data;
    }

    /** 请求与来源均在执行之前固定；缓存命中也使用同一份实际参数。 */
    public record PreparedSearchArguments(WebSearchRequest request, Map<String, Object> parameters,
            String cacheDiscriminator, Map<String, Object> experimentDetails) {
        public PreparedSearchArguments {
            parameters = Map.copyOf(parameters);
            experimentDetails = Map.copyOf(experimentDetails);
        }
    }

    /** 上游失败码携带的 HTTP 状态事实（HTTP_429 一族）→类别；其余按码兜底（未知默认上游/系统故障）。 */
    private static ToolFailureCategory upstreamFailureCategory(String errorCode) {
        if (errorCode != null && errorCode.startsWith("HTTP_")) {
            try {
                int status = Integer.parseInt(errorCode.substring("HTTP_".length()).trim());
                if (status == 429) {
                    return ToolFailureCategory.RATE_LIMITED_OR_CAPACITY;
                }
                if (status >= 500 && status < 600) {
                    return ToolFailureCategory.UPSTREAM_OR_SYSTEM;
                }
            } catch (NumberFormatException ignored) {
                // 后缀不是数字：按未知码走兜底
            }
        }
        return ToolFailureCategory.of(errorCode);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        return nvl(fallback);
    }

    private int resolveMaxResults(Integer runConfigMaxResults, int toolMaxResults) {
        if (runConfigMaxResults != null && runConfigMaxResults > 0) {
            return runConfigMaxResults;
        }
        return toolMaxResults <= 0 ? 5 : toolMaxResults;
    }

    private void applyJudgeResult(List<Map<String, Object>> rows,
                                  List<SearchEvidenceJudgeService.ItemJudgement> judgements) {
        for (int i = 0; i < rows.size(); i++) {
            SearchEvidenceJudgeService.ItemJudgement judgement = judgements != null && i < judgements.size()
                    ? judgements.get(i)
                    : new SearchEvidenceJudgeService.ItemJudgement(
                    true, List.of(), List.of(), "搜索证据相关性 judge 未返回该条结果", false, "JUDGE_RESULT_MISSING");
            Map<String, Object> row = rows.get(i);
            row.put("entityMatch", judgement.entityMatch());
            row.put("matchedEntities", judgement.matchedEntities());
            row.put("outOfScopeEntities", judgement.outOfScopeEntities());
            row.put("relevanceWarning", judgement.relevanceWarning());
            row.put("relevanceJudged", judgement.relevanceJudged());
            row.put("relevanceJudgeError", judgement.relevanceJudgeError());
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"ok\":false,\"tool\":\"searchWeb\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"failed to serialize tool result\"}}";
        }
    }
}
