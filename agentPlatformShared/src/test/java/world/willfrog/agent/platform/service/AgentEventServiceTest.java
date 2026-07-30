package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.workflow.PlanExecutionMode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEventServiceTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventMapper eventMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private AgentLlmLocalConfigLoader llmLocalConfigLoader;
    @Mock
    private AgentMessageService messageService;
    @Mock
    private AgentRunEventRedisStore eventRedisStore;
    @Mock
    private AgentPromptService mockPromptService;

    private AgentEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentEventService(
                runMapper,
                eventMapper,
                eventRedisStore,
                objectMapper,
                redisTemplate,
                llmLocalConfigLoader,
                messageService,
                mockPromptService
        );
    }

    @Test
    void append_shouldPublishLiveEventAfterInsert() throws Exception {
        AgentRun run = run("r1", "u1");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("agent:run:event_seq:r1")).thenReturn(7L);
        when(eventMapper.insert(any())).thenReturn(1);

        service.append("r1", "u1", "PLAN_READY", Map.of("ok", true));

        verify(eventRedisStore).append(any());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("agent:events:r1"), payloadCaptor.capture());
        Map<?, ?> envelope = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertEquals("r1", envelope.get("runId"));
        assertEquals(7, ((Number) envelope.get("seq")).intValue());
        assertEquals("PLAN_READY", envelope.get("eventType"));
        assertTrue(String.valueOf(envelope.get("payloadJson")).contains("\"ok\":true"));
        assertTrue(String.valueOf(envelope.get("createdAt")).contains("T"));
    }

    @Test
    void append_shouldNotFailWhenLivePublishFails() {
        AgentRun run = run("r1", "u1");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("agent:run:event_seq:r1")).thenReturn(1L);
        when(eventMapper.insert(any())).thenReturn(1);
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate)
                .convertAndSend(anyString(), anyString());

        Assertions.assertDoesNotThrow(() -> service.append("r1", "u1", "PLAN_READY", Map.of("ok", true)));
    }

    @Test
    void appendOnce_shouldPersistAndPublishFirstLogicalEvent() {
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run("r1", "u1"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("agent:run:event_seq:r1")).thenReturn(8L);
        when(eventMapper.insertOnce(any())).thenReturn(1);

        boolean inserted = service.appendOnce(
                "r1", "u1", "TOOL_CALL_FINISHED", "r1:tc1:logical_terminal", Map.of("success", true));

        assertTrue(inserted);
        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertOnce(eventCaptor.capture());
        assertEquals("r1:tc1:logical_terminal", eventCaptor.getValue().getDedupeKey());
        assertEquals(8, eventCaptor.getValue().getSeq());
        verify(eventRedisStore).append(eventCaptor.getValue());
        verify(eventRedisStore).flush("r1");
        verify(redisTemplate).convertAndSend(eq("agent:events:r1"), anyString());
    }

    @Test
    void appendOnce_shouldHealRedisWithoutRepublishingDuplicate() {
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run("r1", "u1"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("agent:run:event_seq:r1")).thenReturn(9L);
        when(eventMapper.insertOnce(any())).thenReturn(0);
        AgentRunEvent persisted = new AgentRunEvent();
        persisted.setRunId("r1");
        persisted.setSeq(4);
        persisted.setEventType("TOOL_CALL_FINISHED");
        persisted.setDedupeKey("r1:tc1:logical_terminal");
        persisted.setPayloadJson("{\"success\":true}");
        when(eventMapper.findByRunIdAndDedupeKey("r1", "r1:tc1:logical_terminal")).thenReturn(persisted);

        boolean inserted = service.appendOnce(
                "r1", "u1", "TOOL_CALL_FINISHED", "r1:tc1:logical_terminal", Map.of("success", true));

        assertFalse(inserted);
        verify(eventRedisStore).append(persisted);
        verify(eventRedisStore, never()).flush(anyString());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void extractRunConfig_shouldParseNestedContextConfig() throws Exception {
        String contextJson = objectMapper.writeValueAsString(Map.of(
                "config", Map.of(
                        "webSearch", Map.of(
                                "enabled", true,
                                "backend", "exa",
                                "strength", "fast",
                                "skipHotCache", true,
                                "skipRagPrefetch", true,
                                "maxResults", 6
                        ),
                        "codeInterpreter", Map.of(
                                "enabled", false,
                                "maxCredits", 128
                        ),
                        "smartRetrieval", Map.of("enabled", true)
                )
        ));
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", contextJson));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertTrue(config.webSearchEnabled());
        assertEquals("exa", config.webSearchConfig().backend());
        assertEquals("fast", config.webSearchConfig().strength());
        assertTrue(config.webSearchConfig().skipHotCache());
        assertTrue(config.webSearchConfig().skipRagPrefetch());
        assertEquals(6, config.webSearchConfig().maxResults());
        assertFalse(config.codeInterpreterEnabled());
        assertEquals(128, config.codeInterpreterMaxCredits());
        assertTrue(config.smartRetrievalEnabled());
    }

    @Test
    void extractRunConfig_shouldFallbackToCompatibleDefaultsWhenConfigMissing() throws Exception {
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", "{}"));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertFalse(config.webSearchEnabled());
        assertTrue(config.codeInterpreterEnabled());
        assertEquals(0, config.codeInterpreterMaxCredits());
        assertFalse(config.smartRetrievalEnabled());
    }

    @Test
    void extractRunConfig_shouldFallbackToDefaultsWhenContextJsonMalformed() throws Exception {
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", "{broken-json"));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertFalse(config.webSearchEnabled());
        assertTrue(config.codeInterpreterEnabled());
        assertEquals(0, config.codeInterpreterMaxCredits());
        assertFalse(config.smartRetrievalEnabled());
    }

    @Test
    void extractExecutionMode_shouldNormalizeAliasesAndHonorSnakeCasePrecedence() throws Exception {
        assertEquals("AUTO", service.extractExecutionMode("{}"));
        assertEquals(PlanExecutionMode.AUTO, service.extractPlanExecutionMode("{}"));
        assertEquals("LINEAR", service.extractExecutionMode(objectMapper.writeValueAsString(Map.of(
                "execution_mode", "  linear  "))));
        assertEquals("DAG", service.extractExecutionMode(objectMapper.writeValueAsString(Map.of(
                "executionMode", "dAg"))));
        assertEquals("LINEAR", service.extractExecutionMode(objectMapper.writeValueAsString(Map.of(
                "execution_mode", "LINEAR",
                "executionMode", "DAG"))));
    }

    @Test
    void extractExecutionMode_shouldRejectUnsupportedValue() throws Exception {
        String ext = objectMapper.writeValueAsString(Map.of("execution_mode", "parallel"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.extractPlanExecutionMode(ext));

        assertEquals("unsupported_execution_mode:PARALLEL", error.getMessage());
    }

    @Test
    void createRun_shouldRejectUnsupportedExecutionModeBeforeInsert() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createRun(
                        "u-invalid",
                        "hello",
                        "{\"execution_mode\":\"parallel\"}",
                        "idem",
                        "m",
                        "e",
                        false,
                        "openrouter",
                        2,
                        false,
                        "{}",
                        false));

        assertEquals("unsupported_execution_mode:PARALLEL", error.getMessage());
        verify(runMapper, never()).insert(any());
    }

    @Test
    void createRun_shouldSnapshotDataFreshnessIntoExt() throws Exception {
        AgentLlmProperties.DataFreshness freshness = new AgentLlmProperties.DataFreshness();
        freshness.setStartDate("2020-01-01");
        freshness.setEndDate("2026-06-24");
        freshness.setAsOfDate("2026-06-24");
        freshness.setDescription("test snapshot");
        when(mockPromptService.snapshotDataFreshness()).thenReturn(freshness);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(eventMapper.insert(any())).thenReturn(1);

        AgentRun run = run("r-test", "u-test");
        when(runMapper.findByIdAndUser(anyString(), anyString())).thenReturn(run);

        service.createRun("u-test", "hello", "{\"executionMode\":\" dag \"}",
                "idem", "m", "e", false, "openrouter", 2, false, "{}", false);

        ArgumentCaptor<AgentRun> runCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(runCaptor.capture());
        AgentRun captured = runCaptor.getValue();
        assertEquals("{}", captured.getToolJobAnchorJson());
        Map<?, ?> ext = objectMapper.readValue(captured.getExt(), Map.class);
        assertEquals("DAG", ext.get("execution_mode"));
        Map<?, ?> df = (Map<?, ?>) ext.get("data_freshness");
        assertEquals("2020-01-01", df.get("start_date"));
        assertEquals("2026-06-24", df.get("end_date"));
        assertEquals("2026-06-24", df.get("as_of_date"));
        assertEquals("test snapshot", df.get("description"));
    }

    @Test
    void createRun_shouldNotWriteDataFreshnessWhenSnapshotReturnsNull() throws Exception {
        when(mockPromptService.snapshotDataFreshness()).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(eventMapper.insert(any())).thenReturn(1);

        AgentRun run = run("r-test2", "u-test2");
        when(runMapper.findByIdAndUser(anyString(), anyString())).thenReturn(run);

        service.createRun("u-test2", "hello", "{}", "idem", "m", "e", false, "openrouter", 2, false, "{}", false);

        ArgumentCaptor<AgentRun> runCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(runCaptor.capture());
        Map<?, ?> ext = objectMapper.readValue(runCaptor.getValue().getExt(), Map.class);
        assertEquals("AUTO", ext.get("execution_mode"));
        assertFalse(ext.containsKey("data_freshness"));
    }

    private AgentRun run(String runId, String userId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        return run;
    }
}
