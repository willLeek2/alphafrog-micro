package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunObservabilityServiceCacheTest {

    private AgentRunObservabilityService service;
    private ObjectMapper objectMapper;

    @Mock
    private AgentRunStateStore stateStore;

    @Mock
    private AgentObservabilityDebugFileWriter debugFileWriter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentRunObservabilityService(stateStore, objectMapper, debugFileWriter);
        ReflectionTestUtils.setField(service, "llmTraceEnabled", true);
        ReflectionTestUtils.setField(service, "llmTraceMaxCalls", 100);
        ReflectionTestUtils.setField(service, "llmTraceMaxTextChars", 20000);
        ReflectionTestUtils.setField(service, "captureCachedTokens", true);
        ReflectionTestUtils.setField(service, "llmTraceReasoningMaxChars", 20000);
    }








}
