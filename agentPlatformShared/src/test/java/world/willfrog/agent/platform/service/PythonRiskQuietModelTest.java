package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonRiskQuietModelTest {


    private AgentAiServiceFactory factory(RawHttpLogger rawLogger) {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        AgentAiServiceFactory factory = new AgentAiServiceFactory(
                mock(AgentLlmResolver.class), new AgentLlmProperties(), new ObjectMapper(),
                rawLogger, mock(AgentRunObservabilityService.class), mock(OpenRouterCostService.class),
                mock(AgentRunEventService.class), loader, mock(LangchainLlmLatencyWindow.class));
        ReflectionTestUtils.setField(factory, "openAiApiKey", "test-key");
        ReflectionTestUtils.setField(factory, "maxTokens", 256);
        ReflectionTestUtils.setField(factory, "temperature", 0.0D);
        return factory;
    }
}
