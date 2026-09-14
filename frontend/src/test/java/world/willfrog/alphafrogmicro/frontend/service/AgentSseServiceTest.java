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

    @Test
    void canonicalMapper_shouldExposeStableV1PayloadAndTimestamp() {
        Map<String, Object> normalized = service.normalizeEnvelopeJson("""
                {"runId":"r1","seq":3,"eventType":"TOOL_CALL_STARTED",
                 "payloadJson":"{\\"tool_name\\":\\"searchIndex\\"}",
                 "createdAt":"2026-05-29T00:00:00Z"}
                """);

        assertEquals(1, normalized.get("schemaVersion"));
        assertEquals("agent.event", normalized.get("type"));
        assertEquals("r1", normalized.get("runId"));
        assertEquals(3, normalized.get("seq"));
        assertEquals(true, normalized.get("durable"));
        assertEquals(1_780_012_800_000L, normalized.get("ts"));
        assertEquals("searchIndex", ((Map<?, ?>) normalized.get("payload")).get("tool_name"));
        assertFalse(normalized.containsKey("payloadJson"));
    }

    @Test
    void canonicalMapper_shouldWrapScalarAndFailClosedForMalformedJson() {
        Map<String, Object> scalar = service.normalizeEventMessage(
                AgentRunEventMessage.newBuilder().setRunId("r1").setSeq(4)
                        .setEventType("MESSAGE").setPayloadJson("\"plain\"").build());
        Map<String, Object> malformed = service.normalizeEventMessage(
                AgentRunEventMessage.newBuilder().setRunId("r1").setSeq(5)
                        .setEventType("MESSAGE").setPayloadJson("{broken raw-secret").build());

        assertEquals("plain", ((Map<?, ?>) scalar.get("payload")).get("value"));
        assertEquals("INVALID_JSON", ((Map<?, ?>) malformed.get("payload")).get("value"));
        assertFalse(malformed.toString().contains("raw-secret"));
    }

    @Test
    void canonicalMapper_shouldSharePayloadRulesAndStableTsAcrossDbAndRedis() {
        AgentRunEventMessage message = AgentRunEventMessage.newBuilder()
                .setId(99).setRunId("r1").setSeq(8).setEventType("VALUES")
                .setPayloadJson("[1,true,\"x\"]").setCreatedAt("2026-08-09T00:00:00Z").build();
        Map<String, Object> fromDb = service.normalizeEventMessage(message);
        Map<String, Object> fromRedis = service.normalizeEnvelopeJson("""
                {"runId":"r1","seq":8,"eventType":"VALUES","payloadJson":"[1,true,\\"x\\"]",
                 "createdAt":"2026-08-09T00:00:00Z","durable":true}
                """);
        Map<String, Object> blank = service.normalizeEventMessage(
                AgentRunEventMessage.newBuilder().setPayloadJson(" ").build());

        assertEquals(List.of(1, true, "x"), ((Map<?, ?>) fromDb.get("payload")).get("value"));
        assertEquals(fromDb.get("payload"), fromRedis.get("payload"));
        assertEquals(fromDb.get("ts"), fromRedis.get("ts"));
        assertEquals(99L, fromDb.get("id"));
        assertFalse(fromRedis.containsKey("id"));
        assertTrue(((Map<?, ?>) blank.get("payload")).isEmpty());
    }

    @Test
    void canonicalMapper_shouldForceLiveOnlyIdentityToSeqZeroWithoutId() {
        Map<String, Object> normalized = service.normalizeEnvelopeJson("""
                {"id":99,"runId":"r1","seq":8,"eventType":"LLM_CALL_DELTA",
                 "payload":{},"createdAt":"2026-08-09T00:00:00Z","durable":false}
                """);

        assertEquals(false, normalized.get("durable"));
        assertEquals(0, normalized.get("seq"));
        assertFalse(normalized.containsKey("id"));
    }

    @Test
    void isTerminalStatus_shouldNormalizeAliasesButNotCanceling() {
        assertTrue(service.isTerminalStatus("COMPLETED"));
        assertTrue(service.isTerminalStatus(" cancelled "));
        assertTrue(service.isTerminalStatus("TIMED_OUT"));
        assertFalse(service.isTerminalStatus("CANCELING"));
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
        AgentSseService svc = new AgentSseService(mock(StringRedisTemplate.class), container, OBJECT_MAPPER);
        try {
            svc.initRedisFanoutSubscription();
            svc.initRedisFanoutSubscription();
            ArgumentCaptor<PatternTopic> topic = ArgumentCaptor.forClass(PatternTopic.class);
            verify(container).addMessageListener(any(MessageListener.class), topic.capture());
            assertEquals("agent:events:*", topic.getValue().getTopic());
            verifyNoMoreInteractions(container);
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void connect_shouldSendSnapshotFirstThenReplayBufferedLiveWithoutDuplicate() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("EXECUTING", "{\"steps\":[]}", 4));
        AtomicInteger calls = new AtomicInteger();
        when(dubbo.listEvents(any(ListAgentRunEventsRequest.class))).thenAnswer(invocation -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                return page(false, 1, event(1, "ONE"));
            }
            publishRedis(service, "run-1", 3, true, "THREE");
            publishRedis(service, "run-1", 4, true, "FOUR");
            return page(false, 2, event(2, "TWO"), event(3, "THREE"));
        });
        RecordingEmitter emitter = new RecordingEmitter();

        service.connect("run-1", "user-1", false, 1, emitter);

        assertEquals("snapshot", emitter.eventNames().get(0));
        assertEquals(List.of("2", "3", "4"), emitter.idsFor("agent.event"));
        assertEquals(3, emitter.eventNames().stream().filter("agent.event"::equals).count());
        assertEquals(4, parseData(emitter.wireFor("snapshot").get(0)).get("eventCount"));
        assertEquals(1, parseData(emitter.wireFor("run.status").get(0)).get("lastSeq"));
    }

    @Test
    void liveOnlyEvent_shouldNotCarrySseIdOrAdvanceDurableCursor() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        Object session = createAndRegisterSession(service, "run-live", emitter);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(session, "finishInitialization", 7));

        publishRedis(service, "run-live", 0, false, "LLM_CALL_DELTA");

        String wire = emitter.wireFor("agent.event").get(0);
        assertFalse(wire.contains("id:"));
        assertEquals(false, parseData(wire).get("durable"));
        assertEquals(0, parseData(wire).get("seq"));
        assertEquals(7, ((AtomicInteger) ReflectionTestUtils.getField(session, "lastDurableSeq")).get());
    }

    @Test
    void initiallyTerminal_shouldSendDoneCompleteAndLeaveNoRegistryOrTasks() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status(" timeout ", "", 0));
        when(dubbo.listEvents(any())).thenReturn(page(false, 0));
        RecordingEmitter emitter = new RecordingEmitter();

        service.connect("run-terminal", "user-1", false, 0, emitter);

        assertEquals(List.of("snapshot", "run.status", "run.done"), emitter.eventNames());
        assertEquals("EXPIRED", parseData(emitter.wireFor("run.done").get(0)).get("status"));
        assertTrue(emitter.completed);
        assertTrue(mapField(service, "sessionsByRunId").isEmpty());
        assertTrue(mapField(service, "heartbeatTasks").isEmpty());
        assertTrue(mapField(service, "statusTasks").isEmpty());
        verify(dubbo, times(1)).getStatus(any());
    }

    @Test
    void resumedTerminal_shouldReplayBeforeDoneAndNeverScheduleBackgroundTasks() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("CANCELLED", "", 2));
        AtomicInteger calls = new AtomicInteger();
        when(dubbo.listEvents(any())).thenAnswer(invocation -> calls.getAndIncrement() == 0
                ? page(false, 1, event(1, "ONE"))
                : page(false, 2, event(2, "TWO")));
        RecordingEmitter emitter = new RecordingEmitter();

        service.connect("run-1", "user-1", false, 1, emitter);

        assertEquals(List.of("snapshot", "run.status", "agent.event", "run.done"), emitter.eventNames());
        assertEquals("CANCELED", parseData(emitter.wireFor("run.done").get(0)).get("status"));
        assertTrue(mapField(service, "heartbeatTasks").isEmpty());
        assertTrue(mapField(service, "statusTasks").isEmpty());
    }

    @Test
    void resumeCursorAheadOfServer_shouldClampToLatestConfirmedDurableSeq() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("COMPLETED", "", 2));
        when(dubbo.listEvents(any())).thenReturn(page(false, 2, event(1, "ONE"), event(2, "TWO")));
        RecordingEmitter emitter = new RecordingEmitter();

        service.connect("run-1", "user-1", false, 999, emitter);

        Map<String, Object> snapshot = parseData(emitter.wireFor("snapshot").get(0));
        assertEquals(2, snapshot.get("lastSeq"));
        assertEquals(2, parseData(emitter.wireFor("run.status").get(0)).get("lastSeq"));
        assertEquals(2, parseData(emitter.wireFor("run.done").get(0)).get("lastSeq"));
        ArgumentCaptor<ListAgentRunEventsRequest> requests =
                ArgumentCaptor.forClass(ListAgentRunEventsRequest.class);
        verify(dubbo, times(2)).listEvents(requests.capture());
        assertEquals(2, requests.getAllValues().get(1).getAfterSeq());
    }

    @Test
    void watcherTerminalPath_shouldCompleteAndCancelExistingBackgroundTasks() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("EXECUTING", "", 0));
        when(dubbo.listEvents(any())).thenReturn(page(false, 0));
        RecordingEmitter emitter = new RecordingEmitter();
        service.connect("run-watcher", "user-1", false, 0, emitter);
        Object session = mapField(service, "emitterSessions").get(emitter);
        assertFalse(mapField(service, "heartbeatTasks").isEmpty());
        assertFalse(mapField(service, "statusTasks").isEmpty());

        boolean closed = (Boolean) ReflectionTestUtils.invokeMethod(
                service, "emitRunDoneIfTerminal", session, status("FAILED", "", 0));

        assertTrue(closed);
        assertTrue(emitter.completed);
        assertTrue(mapField(service, "heartbeatTasks").isEmpty());
        assertTrue(mapField(service, "statusTasks").isEmpty());
        assertTrue(mapField(service, "sessionsByRunId").isEmpty());
    }

    @Test
    void terminalDoneSendFailure_shouldErrorCloseWithoutSchedulingWatcher() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("COMPLETED", "", 0));
        when(dubbo.listEvents(any())).thenReturn(page(false, 0));
        RecordingEmitter emitter = new RecordingEmitter("run.done");

        service.connect("run-terminal", "user-1", false, 0, emitter);

        assertTrue(emitter.completed);
        assertEquals("RUN_DONE_SEND_FAILED", parseData(emitter.wireFor("error").get(0)).get("code"));
        assertTrue(mapField(service, "heartbeatTasks").isEmpty());
        assertTrue(mapField(service, "statusTasks").isEmpty());
    }

    @Test
    void snapshotPlan_shouldUseRoleAwareViewAndFailClosed() {
        Map<String, Object> nonAdmin = connectAndSnapshot(false, """
                {"steps":[{"id":"s1","description":"normal","params":{"token":"secret"}}],
                 "reasoningText":"hidden"}
                """);
        Map<String, Object> admin = connectAndSnapshot(true, """
                {"steps":[{"id":"s1","description":"normal","params":{"token":"secret"}}],
                 "reasoningText":"visible"}
                """);
        Map<String, Object> malformed = connectAndSnapshot(false, "{broken raw-plan");
        Map<String, Object> nestedPollution = connectAndSnapshot(false, """
                {"steps":[{"id":"s1","description":{"text":"nested-secret"},
                 "dependencies":[{"id":"also-secret"}],"status":"READY"}]}
                """);

        String nonAdminPlan = String.valueOf(nonAdmin.get("plan"));
        assertTrue(nonAdminPlan.contains("normal"));
        assertFalse(nonAdminPlan.contains("params"));
        assertFalse(nonAdminPlan.contains("reasoning"));
        assertTrue(String.valueOf(admin.get("plan")).contains("reasoningText"));
        assertFalse(String.valueOf(admin.get("plan")).contains("secret"));
        assertNull(malformed.get("plan"));
        String nestedPlan = String.valueOf(nestedPollution.get("plan"));
        assertTrue(nestedPlan.contains("READY"));
        assertFalse(nestedPlan.contains("nested-secret"));
        assertFalse(nestedPlan.contains("also-secret"));
    }

    @Test
    void overflow_shouldSendErrorAndCloseAfterSnapshot() {
        AgentDubboService dubbo = mock(AgentDubboService.class);
        ReflectionTestUtils.setField(service, "agentDubboService", dubbo);
        when(dubbo.getStatus(any())).thenReturn(status("EXECUTING", "", 900));
        AtomicInteger calls = new AtomicInteger();
        when(dubbo.listEvents(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return page(false, 1, event(1, "ONE"));
            }
            for (int seq = 2; seq <= AgentSseService.LIVE_REPLAY_BUFFER_LIMIT + 2; seq++) {
                publishRedis(service, "run-overflow", seq, true, "E" + seq);
            }
            return page(false, 1);
        });
        RecordingEmitter emitter = new RecordingEmitter();

        service.connect("run-overflow", "user-1", false, 1, emitter);

        assertEquals("snapshot", emitter.eventNames().get(0));
        assertEquals("LIVE_REPLAY_BUFFER_OVERFLOW", parseData(emitter.wireFor("error").get(0)).get("code"));
        assertEquals(1, emitter.wireFor("error").size());
        assertTrue(emitter.completed);
        assertTrue(mapField(service, "sessionsByRunId").isEmpty());
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
