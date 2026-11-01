package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import world.willfrog.agent.platform.context.AgentContext;
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
    private final VersionedModelTestFixture versioned = new VersionedModelTestFixture();

    @AfterEach
    void clearContext() { AgentContext.clear(); }

    private PythonRiskReviewService subject(AgentLlmProperties snapshot) {
        when(loader.hotConfigIsAuthoritative()).thenReturn(true);
        when(loader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(snapshot, Set.of("agent")));
        AgentContext.setRunId("run");
        versioned.putImported("pythonRiskReviewSystemPrompt");
        var service = new PythonRiskReviewService(base, loader, resolver, factory, mapper, json);
        versioned.inject(service);
        return service;
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
