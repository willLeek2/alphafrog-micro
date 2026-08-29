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

    @Test
    void spawnWaitAndReplayUseDurableStateAndSharedRunBudget() throws Exception {
        AtomicInteger runToolCalls = new AtomicInteger(2);
        when(todoExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AtomicInteger shared = invocation.getArgument(4);
            assertEquals(runToolCalls, shared, "子代理必须复用主 Run 的工具调用计数器");
            shared.incrementAndGet();
            return LangchainTodoNodeResult.success("child answer", 1);
        });

        try (LangchainSubAgentExecutionContext.Scope ignored = install("run-1", "call-1", runToolCalls)) {
            JsonNode spawned = json(service.spawn(Map.of("goal", "check a fact")));
            String id = spawned.path("data").path("subAgentId").asText();
            assertTrue(spawned.path("ok").asBoolean());
            assertEquals("run-1", spawned.path("data").path("runId").asText());
            assertFalse(id.isBlank());

            JsonNode waited = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id),
                    "timeoutMillis", 5_000)));
            assertTrue(waited.path("ok").asBoolean());
            assertEquals("SUCCEEDED", waited.path("data").path("results").get(0).path("status").asText());
            assertEquals("child answer", waited.path("data").path("results").get(0).path("result").asText());
            assertEquals(3, runToolCalls.get());

            JsonNode replay = json(service.spawn(Map.of("goal", "check a fact")));
            assertTrue(replay.path("ok").asBoolean());
            assertEquals(id, replay.path("data").path("subAgentId").asText());
        }

        verify(todoExecutor).execute(any(), any(), any(), any(), any());
    }

    @Test
    void waitTimeoutDoesNotCancelChildAndLaterWaitCollectsTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(todoExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timeout");
            }
            return LangchainTodoNodeResult.success("late answer", 0);
        });

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-timeout", "call-timeout", new AtomicInteger())) {
            String id = json(service.spawn(Map.of("goal", "slow child")))
                    .path("data").path("subAgentId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            JsonNode firstWait = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id), "timeoutMillis", 1)));
            assertTrue(firstWait.path("ok").asBoolean());
            assertEquals("WAIT_TIMEOUT",
                    firstWait.path("data").path("results").get(0).path("status").asText());

            release.countDown();
            JsonNode secondWait = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id), "timeoutMillis", 5_000)));
            assertEquals("SUCCEEDED",
                    secondWait.path("data").path("results").get(0).path("status").asText());
            assertEquals("late answer",
                    secondWait.path("data").path("results").get(0).path("result").asText());
        }
    }

    @Test
    void childCannotRecurseAndAnotherRunCannotCollectId() throws Exception {
        when(todoExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("done", 0));
        String id;
        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-owner", "call-owner", new AtomicInteger())) {
            id = json(service.spawn(Map.of("goal", "owned child")))
                    .path("data").path("subAgentId").asText();
            json(service.waitFor(Map.of("subAgentIds", List.of(id), "timeoutMillis", 5_000)));
        }

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-other", "call-other", new AtomicInteger())) {
            JsonNode foreign = json(service.waitFor(Map.of("subAgentIds", List.of(id))));
            assertFalse(foreign.path("ok").asBoolean());
            assertEquals("SUB_AGENT_NOT_FOUND", foreign.path("error").path("code").asText());

            AgentContext.setPhase(world.willfrog.agent.platform.service.AgentRunObservabilityService.PHASE_SUB_AGENT);
            JsonNode recursive = json(service.spawn(Map.of("goal", "nested")));
            assertFalse(recursive.path("ok").asBoolean());
            assertEquals("SUB_AGENT_RECURSION_FORBIDDEN",
                    recursive.path("error").path("code").asText());
        }
    }

    @Test
    void parentCancellationStopsChildEvenWhenParentDoesNotCallWait() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(executionGuard.stopReason(anyString(), anyString())).thenAnswer(invocation ->
                stopped.get() ? Optional.of("CANCELED") : Optional.empty());
        when(todoExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("child interrupted", expected);
            }
        });

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-cancel", "call-cancel", new AtomicInteger())) {
            String id = json(service.spawn(Map.of("goal", "cancel me")))
                    .path("data").path("subAgentId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            stopped.set(true);

            assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                    "主 Run 取消后，不调用 wait 也必须中断在途子代理");
            stopped.set(false);
            JsonNode terminal = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id), "timeoutMillis", 5_000)));
            assertEquals("CANCELED",
                    terminal.path("data").path("results").get(0).path("status").asText());
        }
    }

    @Test
    void unfinishedAcceptedEventWithoutLiveHandleFailsAsRecoveryUnsupported() throws Exception {
        String id = "sa_previous_process";
        AgentRunEvent accepted = new AgentRunEvent();
        accepted.setRunId("run-restart");
        accepted.setEventType(LangchainSubAgentLifecycleService.ACCEPTED_EVENT);
        accepted.setDedupeKey("sub-agent:" + id + ":accepted");
        accepted.setPayloadJson(objectMapper.writeValueAsString(Map.of(
                "subAgentId", id,
                "status", "ACCEPTED")));
        durableEvents.put(key("run-restart", accepted.getDedupeKey()), accepted);

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-restart", "call-restart", new AtomicInteger())) {
            JsonNode response = json(service.waitFor(Map.of("subAgentIds", List.of(id))));
            assertFalse(response.path("ok").asBoolean());
            assertEquals("SUB_AGENT_RECOVERY_UNSUPPORTED",
                    response.path("error").path("code").asText());
            assertEquals("RECOVERY_UNSUPPORTED",
                    response.path("error").path("details").path("results").get(0)
                            .path("status").asText());
        }
    }

    @Test
    void spawnRequiresStableToolCallIdInsteadOfCreatingAnUnreplayableRandomId() throws Exception {
        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-no-tool-call", "", new AtomicInteger())) {
            JsonNode response = json(service.spawn(Map.of("goal", "must be replayable")));

            assertFalse(response.path("ok").asBoolean());
            assertEquals("SUB_AGENT_TOOL_CALL_ID_REQUIRED",
                    response.path("error").path("code").asText());
            assertTrue(durableEvents.isEmpty());
        }
    }

    @Test
    void waitRejectsTooManyIdsInsteadOfSilentlyDroppingTheRemainder() throws Exception {
        when(promptService.maxSubAgentCount()).thenReturn(2);
        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-too-many", "call-too-many", new AtomicInteger())) {
            JsonNode response = json(service.waitFor(Map.of(
                    "subAgentIds", List.of("sa_1", "sa_2", "sa_3"))));

            assertFalse(response.path("ok").asBoolean());
            assertEquals("SUB_AGENT_IDS_LIMIT_EXCEEDED",
                    response.path("error").path("code").asText());
            assertEquals(3, response.path("error").path("details").path("provided").asInt());
            assertEquals(2, response.path("error").path("details").path("max").asInt());
        }
    }

    @Test
    void spawnEnforcesTheConfiguredActiveChildLimitFromDurableState() throws Exception {
        when(promptService.maxSubAgentCount()).thenReturn(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(todoExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timeout");
            }
            return LangchainTodoNodeResult.success("done", 0);
        });

        String firstId;
        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-limit", "call-first", new AtomicInteger())) {
            JsonNode first = json(service.spawn(Map.of("goal", "first child")));
            assertTrue(first.path("ok").asBoolean());
            firstId = first.path("data").path("subAgentId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-limit", "call-second", new AtomicInteger())) {
            JsonNode second = json(service.spawn(Map.of("goal", "second child")));
            assertFalse(second.path("ok").asBoolean());
            assertEquals("SUB_AGENT_LIMIT_EXCEEDED",
                    second.path("error").path("code").asText());
        }

        release.countDown();
        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-limit", "call-wait", new AtomicInteger())) {
            JsonNode terminal = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(firstId), "timeoutMillis", 5_000)));
            assertEquals("SUCCEEDED",
                    terminal.path("data").path("results").get(0).path("status").asText());
        }
    }

    @Test
    void taskTimeoutPersistsTimeoutAndInterruptsTheChild() throws Exception {
        ReflectionTestUtils.setField(service, "taskTimeoutMillis", 50L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(todoExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("child interrupted", expected);
            }
        });

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-task-timeout", "call-task-timeout", new AtomicInteger())) {
            String id = json(service.spawn(Map.of("goal", "must time out")))
                    .path("data").path("subAgentId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            JsonNode terminal = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id), "timeoutMillis", 5_000)));
            assertEquals("TIMEOUT",
                    terminal.path("data").path("results").get(0).path("status").asText());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void schedulingRejectionPersistsFailureAndCancelsAlreadySubmittedChild() throws Exception {
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        executor.submit(() -> {
            executorOccupied.countDown();
            try {
                releaseExecutor.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(executorOccupied.await(5, TimeUnit.SECONDS));
        timeoutScheduler.shutdownNow();

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-scheduler-rejected", "call-scheduler-rejected", new AtomicInteger())) {
            JsonNode response = json(service.spawn(Map.of("goal", "must not become an orphan")));

            assertFalse(response.path("ok").asBoolean());
            assertEquals("SUB_AGENT_EXECUTOR_BUSY", response.path("error").path("code").asText());
            releaseExecutor.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            verify(todoExecutor, org.mockito.Mockito.never()).execute(any(), any(), any(), any(), any());
        }
    }

    @Test
    void terminalPersistenceFailureReturnsExplicitFailureInsteadOfHangingWait() throws Exception {
        when(todoExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("not durably committed", 0));
        doAnswer(invocation -> {
            String eventType = invocation.getArgument(2);
            if (LangchainSubAgentLifecycleService.TERMINAL_EVENT.equals(eventType)) {
                throw new IllegalStateException("database unavailable");
            }
            String runId = invocation.getArgument(0);
            String dedupeKey = invocation.getArgument(3);
            Object payload = invocation.getArgument(4);
            AgentRunEvent event = new AgentRunEvent();
            event.setRunId(runId);
            event.setEventType(eventType);
            event.setDedupeKey(dedupeKey);
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
            return durableEvents.putIfAbsent(key(runId, dedupeKey), event) == null;
        }).when(eventService).appendOnce(anyString(), anyString(), anyString(), anyString(), any());

        try (LangchainSubAgentExecutionContext.Scope ignored = install(
                "run-terminal-write-fails", "call-terminal-write-fails", new AtomicInteger())) {
            String id = json(service.spawn(Map.of("goal", "finish honestly")))
                    .path("data").path("subAgentId").asText();
            JsonNode response = json(service.waitFor(Map.of(
                    "subAgentIds", List.of(id), "timeoutMillis", 5_000)));

            assertTrue(response.path("ok").asBoolean());
            JsonNode state = response.path("data").path("results").get(0);
            assertEquals("FAILED", state.path("status").asText());
            assertEquals("SUB_AGENT_TERMINAL_PERSISTENCE_FAILED", state.path("error").asText());
        }
    }

    private LangchainSubAgentExecutionContext.Scope install(String runId,
                                                            String toolCallId,
                                                            AtomicInteger runToolCalls) {
        AgentContext.clear();
        AgentContext.setRunId(runId);
        AgentContext.setUserId("user-1");
        AgentContext.setToolCallId(toolCallId);
        LangchainLinearWorkflowRequest request = LangchainLinearWorkflowRequest.builder()
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
