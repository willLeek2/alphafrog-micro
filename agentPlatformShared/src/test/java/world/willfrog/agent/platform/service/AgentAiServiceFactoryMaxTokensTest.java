package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class AgentAiServiceFactoryMaxTokensTest {





    private static AgentAiServiceFactory newFactory() {
        AgentAiServiceFactory factory = new AgentAiServiceFactory(
                mock(AgentLlmResolver.class),
                mock(AgentLlmProperties.class),
                new ObjectMapper(),
                mock(RawHttpLogger.class),
                mock(AgentRunObservabilityService.class),
                mock(OpenRouterCostService.class),
                mock(AgentRunEventService.class),
                mock(AgentLlmLocalConfigLoader.class),
                mock(LangchainLlmLatencyWindow.class)
        );
        ReflectionTestUtils.setField(factory, "openAiApiKey", "test-key");
        ReflectionTestUtils.setField(factory, "maxTokens", 4096);
        ReflectionTestUtils.setField(factory, "temperature", 0.7D);
        ReflectionTestUtils.setField(factory, "openRouterHttpReferer", "https://example.com");
        ReflectionTestUtils.setField(factory, "openRouterTitle", "test");
        return factory;
    }
}
