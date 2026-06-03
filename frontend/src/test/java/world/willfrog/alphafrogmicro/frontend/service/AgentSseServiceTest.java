package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AgentSseServiceTest {

    private final AgentSseService service = new AgentSseService(
            mock(StringRedisTemplate.class),
            mock(RedisMessageListenerContainer.class),
            new ObjectMapper()
    );

    @Test
    void normalizeEnvelopeJson_shouldExposePayloadObject() {
        Map<String, Object> normalized = service.normalizeEnvelopeJson("""
                {"runId":"r1","seq":3,"eventType":"TOOL_CALL_STARTED",
                 "payloadJson":"{\\"tool_name\\":\\"searchIndex\\",\\"arguments\\":{\\"keyword\\":\\"沪深300\\"}}",
                 "createdAt":"2026-05-29T00:00:00Z"}
                """);

        assertEquals("agent.event", normalized.get("type"));
        assertEquals("r1", normalized.get("runId"));
        assertEquals(3, normalized.get("seq"));
        assertEquals("TOOL_CALL_STARTED", normalized.get("eventType"));
        Object payload = normalized.get("payload");
        assertInstanceOf(Map.class, payload);
        assertEquals("searchIndex", ((Map<?, ?>) payload).get("tool_name"));
        assertFalse(((Map<?, ?>) normalized).containsKey("payloadJson"));
    }

    @Test
    void normalizeEventMessage_shouldWrapNonObjectPayload() {
        Map<String, Object> normalized = service.normalizeEventMessage(
                AgentRunEventMessage.newBuilder()
                        .setRunId("r1")
                        .setSeq(4)
                        .setEventType("MESSAGE")
                        .setPayloadJson("\"plain\"")
                        .build()
        );

        Object payload = normalized.get("payload");
        assertInstanceOf(Map.class, payload);
        assertEquals("plain", ((Map<?, ?>) payload).get("value"));
    }

    @Test
    void isTerminalStatus_shouldRecognizeTerminalValues() {
        assertTrue(service.isTerminalStatus("COMPLETED"));
        assertTrue(service.isTerminalStatus("canceled"));
        assertTrue(service.isTerminalStatus("FAILED"));
        assertFalse(service.isTerminalStatus("EXECUTING"));
        assertFalse(service.isTerminalStatus(null));
    }

    @Test
    void parseRunIdFromChannel_shouldStripPrefix() {
        byte[] channel = "agent:events:run-abc-123".getBytes(StandardCharsets.UTF_8);
        assertEquals("run-abc-123", AgentSseService.parseRunIdFromChannel(channel));
        assertNull(AgentSseService.parseRunIdFromChannel("other:channel".getBytes(StandardCharsets.UTF_8)));
        assertNull(AgentSseService.parseRunIdFromChannel(null));
    }

    @Test
    void initRedisFanoutSubscription_registersPatternListenerOnce() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        AgentSseService svc = new AgentSseService(
                mock(StringRedisTemplate.class),
                container,
                new ObjectMapper()
        );
        svc.initRedisFanoutSubscription();

        ArgumentCaptor<PatternTopic> topicCaptor = ArgumentCaptor.forClass(PatternTopic.class);
        verify(container).addMessageListener(any(MessageListener.class), topicCaptor.capture());
        assertEquals("agent:events:*", topicCaptor.getValue().getTopic());
        verifyNoMoreInteractions(container);
    }

    @Test
    void onRedisMessage_shouldFanOutToAllSessionsOnSameRunId() throws Exception {
        SseEmitter emitter1 = mock(SseEmitter.class);
        SseEmitter emitter2 = mock(SseEmitter.class);
        String runId = "run-fanout-1";

        registerTestSession(service, runId, emitter1);
        registerTestSession(service, runId, emitter2);

        String envelope = """
                {"runId":"run-fanout-1","seq":7,"eventType":"TOOL_CALL_STARTED",
                 "payloadJson":"{}","createdAt":"2026-06-04T00:00:00Z"}
                """;
        byte[] channel = (AgentSseService.REDIS_CHANNEL_PREFIX + runId).getBytes(StandardCharsets.UTF_8);
        service.onRedisMessage(new DefaultMessage(channel, envelope.getBytes(StandardCharsets.UTF_8)), null);

        verify(emitter1, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter2, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void cleanup_shouldRemoveSessionFromRegistry() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        String runId = "run-cleanup-1";
        registerTestSession(service, runId, emitter);

        @SuppressWarnings("unchecked")
        Map<String, Set<Object>> byRun = (Map<String, Set<Object>>) ReflectionTestUtils.getField(service, "sessionsByRunId");
        assertEquals(1, byRun.get(runId).size());

        ReflectionTestUtils.invokeMethod(service, "cleanup", emitter);

        assertTrue(byRun.isEmpty() || !byRun.containsKey(runId) || byRun.get(runId).isEmpty());
        @SuppressWarnings("unchecked")
        Map<SseEmitter, Object> byEmitter = (Map<SseEmitter, Object>) ReflectionTestUtils.getField(service, "emitterSessions");
        assertFalse(byEmitter.containsKey(emitter));
    }

    private static void registerTestSession(AgentSseService service, String runId, SseEmitter emitter) throws Exception {
        Object session = createSessionInstance(service, runId, emitter);
        ReflectionTestUtils.invokeMethod(service, "registerSession", session);
    }

    private static Object createSessionInstance(AgentSseService service, String runId, SseEmitter emitter) throws Exception {
        Class<?> statusStateClass = Class.forName(
                "world.willfrog.alphafrogmicro.frontend.service.AgentSseService$StatusState"
        );
        Constructor<?> statusCtor = statusStateClass.getDeclaredConstructor();
        statusCtor.setAccessible(true);
        Object statusState = statusCtor.newInstance();

        Class<?> sessionClass = Class.forName(
                "world.willfrog.alphafrogmicro.frontend.service.AgentSseService$SseSession"
        );
        Constructor<?> ctor = sessionClass.getDeclaredConstructor(
                AgentSseService.class,
                String.class,
                String.class,
                SseEmitter.class,
                AtomicBoolean.class,
                java.util.List.class,
                AtomicBoolean.class,
                AtomicBoolean.class,
                statusStateClass
        );
        ctor.setAccessible(true);
        return ctor.newInstance(
                service,
                runId,
                "user-1",
                emitter,
                new AtomicBoolean(false),
                new ArrayList<Map<String, Object>>(),
                new AtomicBoolean(false),
                new AtomicBoolean(false),
                statusState
        );
    }
}
