package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.exception.RunStartedAtMissingException;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.entity.AgentRun;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AgentRunBudgetServiceTest {

    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    private AgentPromptService promptService;

    private AgentRunBudgetService service;
    private final VersionedModelTestFixture versioned = new VersionedModelTestFixture();

    @BeforeEach
    void setUp() {
        AgentLlmProperties llmProperties = new AgentLlmProperties();
        versioned.put("budgetLastMileStageInstruction",
                "[Stage: BUDGET_LAST_MILE] {{dimension}} {{actual}}/{{limit}} {{ratioPct}}%",
                "dimension", "actual", "limit", "ratioPct");
        promptService = versioned.promptService();
        service = new AgentRunBudgetService(
                stateStore, eventService, new ObjectMapper(), llmProperties, promptService);
        ReflectionTestUtils.setField(service, "localConfigLoader", localConfigLoader);
        ReflectionTestUtils.setField(service, "defaultMaxWallClockMs", 600000L);
        ReflectionTestUtils.setField(service, "defaultMaxLlmCalls", 50L);
        ReflectionTestUtils.setField(service, "defaultMaxToolCalls", 30L);
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 300000L);
        ReflectionTestUtils.setField(service, "defaultMaxHttpAttemptsPerLogicalCall", 3);
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

















    private void stubObservability(long toolCalls, long llmCalls, long totalTokens) {
        String json = """
                {"summary":{"startedAtMillis":%d,"toolCalls":%d,"llmCalls":%d,"totalTokens":%d}}
                """.formatted(System.currentTimeMillis(), toolCalls, llmCalls, totalTokens);
        lenient().when(stateStore.loadObservability("run-1")).thenReturn(Optional.of(json));
    }

    private void stubObservabilityWithStartedAtMillis(long startedAtMillis) {
        String json = """
                {"summary":{"startedAtMillis":%d,"toolCalls":0,"llmCalls":0,"totalTokens":0}}
                """.formatted(startedAtMillis);
        lenient().when(stateStore.loadObservability("run-1")).thenReturn(Optional.of(json));
    }
}
