package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeChatModelTest {











    // ==================== structured output 禁 thinking 回归测试 ====================







    private static boolean invokeSupportsThinking(DashScopeChatModel model, String name) {
        Boolean result = ReflectionTestUtils.invokeMethod(model, "supportsThinking", name);
        return Boolean.TRUE.equals(result);
    }

    private DashScopeChatModel newModel(String modelName, boolean enableThinking) {
        return newModel(modelName, enableThinking, mock(AgentRunEventService.class));
    }

    private DashScopeChatModel newModel(String modelName, boolean enableThinking, AgentRunEventService eventService) {
        return newModel(modelName, enableThinking, eventService, mock(AgentLlmLocalConfigLoader.class));
    }

    private DashScopeChatModel newModel(String modelName,
                                        boolean enableThinking,
                                        AgentRunEventService eventService,
                                        AgentLlmLocalConfigLoader localConfigLoader) {
        return new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                modelName,
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                mock(AgentRunObservabilityService.class),
                "dashscope",
                enableThinking,
                localConfigLoader,
                eventService,
                mock(LangchainLlmLatencyWindow.class)
        );
    }

    private AgentLlmLocalConfigLoader mockStructuredOutputLoader(Boolean enabled) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Planning planning = new AgentLlmProperties.Planning();
        AgentLlmProperties.StructuredOutput structuredOutput = new AgentLlmProperties.StructuredOutput();
        structuredOutput.setEnabled(enabled);
        planning.setStructuredOutput(structuredOutput);
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(properties));
        return loader;
    }
}
