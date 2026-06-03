package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallDetailMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fromLlmTrace_shouldNotExposeRawFields() throws Exception {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-abc");
    trace.put("phase", "execution");
    trace.put("stage", "execute");
    trace.put("time", "2026-05-07T10:00:00Z");
    trace.put("durationMs", 1200L);
    trace.put("model", "qwen-plus");
    trace.put("inputTokens", 10L);
    trace.put("outputTokens", 5L);
    trace.put("hasError", false);
    trace.put("outputText", "secret full answer");
    trace.put("reasoningText", "secret reasoning");
    trace.put("inputMessages", Map.of("role", "user"));
    trace.put("httpRequest", Map.of("url", "http://example"));
    trace.put("curlCommand", "curl ...");

    AgentCallDetailResponse detail = AgentCallDetailMapper.fromLlmTrace(trace, "llm-abc", "run-1");
    String json = objectMapper.writeValueAsString(detail);

    assertEquals("llm", detail.getType());
    assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
    assertEquals("llm-abc", detail.getId());
    assertNotNull(detail.getSummary());
    assertFalse(json.contains("outputText"));
    assertFalse(json.contains("reasoningText"));
    assertFalse(json.contains("inputMessages"));
    assertFalse(json.contains("httpRequest"));
    assertFalse(json.contains("curlCommand"));
    assertFalse(json.contains("secret full answer"));
    assertFalse(json.contains("secret reasoning"));
  }

  @Test
  void fromToolTrace_shouldNotExposeRawParamsOrOutput() throws Exception {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "tool-abc");
    trace.put("toolName", "searchAssetInfo");
    trace.put("phase", "execution");
    trace.put("success", true);
    trace.put("params", Map.of("query", "茅台", "secretFlag", true));
    trace.put("output", "{\"items\":[{\"code\":\"600519\",\"name\":\"贵州茅台\"}]}");
    trace.put("cacheKey", "cache-key-1");
    trace.put("decisionExcerpt", "raw decision");

    AgentCallDetailResponse detail = AgentCallDetailMapper.fromToolTrace(trace, "tool-abc", "run-1");
    String json = objectMapper.writeValueAsString(detail);

    assertEquals("tool", detail.getType());
    assertNotNull(detail.getTool());
    assertTrue(detail.getTool().getParamsSummary().contains("query=茅台"));
    assertTrue(detail.getTool().getOutputPreview().contains("600519"));
    assertFalse(json.contains("\"params\""));
    assertFalse(json.contains("cacheKey"));
    assertFalse(json.contains("decisionExcerpt"));
    assertFalse(json.contains("raw decision"));
  }

  @Test
  void unavailable_whenTraceMissing() {
    AgentCallDetailResponse detail = AgentCallDetailMapper.unavailable("tool", "missing-id", "run-1");
    assertEquals(AgentCallDetailResponse.KIND_UNAVAILABLE, detail.getDetailKind());
    assertEquals("missing-id", detail.getId());
    assertNull(detail.getSummary());
    assertNull(detail.getTool());
  }

  @Test
  void unknownToolParams_shouldNotLeakSensitiveValues() throws Exception {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "tool-secret");
    trace.put("toolName", "executePython");
    trace.put("success", true);
    trace.put("params", Map.of(
        "apiKey", "sk-secret",
        "code", "print(os.environ)",
        "secretFlag", true
    ));
    trace.put("output", "{\"ok\":true}");

    AgentCallDetailResponse detail = AgentCallDetailMapper.fromToolTrace(trace, "tool-secret", "run-1");
    String json = objectMapper.writeValueAsString(detail);

    assertEquals("parameterCount=3", detail.getTool().getParamsSummary());
    assertFalse(json.contains("sk-secret"));
    assertFalse(json.contains("print(os.environ"));
    assertFalse(json.contains("apiKey"));
    assertFalse(json.contains("secretFlag"));
  }

  @Test
  void searchWebParams_shouldOnlyExposeWhitelistedFields() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("query", "512800");
    params.put("backend", "tavily");
    params.put("maxResults", 5);
    params.put("internalToken", "must-not-show");

    String summary = AgentCallDetailMapper.summarizeParams("searchWeb", params);

    assertTrue(summary.contains("query=512800"));
    assertTrue(summary.contains("backend=tavily"));
    assertFalse(summary.contains("internalToken"));
    assertFalse(summary.contains("must-not-show"));
  }

  @Test
  void resolveLlmDetail_shouldReturnAvailableWhenNoBlobAndFlagFalse() {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-ok");
    trace.put("model", "qwen");
    trace.put("hasError", false);
    trace.put("detailBlobStored", false);

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-ok", "run-1", Optional.empty());

    assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
  }

  @Test
  void resolveLlmDetail_shouldReturnExpiredWhenBlobStoredButMissing() {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-expired");
    trace.put("detailBlobStored", true);
    trace.put("phase", "execution");

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-expired", "run-1", Optional.empty());

    assertEquals(AgentCallDetailResponse.KIND_EXPIRED, detail.getDetailKind());
  }

  @Test
  void fromToolTrace_longOutputPreview_setsTruncatedKind() {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "tool-long");
    trace.put("toolName", "generic");
    trace.put("success", true);
    trace.put("output", "y".repeat(AgentCallDetailMapper.PREVIEW_MAX_CHARS + 100));

    AgentCallDetailResponse detail = AgentCallDetailMapper.fromToolTrace(trace, "tool-long", "run-1");
    assertEquals(AgentCallDetailResponse.KIND_TRUNCATED, detail.getDetailKind());
    assertTrue(detail.getLimits().getTruncated());
  }

  @Test
  void resolveLlmDetail_includeThinkingWithReasoningBlob_mapsReasoningContent() throws Exception {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-think");
    trace.put("model", "kimi-k2.6");
    trace.put("hasError", false);
    trace.put("detailBlobStored", true);

    String blobJson = objectMapper.writeValueAsString(Map.of(
        "type", "llm",
        "traceId", "llm-think",
        "reasoningText", "I should first search for the asset"
    ));

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-think", "run-1", Optional.of(blobJson), true);

    assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
    assertEquals(AgentCallDetailResponse.SOURCE_CALL_DETAIL_REDIS, detail.getSource());
    assertNotNull(detail.getLlm());
    assertEquals("I should first search for the asset", detail.getLlm().getReasoningContent());
    assertNull(detail.getReasoningUnavailable());
  }

  @Test
  void resolveLlmDetail_includeThinkingButBlobMissing_returnsAvailableWithHint() {
    // blob 缺：detailKind 保持 AVAILABLE（不标 EXPIRED），仅 reasoningUnavailable=true
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-expired");
    trace.put("model", "kimi-k2.6");
    trace.put("hasError", false);
    trace.put("detailBlobStored", true);

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-expired", "run-1", Optional.empty(), true);

    assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
    assertEquals(AgentCallDetailResponse.SOURCE_OBSERVABILITY, detail.getSource());
    assertEquals(Boolean.TRUE, detail.getReasoningUnavailable());
    if (detail.getLlm() != null) {
      assertNull(detail.getLlm().getReasoningContent());
    }
  }

  @Test
  void resolveLlmDetail_includeThinkingButBlobHasNoReasoningText_returnsAvailableWithHint() throws Exception {
    // blob 存在但不含 reasoningText（个别场景：非 thinking 模型没存该字段）
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-noreason");
    trace.put("model", "qwen-plus");
    trace.put("hasError", false);
    trace.put("detailBlobStored", true);

    String blobJson = objectMapper.writeValueAsString(Map.of(
        "type", "llm",
        "traceId", "llm-noreason",
        "outputText", "normal answer, no thinking"
    ));

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-noreason", "run-1", Optional.of(blobJson), true);

    assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
    assertEquals(AgentCallDetailResponse.SOURCE_CALL_DETAIL_REDIS, detail.getSource());
    assertEquals(Boolean.TRUE, detail.getReasoningUnavailable());
    if (detail.getLlm() != null) {
      assertNull(detail.getLlm().getReasoningContent());
    }
  }

  @Test
  void resolveLlmDetail_includeThinkingFalse_doesNotExposeReasoning() throws Exception {
    // 默认/显式 false：reasoningContent / reasoningUnavailable 都不出（Step 1 契约）
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("traceId", "llm-default");
    trace.put("model", "kimi-k2.6");
    trace.put("hasError", false);
    trace.put("detailBlobStored", true);

    String blobJson = objectMapper.writeValueAsString(Map.of(
        "type", "llm",
        "traceId", "llm-default",
        "reasoningText", "this should not appear in default response"
    ));

    AgentCallDetailResponse detail = AgentCallDetailMapper.resolveLlmDetail(
            trace, "llm-default", "run-1", Optional.of(blobJson), false);

    String json = objectMapper.writeValueAsString(detail);
    assertFalse(json.contains("reasoningContent"));
    assertFalse(json.contains("reasoningUnavailable"));
    assertFalse(json.contains("this should not appear"));
  }
}
