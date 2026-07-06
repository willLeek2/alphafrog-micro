package world.willfrog.agent.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResultItem;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class RagTools {

    private static final int MAX_TOP_K = 10;
    private static final int RAG_SEARCH_VISIBLE_CHARS = 3000;
    private static final int RAG_SEARCH_SINGLE_PREVIEW_CHARS = 600;
    private static final int RAG_SEARCH_MAX_SNIPPETS_PER_DOC = 2;
    private static final int LOAD_DOCUMENT_PREVIEW_CHARS = 1600;
    private static final int LOAD_DOCUMENT_FULL_CONTENT_MAX_CHARS = 6000;

    @DubboReference(timeout = 60000, retries = 0)
    private ExternalInfoDubboService externalInfoDubboService;

    private final ObjectMapper objectMapper;
    private final Optional<RunRawRefStore> runRawRefStore;
    private final Optional<AgentLlmLocalConfigLoader> localConfigLoader;

    public RagTools(ObjectMapper objectMapper) {
        this(objectMapper, Optional.empty(), Optional.empty());
    }

    @Autowired
    public RagTools(ObjectMapper objectMapper,
                    Optional<RunRawRefStore> runRawRefStore,
                    Optional<AgentLlmLocalConfigLoader> localConfigLoader) {
        this.objectMapper = objectMapper;
        this.runRawRefStore = runRawRefStore == null ? Optional.empty() : runRawRefStore;
        this.localConfigLoader = localConfigLoader == null ? Optional.empty() : localConfigLoader;
    }

    @Tool("""
        【首选工具】查询公告、年报、研报原文内容。返回结构固定为 data.summary / data.top_refs / data.omitted_refs；
        当命中内容超出可见预算时，结果会通过 data.rawRef + data.read_hints 让你用 rereadToolResult 定向补读。
        
        适用场景：
          - 查询公司公告原文（如"募集资金变更"、"股权质押"、"重大合同"等）
          - 查询年报/半年报特定章节内容（如"风险提示"、"业务展望"）
          - 查询研报观点和数据
        
        与 getFinancialReport 的区别：
          - ragSearch：查公告/研报原文、非结构化文本、事件描述
          - getFinancialReport：查结构化财务数据（利润、资产负债等）
        
        参数说明：
          queryText  - 查询内容（如"贵州茅台募集资金变更公告"），必填
          docType    - 文档类型："announcement"（公告）| "research_report"（研报）| ""（不限），可选
          tsCode     - 股票代码过滤（如"600519.SH"），可选，建议填写以提高准确度
          indName    - 行业过滤（如"电子"、"电新"），可选，仅对研报有效
          topK       - 检索候选条数（默认5，最大10），可选。工具会按预算只展示少量 preview。

        返回字段要点：
          - data.summary: hit_count / visible_count / omitted_count / visible_chars / budget_hit
          - data.top_refs[].ref_id: 本次回答内引用 ID；最终答案 citation 应使用 <rag-cite ref="rag_ref_001" />
          - data.top_refs[].source_key: 过渡证据定位字段，优先 oss_url + chunk_index
          - data.top_refs[].preview: 命中原文片段，不是模型摘要
          - data.rawRef: 仅用于 rereadToolResult 继续读取，不可作为 citation
          - data.read_hints: keyword/range 两种合法 rereadToolResult 调用提示；不要传空 keyword
        """)
    public String ragSearch(String queryText, String docType, String tsCode, String indName, int topK) {
        try {
            int k = (topK <= 0 || topK > MAX_TOP_K) ? 5 : topK;
            RagSearchRequest req = RagSearchRequest.newBuilder()
                    .setQueryText(nvl(queryText))
                    .setDocType(nvl(docType))
                    .setTsCode(nvl(tsCode))
                    .setIndName(nvl(indName))
                    .setTopK(k)
                    .build();
            RagSearchResponse resp = externalInfoDubboService.ragSearch(req);

            List<Map<String, Object>> fullRefs = new ArrayList<>();
            List<Map<String, Object>> topRefs = new ArrayList<>();
            List<Map<String, Object>> omittedRefs = new ArrayList<>();
            Map<String, Integer> snippetsPerDoc = new HashMap<>();
            int totalEstimatedChars = 0;
            int visibleChars = 0;
            int seq = 1;
            int visibleBudget = ragVisibleChars();
            int previewChars = ragPreviewChars();
            int snippetsPerDocCap = ragSnippetCapPerDoc();
            for (RagSearchResultItem item : resp.getItemsList()) {
                String refId = "rag_ref_%03d".formatted(seq++);
                String chunkText = nvl(item.getChunkText());
                int chunkIndex = item.getChunkIndex();
                String sourceKey = sourceKey(item.getOssUrl(), chunkIndex);
                totalEstimatedChars += chunkText.length();

                Map<String, Object> full = buildRef(item, refId, sourceKey, chunkIndex, chunkText, chunkText);
                fullRefs.add(full);

                String docKey = firstNonBlank(item.getOssUrl(), item.getTitle(), item.getTsCode(), refId);
                int docCount = snippetsPerDoc.getOrDefault(docKey, 0);
                String preview = truncate(chunkText, previewChars);
                boolean docCapHit = docCount >= snippetsPerDocCap;
                boolean budgetHit = visibleChars > 0 && visibleChars + preview.length() > visibleBudget;
                if (docCapHit || budgetHit) {
                    omittedRefs.add(omittedRef(item, sourceKey, chunkIndex,
                            docCapHit ? "per_doc_snippet_cap_exceeded" : "answer_context_budget_exceeded"));
                    continue;
                }
                Map<String, Object> top = buildRef(item, refId, sourceKey, chunkIndex, preview, null);
                topRefs.add(top);
                snippetsPerDoc.put(docKey, docCount + 1);
                visibleChars += preview.length();
            }

            boolean needsRawRef = totalEstimatedChars > visibleChars || !omittedRefs.isEmpty();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("hit_count", resp.getTotal());
            summary.put("visible_count", topRefs.size());
            summary.put("omitted_count", Math.max(0, resp.getItemsCount() - topRefs.size()));
            summary.put("total_estimated_chars", totalEstimatedChars);
            summary.put("visible_chars", visibleChars);
            summary.put("budget_hit", needsRawRef);
            summary.put("retrieval_mode", "dense_only");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", nvl(queryText));
            data.put("doc_type", nvl(docType));
            data.put("summary", summary);
            data.put("top_refs", topRefs);
            data.put("omitted_refs", omittedRefs);
            if (needsRawRef) {
                data.put("needs_raw_ref", true);
                data.put("raw_refs", fullRefs);
                attachRawRef(data, "ragSearch");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("tool", "ragSearch");
            payload.put("data", data);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"ragSearch\",\"error\":{\"code\":\"TOOL_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    @Tool("""
        根据 OSS URL 获取文档原文窗口。P0 起本工具按 loadDocumentSection 语义工作：
        短文档直接返回全文；长文档只返回 preview，并通过 data.rawRef + data.read_hints
        让你用 rereadToolResult 按 keyword 或 offset/limit 继续读取。
        注意：rawRef 只能传给 rereadToolResult，不能作为最终答案 citation。
        """)
    public String loadDocument(String ossUrl) {
        try {
            if (ossUrl == null || ossUrl.isBlank()) {
                return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"INVALID_ARGUMENT\",\"message\":\"ossUrl is empty\"}}";
            }
            if (ossUrl.startsWith("raw_ref_") || ossUrl.startsWith("raw-ref:")) {
                return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"INVALID_ARGUMENT\",\"message\":\"rawRef must be passed to rereadToolResult, not loadDocument\"}}";
            }
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(ossUrl))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"HTTP_ERROR\",\"message\":\"Status "
                        + response.statusCode() + "\"}}";
            }
            Map<String, Object> data = new LinkedHashMap<>();
            String body = response.body();
            boolean truncated = body.length() > ragShortDocFullThreshold();
            data.put("oss_url", ossUrl);
            data.put("section_path", null);
            data.put("content_length", body.length());
            data.put("preview_start", 0);
            data.put("preview", truncate(body, truncated ? loadDocumentPreviewChars() : body.length()));
            data.put("preview_length", ((String) data.get("preview")).length());
            data.put("truncated", truncated);
            if (truncated) {
                data.put("needs_raw_ref", true);
                data.put("content", body);
                attachRawRef(data, "loadDocument");
            } else {
                data.put("content", body);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("tool", "loadDocument");
            result.put("data", data);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"TOOL_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private void attachRawRef(Map<String, Object> data, String displayName) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || runId.isBlank()) {
            log.warn("Skip rawRef registration for {} because runId/store is unavailable: runId={}, storePresent={}",
                    displayName, runId, runRawRefStore.isPresent());
            data.put("raw_ref_unavailable_reason", "MISSING_RUN_ID");
            return;
        }
        if (runRawRefStore.isEmpty()) {
            log.warn("Skip rawRef registration for {} because runId/store is unavailable: runId={}, storePresent={}",
                    displayName, runId, false);
            data.put("raw_ref_unavailable_reason", "RAW_REF_STORE_UNAVAILABLE");
            return;
        }
        try {
            String rawContent = objectMapper.writeValueAsString(data);
            String rawRef = runRawRefStore.get().register(runId, nvl(userId), displayName, rawContent, rawRefTtlSeconds());
            data.remove("needs_raw_ref");
            data.remove("raw_refs");
            data.remove("content");
            data.put("rawRef", rawRef);
            data.put("read_hints", readHints(rawRef));
        } catch (Exception e) {
            log.warn("Failed to register rawRef for {}", displayName, e);
            data.put("rawRef_error", "RAW_REF_REGISTER_FAILED");
        }
    }

    private List<Map<String, Object>> readHints(String rawRef) {
        int keywordMax = rereadKeywordMaxLimit();
        int rangeMin = rereadRangeMinLimitWithoutKeyword();
        int rangeMax = rereadRangeMaxLimit();
        int suggestedRangeLimit = Math.max(rangeMin, Math.min(rangeMax, 3000));

        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("mode", "keyword");
        keyword.put("tool", "rereadToolResult");
        keyword.put("rawRef", rawRef);
        keyword.put("required_args", List.of("keyword"));
        keyword.put("optional_args", List.of("limit"));
        keyword.put("constraints", Map.of(
                "keyword_required", true,
                "max_limit", keywordMax
        ));

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("mode", "range");
        range.put("tool", "rereadToolResult");
        range.put("rawRef", rawRef);
        range.put("required_args", List.of("offset", "limit"));
        range.put("suggested_args", Map.of(
                "offset", 0,
                "limit", suggestedRangeLimit
        ));
        range.put("constraints", Map.of(
                "keyword_omitted", true,
                "min_limit_without_keyword", rangeMin,
                "max_limit", rangeMax
        ));
        return List.of(keyword, range);
    }

    private Map<String, Object> buildRef(RagSearchResultItem item,
                                         String refId,
                                         String sourceKey,
                                         int chunkIndex,
                                         String preview,
                                         String chunkText) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ref_id", refId);
        row.put("source_key", sourceKey);
        row.put("score", item.getScore());
        row.put("doc_type", item.getDocType());
        row.put("ts_code", item.getTsCode());
        row.put("ind_name", item.getIndName());
        row.put("title", item.getTitle());
        row.put("date", item.getDate());
        row.put("chunk_index", chunkIndex);
        row.put("chunk_span", Map.of("start", chunkIndex, "end", chunkIndex));
        row.put("chunk_indices", List.of(chunkIndex));
        row.put("section_path", null);
        row.put("page_range", null);
        row.put("preview", preview);
        row.put("oss_url", item.getOssUrl());
        if (chunkText != null) {
            row.put("chunk_text", chunkText);
        }
        return row;
    }

    private Map<String, Object> omittedRef(RagSearchResultItem item, String sourceKey, int chunkIndex, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_key", sourceKey);
        row.put("title", item.getTitle());
        row.put("chunk_index", chunkIndex);
        row.put("reason", reason);
        return row;
    }

    private String sourceKey(String ossUrl, int chunkIndex) {
        String base = nvl(ossUrl);
        if (base.isBlank()) {
            return "chunk=" + chunkIndex;
        }
        return base + "#chunk=" + chunkIndex;
    }

    private String truncate(String text, int maxChars) {
        String safe = nvl(text);
        if (maxChars <= 0 || safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private AgentLlmProperties.Tools toolsConfig() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .orElseGet(AgentLlmProperties.Tools::new);
    }

    private int ragVisibleChars() {
        Integer value = toolsConfig().getRag().getVisibleChars();
        return value != null && value > 0 ? value : RAG_SEARCH_VISIBLE_CHARS;
    }

    private int ragPreviewChars() {
        Integer value = toolsConfig().getRag().getPreviewChars();
        return value != null && value > 0 ? value : RAG_SEARCH_SINGLE_PREVIEW_CHARS;
    }

    private int ragSnippetCapPerDoc() {
        Integer value = toolsConfig().getRag().getSnippetCapPerDoc();
        return value != null && value > 0 ? value : RAG_SEARCH_MAX_SNIPPETS_PER_DOC;
    }

    private int ragShortDocFullThreshold() {
        Integer value = toolsConfig().getRag().getShortDocFullThreshold();
        return value != null && value > 0 ? value : LOAD_DOCUMENT_FULL_CONTENT_MAX_CHARS;
    }

    private int loadDocumentPreviewChars() {
        return Math.max(1, Math.min(LOAD_DOCUMENT_PREVIEW_CHARS, ragVisibleChars()));
    }

    private long rawRefTtlSeconds() {
        AgentLlmProperties.ToolRawRef rawRef = toolsConfig().getRawRef();
        if (rawRef.getTtlSeconds() != null && rawRef.getTtlSeconds() > 0) {
            return rawRef.getTtlSeconds();
        }
        if (rawRef.getTtlHours() != null && rawRef.getTtlHours() > 0) {
            return rawRef.getTtlHours() * 3600L;
        }
        return 21600L;
    }

    private int rereadKeywordMaxLimit() {
        AgentLlmProperties.ToolReread reread = toolsConfig().getReread();
        if (reread.getKeywordCharLimit() != null && reread.getKeywordCharLimit() > 0) {
            return reread.getKeywordCharLimit();
        }
        if (reread.getMaxLimit() != null && reread.getMaxLimit() > 0) {
            return reread.getMaxLimit();
        }
        return 4000;
    }

    private int rereadRangeMaxLimit() {
        AgentLlmProperties.ToolReread reread = toolsConfig().getReread();
        if (reread.getRangeMaxLimit() != null && reread.getRangeMaxLimit() > 0) {
            return reread.getRangeMaxLimit();
        }
        if (reread.getMaxLimit() != null && reread.getMaxLimit() > 0) {
            return reread.getMaxLimit();
        }
        return 6000;
    }

    private int rereadRangeMinLimitWithoutKeyword() {
        Integer value = toolsConfig().getReread().getRangeMinLimitWithoutKeyword();
        return value != null && value > 0 ? value : 3000;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
