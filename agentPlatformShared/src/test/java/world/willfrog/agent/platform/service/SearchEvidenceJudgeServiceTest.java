package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.config.StageLlmConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

class SearchEvidenceJudgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentAiServiceFactory aiServiceFactory = mock(AgentAiServiceFactory.class);
    private final SearchEvidenceJudgeModelResolver judgeModelResolver = mock(SearchEvidenceJudgeModelResolver.class);
    private final JudgeModelSelectorService selectorService = mock(JudgeModelSelectorService.class);
    private final AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
    private final ChatModel model = mock(ChatModel.class);
    private final VersionedModelTestFixture versioned = new VersionedModelTestFixture();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }








    private SearchEvidenceJudgeService serviceWithModel() {
        SearchEvidenceJudgeService service = newService();
        StageLlmConfig stage = new StageLlmConfig();
        stage.setEndpointName("cheap-judge");
        stage.setModelName("small-model");
        when(judgeModelResolver.resolve()).thenReturn(Optional.of(
                new SearchEvidenceJudgeModelResolver.ResolvedStageModel(
                        stage, SearchEvidenceJudgeModelResolver.ModelSource.SEARCH_JUDGE)));
        var resolved = new AgentLlmResolver.ResolvedLlm(
                "cheap-judge", "https://example.com/v1", "small-model", "key", "", List.of(), null);
        when(aiServiceFactory.resolveLlm("cheap-judge", "small-model")).thenReturn(resolved);
        when(aiServiceFactory.buildArchivedChatModelWithProviderOrderAndTemperature(resolved, List.of(), 0.0D, SearchEvidenceJudgeService.JUDGE_MAX_TOKENS))
                .thenReturn(versioned.archived(model, "cheap-judge"));
        return service;
    }

    private SearchEvidenceJudgeService newService() {
        versioned.putImported("searchEvidenceJudgeSystemPrompt");
        var service = new SearchEvidenceJudgeService(
                objectMapper, aiServiceFactory, judgeModelResolver, selectorService, observabilityService);
        versioned.inject(service);
        return service;
    }

    private Map<String, Object> hit(String title, String snippet, String url) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("snippet", snippet);
        row.put("url", url);
        return row;
    }

    private Map<String, Object> citation(String title, String url) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("url", url);
        return row;
    }
}
