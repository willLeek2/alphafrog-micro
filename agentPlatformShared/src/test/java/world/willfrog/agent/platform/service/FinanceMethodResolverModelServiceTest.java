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

    private final VersionedModelTestFixture versioned = new VersionedModelTestFixture();
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
        versioned.put("financeMethodResolverSystemPrompt", TEMPLATE, "RESOLVER_CATALOG");
        promptService = versioned.promptService();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
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
        when(aiServiceFactory.buildArchivedChatModelWithProviderOrderAndTemperature(
                any(), anyList(), anyDouble(), anyInt())).thenAnswer(call -> versioned.archived(model,
                        call.getArgument(0, AgentLlmResolver.ResolvedLlm.class).endpointName()));
        return newService();
    }

    private FinanceMethodResolverModelService newService() {
        // 边界读取与生产同口径：effective config 来自 resolver；测试默认映射到静态 llmProperties。
        lenient().when(resolverModelResolver.effectiveResolverConfig())
                .thenAnswer(invocation -> llmProperties.getFinanceMethodResolver());
        var service = new FinanceMethodResolverModelService(
                objectMapper, aiServiceFactory, resolverModelResolver, promptService, observabilityService);
        versioned.inject(service);
        return service;
    }
}
