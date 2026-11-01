package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

class LangchainSubAgentLifecycleServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, AgentRunEvent> durableEvents = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private ScheduledExecutorService timeoutScheduler;
    private LangchainTodoNodeExecutor todoExecutor;
    private LangchainRunExecutionGuard executionGuard;
    private AgentRunEventService eventService;
    private AgentPromptService promptService;
    private LangchainSubAgentLifecycleService service;

    @BeforeEach
    void setUp() throws Exception {
        todoExecutor = mock(LangchainTodoNodeExecutor.class);
        executionGuard = mock(LangchainRunExecutionGuard.class);
        eventService = mock(AgentRunEventService.class);
        promptService = mock(AgentPromptService.class);
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties llmProperties = new AgentLlmProperties();
        AgentLlmProperties.Execution execution = llmProperties.getRuntime().getExecution();
        execution.setMaxToolCallsPerSubAgent(4);

        when(executionGuard.stopReason(anyString(), anyString())).thenReturn(Optional.empty());
        when(promptService.subAgentEnabled()).thenReturn(true);
        when(promptService.maxSubAgentCount()).thenReturn(3);
        when(promptService.maxSubAgentSteps()).thenReturn(6);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String runId = invocation.getArgument(0);
            String eventType = invocation.getArgument(2);
            String dedupeKey = invocation.getArgument(3);
            Object payload = invocation.getArgument(4);
            AgentRunEvent event = new AgentRunEvent();
            event.setRunId(runId);
            event.setEventType(eventType);
            event.setDedupeKey(dedupeKey);
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
            return durableEvents.putIfAbsent(key(runId, dedupeKey), event) == null;
        }).when(eventService).appendOnce(anyString(), anyString(), anyString(), anyString(), any());
        when(eventService.findByDedupeKey(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(durableEvents.get(key(
                        invocation.getArgument(0), invocation.getArgument(1)))));
        when(eventService.listByRunId(anyString())).thenAnswer(invocation -> {
            String runId = invocation.getArgument(0);
            List<AgentRunEvent> events = new ArrayList<>();
            durableEvents.values().stream()
                    .filter(event -> runId.equals(event.getRunId()))
                    .forEach(events::add);
            return events;
        });

        executor = Executors.newSingleThreadExecutor();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        service = new LangchainSubAgentLifecycleService(
                todoExecutor,
                executionGuard,
                eventService,
                promptService,
                llmProperties,
                localConfigLoader,
                objectMapper,
                executor,
                timeoutScheduler);
        ReflectionTestUtils.setField(service, "maxWaitMillis", 5_000L);
        ReflectionTestUtils.setField(service, "defaultWaitMillis", 100L);
        ReflectionTestUtils.setField(service, "taskTimeoutMillis", 10_000L);
        ReflectionTestUtils.setField(service, "resultMaxChars", 4_096);
    }

    @AfterEach
    void tearDown() throws Exception {
        AgentContext.clear();
        executor.shutdownNow();
        timeoutScheduler.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS));
    }












    private LangchainSubAgentExecutionContext.Scope install(String runId,
                                                            String toolCallId,
                                                            AtomicInteger runToolCalls) {
        AgentContext.clear();
        AgentContext.setRunId(runId);
        AgentContext.setUserId("user-1");
        AgentContext.setToolCallId(toolCallId);
        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId(runId)
                .userId("user-1")
                .userGoal("parent goal")
                .model(mock(ChatModel.class))
                .toolSpecifications(List.of())
                .build();
        return LangchainSubAgentExecutionContext.install(request, Map.of("1", "dataset-1"), runToolCalls);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private static String key(String runId, String dedupeKey) {
        return runId + "\n" + dedupeKey;
    }
}
