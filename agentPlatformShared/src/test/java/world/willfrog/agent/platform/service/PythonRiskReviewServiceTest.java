package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.dataanalysis.PythonRiskDecisionRow;
import world.willfrog.agent.platform.mapper.PythonRiskDecisionMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonRiskReviewServiceTest {
    private final AgentLlmProperties base = new AgentLlmProperties();
    private final AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
    private final AgentLlmResolver resolver = mock(AgentLlmResolver.class);
    private final AgentAiServiceFactory factory = mock(AgentAiServiceFactory.class);
    private final PythonRiskDecisionMapper mapper = mock(PythonRiskDecisionMapper.class);
    private final ObjectMapper json = new ObjectMapper();

    private PythonRiskReviewService subject(AgentLlmProperties snapshot) {
        when(loader.hotConfigIsAuthoritative()).thenReturn(true);
        when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(snapshot, Set.of("agent")));
        return new PythonRiskReviewService(base, loader, resolver, factory, mapper, json);
    }

    @Test
    void disabledFirstCallPersistsPassWithoutCallingModelThenReusesOldDecision() {
        AgentLlmProperties snapshot = new AgentLlmProperties();
        AtomicReference<PythonRiskDecisionRow> stored = storeInsertedDecision();
        PythonRiskReviewService subject = subject(snapshot);

        PythonRiskReviewService.Evaluation first = subject.evaluate("run:tool:1", "run", "print(1)");
        assertTrue(first.allowed());
        assertFalse(first.reused());
        assertNull(first.riskScore());
        assertFalse(stored.get().getConfigJson().contains("print(1)"));
        verifyNoInteractions(resolver, factory);

        snapshot.getAgent().getPythonRiskReview().setEnabled(true);
        PythonRiskReviewService.Evaluation replay = subject.evaluate("run:tool:1", "run", "print(1)");
        assertTrue(replay.allowed());
        assertTrue(replay.reused());
        verifyNoInteractions(resolver, factory);
        verify(mapper, times(1)).insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void enabledReviewUsesFrozenRouteAndRejectsScoreAboveThreshold() {
        AgentLlmProperties snapshot = enabledConfig(50);
        ChatModel model = mock(ChatModel.class);
        when(resolver.resolveFromSnapshot(eq("cheap"), eq("small-model"), same(snapshot)))
                .thenReturn(route());
        when(factory.buildPythonRiskReviewModel(any()))
                .thenReturn(model);
        when(model.chat(any(List.class))).thenReturn(response("{\"riskScore\":51}"));
        AtomicReference<PythonRiskDecisionRow> stored = storeInsertedDecision();

        PythonRiskReviewService.Evaluation decision = subject(snapshot)
                .evaluate("run:tool:1", "run", "open('x','w').write('x')");

        assertFalse(decision.allowed());
        assertEquals(51, decision.riskScore());
        assertEquals("REJECT", stored.get().getDecision());
        assertFalse(stored.get().getConfigJson().contains("open('x'"));
        verify(model).chat(org.mockito.Mockito.<List<dev.langchain4j.data.message.ChatMessage>>argThat(
                messages -> messages.size() == 2
                && messages.get(1) instanceof dev.langchain4j.data.message.UserMessage user
                && user.singleText().equals("open('x','w').write('x')")));
    }

    @Test
    void thresholdScorePassesAndInvalidResponsesNeverPersistDecision() {
        AgentLlmProperties snapshot = enabledConfig(50);
        ChatModel model = mock(ChatModel.class);
        when(resolver.resolveFromSnapshot(anyString(), anyString(), same(snapshot)))
                .thenReturn(route());
        when(factory.buildPythonRiskReviewModel(any()))
                .thenReturn(model);
        when(model.chat(any(List.class))).thenReturn(response("{\"riskScore\":50}"));
        storeInsertedDecision();
        assertTrue(subject(snapshot).evaluate("run:ok:1", "run", "pass").allowed());

        for (String invalid : List.of("{\"riskScore\":50.0}", "{\"riskScore\":101}",
                "{\"riskScore\":20,\"reason\":\"ok\"}", "{\"riskScore\":2} {}", "not json")) {
            reset(mapper);
            when(model.chat(any(List.class))).thenReturn(response(invalid));
            assertThrows(PythonRiskReviewService.Unavailable.class,
                    () -> subject(snapshot).evaluate("run:invalid:1", "run", "pass"));
            verify(mapper, never()).insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString());
        }
    }

    @Test
    void duplicateRiskScoreKeysCannotTurnRejectIntoPass() {
        AgentLlmProperties snapshot = enabledConfig(50);
        ChatModel model = mock(ChatModel.class);
        when(resolver.resolveFromSnapshot(anyString(), anyString(), same(snapshot)))
                .thenReturn(route());
        when(factory.buildPythonRiskReviewModel(any())).thenReturn(model);
        when(model.chat(any(List.class))).thenReturn(response("{\"riskScore\":100,\"riskScore\":0}"));

        assertThrows(PythonRiskReviewService.Unavailable.class,
                () -> subject(snapshot).evaluate("run:duplicate:1", "run", "pass"));
        verify(mapper, never()).insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void modelFailureAndStorageFailureDoNotBecomePass() {
        AgentLlmProperties snapshot = enabledConfig(50);
        ChatModel model = mock(ChatModel.class);
        when(resolver.resolveFromSnapshot(anyString(), anyString(), same(snapshot)))
                .thenReturn(route());
        when(factory.buildPythonRiskReviewModel(any()))
                .thenReturn(model);
        when(model.chat(any(List.class))).thenThrow(new RuntimeException("timeout"));
        assertThrows(PythonRiskReviewService.Unavailable.class,
                () -> subject(snapshot).evaluate("run:timeout:1", "run", "pass"));
        verify(mapper, never()).insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString());

        reset(model);
        when(model.chat(any(List.class))).thenReturn(response("{\"riskScore\":0}"));
        when(mapper.insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("db unavailable"));
        assertThrows(PythonRiskReviewService.Unavailable.class,
                () -> subject(snapshot).evaluate("run:db:1", "run", "pass"));
    }

    private AgentLlmProperties enabledConfig(int threshold) {
        AgentLlmProperties config = new AgentLlmProperties();
        AgentLlmProperties.PythonRiskReview review = config.getAgent().getPythonRiskReview();
        review.setEnabled(true);
        review.setEndpointName("cheap");
        review.setModelName("small-model");
        review.setThreshold(threshold);
        return config;
    }

    private AtomicReference<PythonRiskDecisionRow> storeInsertedDecision() {
        AtomicReference<PythonRiskDecisionRow> stored = new AtomicReference<>();
        when(mapper.findByOperation(anyString())).thenAnswer(call -> stored.get());
        when(mapper.insertIfAbsent(anyString(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(call -> {
                    PythonRiskDecisionRow row = new PythonRiskDecisionRow();
                    row.setOperationId(call.getArgument(0));
                    row.setRunId(call.getArgument(1));
                    row.setDecision(call.getArgument(2));
                    row.setRiskScore(call.getArgument(3));
                    row.setConfigJson(call.getArgument(4));
                    stored.set(row);
                    return 1;
                });
        return stored;
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private static AgentLlmResolver.ResolvedLlm route() {
        return new AgentLlmResolver.ResolvedLlm("cheap", "https://example.invalid", "small-model",
                "test-key", null, List.of(), 256);
    }
}
