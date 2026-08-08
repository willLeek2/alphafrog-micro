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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceMethodResolverModelServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgentAiServiceFactory aiServiceFactory;

    @Mock
    private FinanceMethodResolverModelResolver resolverModelResolver;

    @Mock
    private AgentPromptService promptService;

    @Mock
    private AgentObservabilityService observabilityService;

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
    void resolve_shouldReturnCandidatesWhenLlmReturnsValidJson() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"NEEDS_CLARIFICATION","candidates":[{"methodId":"finance.growth.cagr","version":"1.0.0","specDigest":"sha256:cagr","matchReason":"复合增长速度","unresolvedTerms":["这几年"],"clarificationQuestions":["希望从哪个交易日算到哪个交易日？"]}]}
                        """))
                .build());

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "这几年涨得怎么样", "已取收盘价", "catalog-text");

        assertEquals("NEEDS_CLARIFICATION", result.status());
        assertEquals(1, result.candidates().size());
        FinanceMethodResolverModelService.MethodCandidate candidate = result.candidates().get(0);
        assertEquals("finance.growth.cagr", candidate.methodId());
        assertEquals("1.0.0", candidate.version());
        assertEquals("sha256:cagr", candidate.specDigest());
        assertEquals("这几年", candidate.unresolvedTerms().get(0));
        assertNotNull(result.resolverToolCallId());
        assertTrue(result.resolverToolCallId().startsWith("resolver-"));
    }

    @Test
    void resolve_shouldUseResolverToolCallIdFromContext() {
        AgentContext.setToolCallId("tool-call-resolver-7");
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"NO_ADVICE","candidates":[]}
                        """))
                .build());

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("tool-call-resolver-7", result.resolverToolCallId());
    }

    @Test
    void resolve_shouldFailOpenWhenNoRoute() {
        when(resolverModelResolver.resolve()).thenReturn(Optional.empty());
        FinanceMethodResolverModelService service = newService();

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
        assertEquals("NO_RESOLVER_ROUTE", result.unavailableReason());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldFailOpenWhenLlmThrows() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenThrow(new IllegalStateException("timeout"));

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
        assertTrue(result.unavailableReason().contains("RESOLVER_CALL_FAILED"));
        assertTrue(result.unavailableReason().contains("timeout"));
    }

    @Test
    void resolve_shouldFailOpenWhenBadJson() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("不是 JSON"))
                .build());

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
        assertTrue(result.unavailableReason().contains("RESOLVER_CALL_FAILED"));
    }

    @Test
    void resolve_shouldFailOpenWhenStatusInvalid() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"GUESS","candidates":[]}
                        """))
                .build());

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
    }

    @Test
    void resolve_shouldFailOpenWhenCandidateMissingFields() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"MATCHED","candidates":[{"methodId":"","version":"1.0.0","specDigest":"sha256:x","matchReason":"ok"}]}
                        """))
                .build());

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "catalog-text");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
    }

    @Test
    void resolve_shouldFailOpenWhenCatalogExceedsBudget() {
        llmProperties.getFinanceMethodResolver().setCatalogPromptMaxBytes(10);
        FinanceMethodResolverModelService service = newService();

        FinanceMethodResolverModelService.ResolutionResult result = service.resolve(
                "query", null, "this-catalog-text-is-longer-than-ten-bytes");

        assertEquals("RESOLVER_UNAVAILABLE", result.status());
        assertEquals("CATALOG_EXCEEDS_BYTES", result.unavailableReason());
        verify(model, never()).chat(anyList());
    }

    @Test
    void resolve_shouldSetStructuredOutputSpecAndRestoreContext() {
        FinanceMethodResolverModelService service = serviceWithModel();
        AgentContext.setPhase("old_phase");
        AgentContext.setStage("old_stage");
        AgentContext.setReasoningEffort("medium");
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"NO_ADVICE","candidates":[]}
                        """))
                .build());

        service.resolve("query", null, "catalog");

        assertEquals("old_phase", AgentContext.getPhase());
        assertEquals("old_stage", AgentContext.getStage());
        assertEquals("medium", AgentContext.getReasoningEffort());
        assertTrue(AgentContext.getStructuredOutputSpec() == null
                || AgentContext.getStructuredOutputSpec().schema().isEmpty());
    }

    @Test
    void resolve_shouldInjectCatalogIntoSystemPrompt() {
        FinanceMethodResolverModelService service = serviceWithModel();
        when(promptService.financeMethodResolverSystemPrompt("my-catalog"))
                .thenReturn("system prompt with catalog");
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"status":"NO_ADVICE","candidates":[]}
                        """))
                .build());

        service.resolve("query", null, "my-catalog");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        SystemMessage systemMessage = (SystemMessage) captor.getValue().get(0);
        assertTrue(systemMessage.text().contains("system prompt with catalog"));
    }

    private FinanceMethodResolverModelService serviceWithModel() {
        StageLlmConfig stage = new StageLlmConfig();
        stage.setEndpointName("resolver-endpoint");
        stage.setModelName("resolver-model");
        when(resolverModelResolver.resolve()).thenReturn(Optional.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stage, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "resolver-endpoint", "https://example.com/v1", "resolver-model", "key", "", List.of(), null);
        when(aiServiceFactory.resolveLlm("resolver-endpoint", "resolver-model")).thenReturn(resolved);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                any(), anyList(), anyDouble(), anyInt())).thenReturn(model);
        lenient().when(promptService.financeMethodResolverSystemPrompt(any()))
                .thenReturn("You are a financial method resolver.");
        return newService();
    }

    private FinanceMethodResolverModelService newService() {
        return new FinanceMethodResolverModelService(
                objectMapper, aiServiceFactory, resolverModelResolver, promptService, observabilityService, llmProperties);
    }
}
