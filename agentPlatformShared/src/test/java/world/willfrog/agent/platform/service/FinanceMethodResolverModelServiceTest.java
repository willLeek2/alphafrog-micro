package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient.Ok;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ResolverResult;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceMethodResolverModelServiceTest {

    private static final String TEMPLATE = "You are a financial method resolver. {{RESOLVER_CATALOG}}";
    private static final String VALID_JSON =
            "{\"status\":\"NEEDS_CLARIFICATION\",\"candidates\":[{\"methodId\":\"finance.growth.cagr\","
                    + "\"version\":\"1.0.0\",\"specDigest\":\"sha256:cagr\",\"matchReason\":\"复合增长速度\","
                    + "\"unresolvedTerms\":[\"这几年\"],\"clarificationQuestions\":[\"希望从哪个交易日算到哪个交易日？\"]}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgentAiServiceFactory aiServiceFactory;

    @Mock
    private FinanceMethodResolverModelResolver resolverModelResolver;

    @Mock
    private AgentPromptService promptService;

    @Mock
    private AgentRunObservabilityService observabilityService;

    private AgentLlmProperties llmProperties;

    @Mock
    private ChatModel model;

    @BeforeEach
    void setUp() {
        llmProperties = new AgentLlmProperties();
        AgentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void resolve_shouldReturnOkWithRawJsonRouteAndPromptVersion() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse(VALID_JSON));

        ResolverResult result = service.resolve("这几年涨得怎么样", "已取收盘价", "catalog-text");

        Ok ok = assertInstanceOf(Ok.class, result);
        assertEquals(VALID_JSON, ok.rawJson());
        assertEquals("openai-compatible", ok.route().provider());
        assertEquals("https://example.com/v1", ok.route().endpoint());
        assertEquals("resolver-model", ok.route().model());
        assertEquals("sha256:" + sha256Hex(TEMPLATE), ok.resolverPromptVersion());
    }

    @Test
    void resolve_shouldReturnNoRouteWhenNoRouteConfigured() {
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of());
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.NO_ROUTE, error.kind());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldReturnBadJsonWhenResponseNotJson() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("不是 JSON"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
        verify(model, times(2)).chat(anyList());
    }

    @Test
    void resolve_shouldReturnBadJsonWhenResponseFenced() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("```json\n" + VALID_JSON + "\n```"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
    }

    @Test
    void resolve_shouldReturnBadJsonWhenTrailingGarbage() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse(VALID_JSON + " trailing"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
    }

    @Test
    void resolve_shouldReturnBadJsonWhenResponseExceedsBytes() {
        llmProperties.getFinanceMethodResolver().setResponseMaxBytes(16);
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse(VALID_JSON));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
        assertTrue(error.message().contains("exceed configured limit"));
    }

    @Test
    void resolve_shouldReturnBadJsonWhenCandidatesExceedLimit() {
        llmProperties.getFinanceMethodResolver().setMaxCandidates(1);
        FinanceMethodResolverModelService service = serviceWithModel();
        String twoCandidates = "{\"status\":\"AMBIGUOUS\",\"candidates\":["
                + "{\"methodId\":\"a\",\"version\":\"1.0.0\",\"specDigest\":\"sha256:a\",\"matchReason\":\"x\"},"
                + "{\"methodId\":\"b\",\"version\":\"1.0.0\",\"specDigest\":\"sha256:b\",\"matchReason\":\"y\"}]}";
        when(model.chat(anyList())).thenReturn(jsonResponse(twoCandidates));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
        assertTrue(error.message().contains("candidate count"));
    }

    @Test
    void resolve_shouldReturnRequestTooLarge() {
        llmProperties.getFinanceMethodResolver().setRequestMaxBytes(8);
        FinanceMethodResolverModelService service = serviceWithModel();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.REQUEST_TOO_LARGE, error.kind());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldReturnCatalogBudgetExceeded() {
        llmProperties.getFinanceMethodResolver().setCatalogPromptMaxBytes(10);
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "this-catalog-text-is-longer-than-ten-bytes");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CATALOG_BUDGET_EXCEEDED, error.kind());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldReturnCallFailedWhenModelThrows() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenThrow(new IllegalStateException("boom"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CALL_FAILED, error.kind());
        assertTrue(error.message().contains("boom"));
    }

    @Test
    void resolve_shouldReturnTimeoutWhenCauseChainHasSocketTimeout() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenThrow(new RuntimeException(new SocketTimeoutException("read timed out")));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.TIMEOUT, error.kind());
    }

    @Test
    void resolve_shouldNeverGenerateSyntheticIdentity() {
        // 外层无 toolCallId 时服务自身不生成 resolver-UUID；身份缺失由 tools 层 TOOL_CALL_ID_MISSING 负责。
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        assertInstanceOf(Ok.class, result);
        assertNull(AgentContext.getToolCallId());
    }

    @Test
    void resolve_shouldComputePromptVersionFromActualTemplate() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));
        when(promptService.financeMethodResolverSystemPromptTemplate())
                .thenReturn("TEMPLATE_A {{RESOLVER_CATALOG}}", "TEMPLATE_B {{RESOLVER_CATALOG}}");

        ResolverResult first = service.resolve("query", null, "catalog-text");
        ResolverResult second = service.resolve("query", null, "catalog-text");

        assertEquals("sha256:" + sha256Hex("TEMPLATE_A {{RESOLVER_CATALOG}}"),
                assertInstanceOf(Ok.class, first).resolverPromptVersion());
        assertEquals("sha256:" + sha256Hex("TEMPLATE_B {{RESOLVER_CATALOG}}"),
                assertInstanceOf(Ok.class, second).resolverPromptVersion());
    }

    @Test
    void resolve_shouldInjectCatalogIntoActualTemplate() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));

        service.resolve("query", null, "my-catalog");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        SystemMessage systemMessage = (SystemMessage) captor.getValue().get(0);
        assertTrue(systemMessage.text().contains("my-catalog"));
        assertTrue(systemMessage.text().startsWith("You are a financial method resolver."));
    }

    @Test
    void resolve_shouldPinProviderAndEndpointFromResolvedLlm() {
        StageLlmConfig stage = new StageLlmConfig();
        stage.setEndpointName("dashscope");
        stage.setModelName("qwen-lite");
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stage, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "dashscope", null, "qwen-lite", "key", "cn", List.of(), null);
        when(aiServiceFactory.resolveLlm("dashscope", "qwen-lite")).thenReturn(resolved);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                any(), anyList(), anyDouble(), anyInt())).thenReturn(model);
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));
        when(promptService.financeMethodResolverSystemPromptTemplate()).thenReturn(TEMPLATE);
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        Ok ok = assertInstanceOf(Ok.class, result, () -> "unexpected: " + result);
        assertEquals("dashscope", ok.route().provider());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", ok.route().endpoint());
        assertEquals("qwen-lite", ok.route().model());
    }

    @Test
    void resolve_shouldSnapshotAndRestoreSevenContextFieldsOnSuccess() {
        FinanceMethodResolverModelService service = serviceWithModel();
        Map<String, Object> capturedInCall = new LinkedHashMap<>();
        when(model.chat(anyList())).thenAnswer(invocation -> {
            capturedInCall.put("phase", AgentContext.getPhase());
            capturedInCall.put("stage", AgentContext.getStage());
            capturedInCall.put("reasoningEffort", AgentContext.getReasoningEffort());
            capturedInCall.put("spec", AgentContext.getStructuredOutputSpec());
            capturedInCall.put("meta", AgentContext.peekLlmCallRequestMeta());
            capturedInCall.put("providerTraceId", AgentContext.peekProviderLlmTraceId());
            capturedInCall.put("lastRecordedTraceId", AgentContext.peekLastRecordedLlmTraceId());
            return jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}");
        });
        presetOuterContext();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        assertInstanceOf(Ok.class, result);
        assertEquals(FinanceMethodResolverModelService.STAGE, capturedInCall.get("phase"));
        assertEquals(FinanceMethodResolverModelService.STAGE, capturedInCall.get("stage"));
        assertNull(capturedInCall.get("reasoningEffort"));
        assertTrue(capturedInCall.get("spec") instanceof AgentContext.StructuredOutputSpec);
        Object inCallMeta = capturedInCall.get("meta");
        assertTrue(inCallMeta instanceof Map);
        assertEquals(FinanceMethodResolverModelService.STAGE, ((Map<?, ?>) inCallMeta).get("stage"));
        // resolver 内不得继承外层 provider/lastRecorded trace（observability 会消费它们）
        assertNull(capturedInCall.get("providerTraceId"));
        assertNull(capturedInCall.get("lastRecordedTraceId"));
        assertOuterContextRestored();
    }

    @Test
    void resolve_shouldRestoreContextWhenBadJsonRetriesExhausted() {
        FinanceMethodResolverModelService service = serviceWithModel();
        AtomicReference<String> inCallProviderTrace = new AtomicReference<>("unset");
        when(model.chat(anyList())).thenAnswer(invocation -> {
            inCallProviderTrace.set(AgentContext.peekProviderLlmTraceId());
            return jsonResponse("not-json");
        });
        presetOuterContext();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.BAD_JSON, error.kind());
        assertNull(inCallProviderTrace.get());
        assertOuterContextRestored();
    }

    @Test
    void resolve_shouldRestoreContextWhenModelThrows() {
        FinanceMethodResolverModelService service = serviceWithModel();
        AtomicReference<String> inCallProviderTrace = new AtomicReference<>("unset");
        when(model.chat(anyList())).thenAnswer(invocation -> {
            inCallProviderTrace.set(AgentContext.peekProviderLlmTraceId());
            throw new IllegalStateException("boom");
        });
        presetOuterContext();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CALL_FAILED, error.kind());
        assertNull(inCallProviderTrace.get());
        assertOuterContextRestored();
    }

    @Test
    void resolve_shouldClearReasoningEffortDuringCallAndRestoreAfter() {
        FinanceMethodResolverModelService service = serviceWithModel();
        AtomicReference<String> inCallReasoning = new AtomicReference<>("unset");
        when(model.chat(anyList())).thenAnswer(invocation -> {
            inCallReasoning.set(AgentContext.getReasoningEffort());
            return jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}");
        });
        AgentContext.setReasoningEffort("high");

        ResolverResult result = service.resolve("query", null, "catalog-text");

        assertInstanceOf(Ok.class, result);
        assertNull(inCallReasoning.get());
        assertEquals("high", AgentContext.getReasoningEffort());
    }

    @Test
    void resolve_shouldFallBackToDefaultRouteWhenStageBuildFails() {
        StageLlmConfig stageCfg = new StageLlmConfig();
        stageCfg.setEndpointName("broken-endpoint");
        stageCfg.setModelName("broken-model");
        StageLlmConfig defaultCfg = new StageLlmConfig();
        defaultCfg.setEndpointName("default-endpoint");
        defaultCfg.setModelName("default-model");
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stageCfg, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG),
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        defaultCfg, FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE)));
        AgentLlmResolver.ResolvedLlm broken = new AgentLlmResolver.ResolvedLlm(
                "broken-endpoint", "https://broken.example.com/v1", "broken-model", "key", "", List.of(), null);
        AgentLlmResolver.ResolvedLlm fallback = new AgentLlmResolver.ResolvedLlm(
                "default-endpoint", "https://openrouter.ai/api/v1", "default-model", "key", "", List.of(), null);
        when(aiServiceFactory.resolveLlm("broken-endpoint", "broken-model")).thenReturn(broken);
        when(aiServiceFactory.resolveLlm("default-endpoint", "default-model")).thenReturn(fallback);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                org.mockito.ArgumentMatchers.eq(broken), anyList(), anyDouble(), anyInt()))
                .thenThrow(new IllegalStateException("stage route misconfigured"));
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                org.mockito.ArgumentMatchers.eq(fallback), anyList(), anyDouble(), anyInt()))
                .thenReturn(model);
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));
        when(promptService.financeMethodResolverSystemPromptTemplate()).thenReturn(TEMPLATE);
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        Ok ok = assertInstanceOf(Ok.class, result);
        assertEquals("default-model", ok.route().model());
        assertEquals("openrouter", ok.route().provider());
        assertEquals("https://openrouter.ai/api/v1", ok.route().endpoint());
    }

    @Test
    void resolve_shouldReturnNoRouteWhenAllCandidatesFail() {
        StageLlmConfig stageCfg = new StageLlmConfig();
        stageCfg.setEndpointName("broken-endpoint");
        stageCfg.setModelName("broken-model");
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stageCfg, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        when(aiServiceFactory.resolveLlm("broken-endpoint", "broken-model"))
                .thenThrow(new IllegalStateException("endpoint not configured"));
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.NO_ROUTE, error.kind());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldFailClosedWhenNonDashScopeEndpointHasBlankBaseUrl() {
        StageLlmConfig stageCfg = new StageLlmConfig();
        stageCfg.setEndpointName("custom-endpoint");
        stageCfg.setModelName("custom-model");
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stageCfg, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        AgentLlmResolver.ResolvedLlm blankBaseUrl = new AgentLlmResolver.ResolvedLlm(
                "custom-endpoint", null, "custom-model", "key", "", List.of(), null);
        when(aiServiceFactory.resolveLlm("custom-endpoint", "custom-model")).thenReturn(blankBaseUrl);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                any(), anyList(), anyDouble(), anyInt())).thenReturn(model);
        FinanceMethodResolverModelService service = newService();

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.NO_ROUTE, error.kind());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldFailClosedWhenTemplateMissingPlaceholder() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(promptService.financeMethodResolverSystemPromptTemplate()).thenReturn("template without placeholder");

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CALL_FAILED, error.kind());
        assertTrue(error.message().contains("exactly one"));
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldFailClosedWhenTemplateHasDuplicatePlaceholders() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(promptService.financeMethodResolverSystemPromptTemplate())
                .thenReturn("A {{RESOLVER_CATALOG}} B {{RESOLVER_CATALOG}}");

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CALL_FAILED, error.kind());
        assertTrue(error.message().contains("exactly one"));
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldReturnCallFailedWhenPayloadSerializationFails() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonProcessingException("serialize boom") {
                };
            }
        };
        FinanceMethodResolverModelService service = new FinanceMethodResolverModelService(
                failingMapper, aiServiceFactory, resolverModelResolver, promptService, observabilityService);

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.CALL_FAILED, error.kind());
        assertTrue(error.message().contains("serialize"));
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldCheckRequestBytesOnSerializedMessages() {
        // 30 个引号序列化转义后 user JSON 约 90 字节，加 render 后 systemPrompt 超过 64 字节上限；
        // 原始 query+context 简单相加（30 字节）不会触发——钉死"按实际 message content 求和"口径。
        llmProperties.getFinanceMethodResolver().setRequestMaxBytes(64);
        FinanceMethodResolverModelService service = serviceWithModel();
        String quotes = "\"".repeat(30);

        ResolverResult result = service.resolve(quotes, null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.REQUEST_TOO_LARGE, error.kind());
        assertTrue(error.message().contains("resolver request message bytes"));
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldFailClosedBeforeChatWhenLocalTemplateOversized() {
        // 超大 local template：catalog budget 合格，但 render 后 systemPrompt 把请求顶过上限；
        // 必须在 model.chat() 前 fail-closed，且错误信息只报 size/cap、不回显 prompt 内容。
        FinanceMethodResolverModelService service = serviceWithModel();
        String marker = "OVERSIZED-TEMPLATE-MARKER";
        String hugeTemplate = marker + "x".repeat(20000) + " {{RESOLVER_CATALOG}}";
        when(promptService.financeMethodResolverSystemPromptTemplate()).thenReturn(hugeTemplate);

        ResolverResult result = service.resolve("query", null, "catalog-text");

        TechnicalError error = assertInstanceOf(TechnicalError.class, result);
        assertEquals(ErrorKind.REQUEST_TOO_LARGE, error.kind());
        assertTrue(error.message().contains("exceed configured limit"));
        assertFalse(error.message().contains(marker));
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldRecordObservabilityWithOwnStageAndRoute() {
        AgentContext.setRunId("run-obs-1");
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(jsonResponse("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"));

        ResolverResult result = service.resolve("query", null, "catalog-text");

        assertInstanceOf(Ok.class, result);
        verify(observabilityService).recordLlmCall(
                org.mockito.ArgumentMatchers.eq("run-obs-1"),
                org.mockito.ArgumentMatchers.eq(FinanceMethodResolverModelService.STAGE),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("https://example.com/v1"),
                org.mockito.ArgumentMatchers.eq("resolver-model"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        FinanceMethodResolverModelService.STAGE.equals(map.get("stage"))
                                && "resolver-model".equals(map.get("resolver_model"))
                                && "openai-compatible".equals(map.get("resolver_provider"))),
                any());
    }

    private void presetOuterContext() {
        AgentContext.setPhase("outer_phase");
        AgentContext.setStage("outer_stage");
        AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                "outer_spec", false, Map.of("type", "object"), false, true));
        AgentContext.setReasoningEffort("medium");
        AgentContext.setProviderLlmTraceId("outer-provider-trace");
        AgentContext.setLlmCallRequestMeta(Map.of("outer_key", "outer_value"));
        AgentContext.setLastRecordedLlmTraceId("outer-last-recorded");
    }

    private void assertOuterContextRestored() {
        assertEquals("outer_phase", AgentContext.getPhase());
        assertEquals("outer_stage", AgentContext.getStage());
        AgentContext.StructuredOutputSpec spec = AgentContext.getStructuredOutputSpec();
        assertTrue(spec != null && "outer_spec".equals(spec.schemaName()));
        assertEquals("medium", AgentContext.getReasoningEffort());
        assertEquals("outer-provider-trace", AgentContext.peekProviderLlmTraceId());
        assertEquals(Map.of("outer_key", "outer_value"), AgentContext.peekLlmCallRequestMeta());
        assertEquals("outer-last-recorded", AgentContext.peekLastRecordedLlmTraceId());
    }

    private ChatResponse jsonResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private FinanceMethodResolverModelService serviceWithModel() {
        StageLlmConfig stage = new StageLlmConfig();
        stage.setEndpointName("resolver-endpoint");
        stage.setModelName("resolver-model");
        when(resolverModelResolver.resolveCandidates(any())).thenReturn(List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stage, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "resolver-endpoint", "https://example.com/v1", "resolver-model", "key", "", List.of(), null);
        when(aiServiceFactory.resolveLlm("resolver-endpoint", "resolver-model")).thenReturn(resolved);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                any(), anyList(), anyDouble(), anyInt())).thenReturn(model);
        lenient().when(promptService.financeMethodResolverSystemPromptTemplate()).thenReturn(TEMPLATE);
        return newService();
    }

    private FinanceMethodResolverModelService newService() {
        // 边界读取与生产同口径：effective config 来自 resolver；测试默认映射到静态 llmProperties。
        lenient().when(resolverModelResolver.effectiveResolverConfig())
                .thenAnswer(invocation -> llmProperties.getFinanceMethodResolver());
        return new FinanceMethodResolverModelService(
                objectMapper, aiServiceFactory, resolverModelResolver, promptService, observabilityService);
    }
}
