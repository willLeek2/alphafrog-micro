package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentSseServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AgentSseService service = new AgentSseService(
            mock(StringRedisTemplate.class),
            mock(RedisMessageListenerContainer.class),
            OBJECT_MAPPER
    );

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

















    private Map<String, Object> connectAndSnapshot(boolean admin, String planJson) {
        AgentSseService svc = new AgentSseService(
                mock(StringRedisTemplate.class), mock(RedisMessageListenerContainer.class), OBJECT_MAPPER);
        try {
            AgentDubboService dubbo = mock(AgentDubboService.class);
            ReflectionTestUtils.setField(svc, "agentDubboService", dubbo);
            when(dubbo.getStatus(any())).thenReturn(status("COMPLETED", planJson, 0));
            when(dubbo.listEvents(any())).thenReturn(page(false, 0));
            RecordingEmitter emitter = new RecordingEmitter();
            svc.connect("run-plan", "user-1", admin, 0, emitter);
            return parseData(emitter.wireFor("snapshot").get(0));
        } finally {
            svc.shutdown();
        }
    }

    private static AgentRunStatusMessage status(String status, String planJson, int eventCount) {
        return AgentRunStatusMessage.newBuilder().setId("run-1").setStatus(status)
                .setPlanJson(planJson).setEventCount(eventCount).build();
    }

    private static AgentRunEventMessage event(int seq, String eventType) {
        return AgentRunEventMessage.newBuilder().setId(seq).setRunId("run-1").setSeq(seq)
                .setEventType(eventType).setPayloadJson("{}").setCreatedAt("2026-08-09T00:00:00Z").build();
    }

    private static ListAgentRunEventsResponse page(boolean hasMore, int nextAfterSeq,
                                                   AgentRunEventMessage... events) {
        return ListAgentRunEventsResponse.newBuilder().addAllItems(List.of(events))
                .setHasMore(hasMore).setNextAfterSeq(nextAfterSeq).build();
    }

    private static void publishRedis(AgentSseService svc, String runId, int seq,
                                     boolean durable, String eventType) throws Exception {
        String envelope = OBJECT_MAPPER.writeValueAsString(Map.of(
                "runId", runId,
                "seq", seq,
                "eventType", eventType,
                "payloadJson", "{}",
                "createdAt", "2026-08-09T00:00:00Z",
                "durable", durable
        ));
        byte[] channel = (AgentSseService.REDIS_CHANNEL_PREFIX + runId).getBytes(StandardCharsets.UTF_8);
        svc.onRedisMessage(new DefaultMessage(channel, envelope.getBytes(StandardCharsets.UTF_8)), null);
    }

    private static Object createAndRegisterSession(AgentSseService svc, String runId,
                                                   SseEmitter emitter) throws Exception {
        Class<?> sessionClass = Class.forName(
                "world.willfrog.alphafrogmicro.frontend.service.AgentSseService$SseSession");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(
                AgentSseService.class, String.class, String.class, SseEmitter.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(svc, runId, "user-1", emitter);
        ReflectionTestUtils.invokeMethod(svc, "registerSession", session);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> mapField(Object target, String name) {
        return (Map<Object, Object>) ReflectionTestUtils.getField(target, name);
    }

    private static Map<String, Object> parseData(String wire) {
        int start = wire.indexOf("data:");
        assertTrue(start >= 0, wire);
        String json = wire.substring(start + 5).trim();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new AssertionError("invalid SSE JSON: " + wire, e);
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> wires = new ArrayList<>();
        private final String failEventName;
        private boolean completed;

        private RecordingEmitter() {
            this(null);
        }

        private RecordingEmitter(String failEventName) {
            super(-1L);
            this.failEventName = failEventName;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder wire = new StringBuilder();
            Set<ResponseBodyEmitter.DataWithMediaType> data = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType item : data) {
                wire.append(item.getData());
            }
            String value = wire.toString();
            if (failEventName != null && value.contains("event:" + failEventName + "\n")) {
                throw new IOException("simulated send failure");
            }
            wires.add(value);
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        private List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (String wire : wires) {
                int start = wire.indexOf("event:");
                if (start >= 0) {
                    int end = wire.indexOf('\n', start);
                    names.add(wire.substring(start + 6, end));
                }
            }
            return names;
        }

        private List<String> wireFor(String eventName) {
            return wires.stream().filter(wire -> wire.contains("event:" + eventName + "\n")).toList();
        }

        private List<String> idsFor(String eventName) {
            List<String> ids = new ArrayList<>();
            for (String wire : wireFor(eventName)) {
                int start = wire.indexOf("id:");
                if (start >= 0) {
                    ids.add(wire.substring(start + 3, wire.indexOf('\n', start)));
                }
            }
            return ids;
        }
    }
}
