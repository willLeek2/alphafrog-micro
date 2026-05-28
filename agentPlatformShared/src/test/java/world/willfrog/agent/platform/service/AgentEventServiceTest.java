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
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    private AgentEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentEventService(
                runMapper,
                eventMapper,
                objectMapper,
                redisTemplate,
                llmLocalConfigLoader,
                messageService
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

    private AgentRun run(String runId, String userId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        return run;
    }
}
