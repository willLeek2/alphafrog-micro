package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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
    void connect_whenRedisSubscribeFails_shouldCloseEmitterWithoutThrowing() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(container)
                .addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        AgentSseService failingService = new AgentSseService(
                mock(StringRedisTemplate.class),
                container,
                new ObjectMapper()
        );
        SseEmitter emitter = spy(new SseEmitter(60_000L));

        assertDoesNotThrow(() -> failingService.connect("run-1", "user-1", null, emitter));
        verify(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        verify(emitter, timeout(2_000)).complete();
    }
}
