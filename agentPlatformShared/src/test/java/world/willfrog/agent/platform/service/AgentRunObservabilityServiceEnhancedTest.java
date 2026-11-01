package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agent.platform.service.AgentCallDetailPersistence.OBSERVABILITY_PREVIEW_MAX_CHARS;

import org.mockito.ArgumentCaptor;

/**
 * 可观测性增强测试：验证 5.2~5.5 的核心改动。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunObservabilityServiceEnhancedTest {

    private AgentRunObservabilityService service;
    private ObjectMapper objectMapper;

    @Mock
    private AgentRunStateStore stateStore;

    @Mock
    private AgentObservabilityDebugFileWriter debugFileWriter;

    private AtomicReference<String> savedJson;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentRunObservabilityService(stateStore, objectMapper, debugFileWriter);
        ReflectionTestUtils.setField(service, "llmTraceEnabled", true);
        ReflectionTestUtils.setField(service, "llmTraceMaxCalls", 100);
        ReflectionTestUtils.setField(service, "llmTraceMaxTextChars", 20000);
        ReflectionTestUtils.setField(service, "captureCachedTokens", true);
        ReflectionTestUtils.setField(service, "llmTraceReasoningMaxChars", 20000);
        ReflectionTestUtils.setField(service, "toolTraceMaxOutputChars", 100000);

        savedJson = new AtomicReference<>();
        AgentContext.clear();
    }

    private void setupStateStore(String runId) {
        when(stateStore.loadObservability(eq(runId))).thenAnswer(inv -> {
            String json = savedJson.get();
            return json == null ? Optional.empty() : Optional.of(json);
        });
        doAnswer(inv -> {
            savedJson.set(inv.getArgument(1));
            return null;
        }).when(stateStore).saveObservability(eq(runId), anyString());
    }


    private static void assertPromptSelection(AgentRunObservabilityService.LlmTrace trace) {
        assertEquals("default-v1", trace.getPromptBundleVersion());
        assertEquals("control", trace.getPromptVariant());
        assertEquals("bundle-digest", trace.getPromptBundleDigest());
        assertEquals("capability-digest", trace.getPromptCapabilityCatalogDigest());
    }

    // ==================== 5.2 LLM inputMessages / outputText ====================







    // ==================== 5.3 DAG 节点 ID 写入 LlmTrace ====================



    // ==================== 5.4 工具输出使用独立配置限制 ====================








    // ==================== ToolTrace backward compat ====================


    // ==================== LlmTrace without TodoContext ====================

}
