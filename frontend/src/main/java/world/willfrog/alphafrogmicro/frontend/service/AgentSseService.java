package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 实时事件流服务 —— 管理 SSE 连接、snapshot/replay、Redis pub/sub、status watcher 和 heartbeat。
 *
 * <p>外部 SSE 契约在本服务边界完成规范化：DB/Redis 内部仍传 {@code payloadJson}，
 * 但对前端输出的 {@code agent.event.data.payload} 始终是 JSON object。</p>
 */
@Service
@Slf4j
public class AgentSseService {

    private static final int SNAPSHOT_EVENT_COUNT = 10;
    private static final int REPLAY_PAGE_SIZE = 200;
    private static final int LIVE_REPLAY_BUFFER_LIMIT = 500;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long STATUS_WATCH_INTERVAL_MS = 5_000L;
    private static final String REDIS_CHANNEL_PREFIX = "agent:events:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETED", "PARTIAL", "FAILED", "CANCELED", "CANCELLED", "EXPIRED", "TIMEOUT", "TIMED_OUT"
    );

    /**
     * Dubbo 代理（langchain provider），通过字段注入，
     * 与 AgentController 中 agentDubboServiceLangchain 的注入方式一致。
     */
    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboService;

    @SuppressWarnings("unused")
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;
    private final ObjectMapper objectMapper;

    public AgentSseService(StringRedisTemplate stringRedisTemplate,
                           RedisMessageListenerContainer redisListenerContainer,
                           ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisListenerContainer = redisListenerContainer;
        this.objectMapper = objectMapper;
    }

    /** 保存每个 emitter 关联的 heartbeat 定时任务，清理时 cancel。 */
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    /** 保存每个 emitter 关联的 status watcher 定时任务，清理时 cancel。 */
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> statusTasks = new ConcurrentHashMap<>();
    /** 保存每个 emitter 关联的 Redis listener，清理时 unsubscribe。 */
    private final ConcurrentHashMap<SseEmitter, MessageListener> listeners = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-sse");
        t.setDaemon(true);
        return t;
    });

    /**
     * 建立 SSE 连接。
     *
     * <p>无恢复 cursor 时发送 snapshot；有 cursor 时先 replay {@code seq > resumeAfterSeq}
     * 的 durable DB event，再进入 live 模式。Redis listener 会先订阅并在 replay 期间缓冲 live
     * event，减少补发窗口里的丢失风险。</p>
     */
    public void connect(String runId, String userId, Integer resumeAfterSeq, SseEmitter emitter) {
        int safeResumeAfterSeq = resumeAfterSeq == null ? 0 : Math.max(0, resumeAfterSeq);
        String channelName = REDIS_CHANNEL_PREFIX + runId;
        AtomicBoolean replaying = new AtomicBoolean(safeResumeAfterSeq > 0);
        List<Map<String, Object>> liveBuffer = new ArrayList<>();
        AtomicBoolean overflow = new AtomicBoolean(false);
        AtomicBoolean doneSent = new AtomicBoolean(false);
        StatusState statusState = new StatusState();

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                Map<String, Object> normalized = normalizeEnvelopeJson(
                        new String(message.getBody(), StandardCharsets.UTF_8));
                synchronized (liveBuffer) {
                    if (replaying.get()) {
                        if (liveBuffer.size() >= LIVE_REPLAY_BUFFER_LIMIT) {
                            overflow.set(true);
                        } else {
                            liveBuffer.add(normalized);
                        }
                        return;
                    }
                }
                sendAgentEvent(emitter, normalized);
            } catch (Exception e) {
                log.debug("SSE live event send failed for runId={}, likely client disconnected: {}",
                        runId, e.getMessage());
            }
        };
        redisListenerContainer.addMessageListener(listener, new ChannelTopic(channelName));
        listeners.put(emitter, listener);

        try {
            if (safeResumeAfterSeq > 0) {
                int replayMaxSeq = replayEvents(runId, userId, safeResumeAfterSeq, emitter);
                replaying.set(false);
                flushLiveBuffer(emitter, liveBuffer, replayMaxSeq);
                if (overflow.get()) {
                    sendErrorAndClose(emitter, "LIVE_REPLAY_BUFFER_OVERFLOW", "SSE live buffer overflow; please repair via REST events");
                    cleanup(emitter, channelName);
                    return;
                }
            } else {
                SnapshotData snapshotData = buildSnapshot(runId, userId);
                sendJsonEvent(emitter, SseEmitter.event().name("snapshot"), snapshotData.snapshot());
                emitRunStatus(emitter, runId, snapshotData.status(), statusState);
                emitRunDoneIfTerminal(emitter, runId, snapshotData.status(), doneSent);
            }
        } catch (Exception e) {
            log.warn("SSE initialization failed for runId={}, sending error and closing", runId, e);
            sendErrorAndClose(emitter, "STREAM_INIT_FAILED", "无法初始化 run stream");
            cleanup(emitter, channelName);
            return;
        }

        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            Map<String, Object> hb = new LinkedHashMap<>();
            hb.put("type", "heartbeat");
            hb.put("runId", runId);
            hb.put("ts", System.currentTimeMillis());
            try {
                sendJsonEvent(emitter, SseEmitter.event().name("heartbeat"), hb);
            } catch (Exception e) {
                log.debug("SSE heartbeat failed for runId={}, likely client disconnected", runId);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        heartbeatTasks.put(emitter, heartbeat);

        ScheduledFuture<?> statusWatcher = scheduler.scheduleWithFixedDelay(() -> {
            try {
                AgentRunStatusMessage status = loadStatus(runId, userId);
                emitRunStatusIfChanged(emitter, runId, status, statusState);
                emitRunDoneIfTerminal(emitter, runId, status, doneSent);
            } catch (Exception e) {
                log.debug("SSE status watcher failed for runId={}: {}", runId, e.getMessage());
            }
        }, STATUS_WATCH_INTERVAL_MS, STATUS_WATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        statusTasks.put(emitter, statusWatcher);

        emitter.onCompletion(() -> {
            log.debug("SSE completed for runId={}", runId);
            cleanup(emitter, channelName);
        });
        emitter.onError(ex -> {
            log.debug("SSE error for runId={}: {}", runId, ex.getMessage());
            cleanup(emitter, channelName);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for runId={}", runId);
            cleanup(emitter, channelName);
        });

        log.info("SSE connected for runId={}, resumeAfterSeq={}", runId, safeResumeAfterSeq);
    }

    private int replayEvents(String runId, String userId, int afterSeq, SseEmitter emitter) {
        int cursor = afterSeq;
        int replayMaxSeq = afterSeq;
        while (true) {
            ListAgentRunEventsResponse page = agentDubboService.listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setAfterSeq(cursor)
                            .setLimit(REPLAY_PAGE_SIZE)
                            .build());
            int maxSeqInPage = cursor;
            for (AgentRunEventMessage event : page.getItemsList()) {
                Map<String, Object> normalized = normalizeEventMessage(event);
                sendAgentEvent(emitter, normalized);
                maxSeqInPage = Math.max(maxSeqInPage, event.getSeq());
                replayMaxSeq = Math.max(replayMaxSeq, event.getSeq());
            }
            if (!page.getHasMore() || page.getNextAfterSeq() <= cursor || page.getItemsCount() == 0) {
                break;
            }
            cursor = Math.max(page.getNextAfterSeq(), maxSeqInPage);
        }
        return replayMaxSeq;
    }

    private void flushLiveBuffer(SseEmitter emitter, List<Map<String, Object>> liveBuffer, int replayAfterSeq) {
        List<Map<String, Object>> buffered;
        synchronized (liveBuffer) {
            buffered = new ArrayList<>(liveBuffer);
            liveBuffer.clear();
        }
        buffered.sort(Comparator.comparingInt(this::seqOf));
        int lastSentSeq = replayAfterSeq;
        for (Map<String, Object> event : buffered) {
            int seq = seqOf(event);
            if (seq <= lastSentSeq) {
                continue;
            }
            sendAgentEvent(emitter, event);
            lastSentSeq = seq;
        }
    }

    private SnapshotData buildSnapshot(String runId, String userId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "snapshot");
        snapshot.put("runId", runId);
        snapshot.put("ts", System.currentTimeMillis());

        AgentRunStatusMessage statusMsg = loadStatus(runId, userId);
        snapshot.put("status", emptyToNull(statusMsg.getStatus()));
        snapshot.put("phase", emptyToNull(statusMsg.getPhase()));
        snapshot.put("startedAtMs", statusMsg.getStartedAtMs() > 0 ? statusMsg.getStartedAtMs() : null);
        snapshot.put("completedAtMs", statusMsg.getCompletedAtMs() > 0 ? statusMsg.getCompletedAtMs() : null);

        String planJson = statusMsg.getPlanJson();
        if (planJson != null && !planJson.isBlank()) {
            snapshot.put("plan", parseJsonOrNull(planJson));
        }

        List<AgentRunEventResponse> recentEvents = new ArrayList<>();
        ListAgentRunEventsResponse eventsResp = agentDubboService.listEvents(
                ListAgentRunEventsRequest.newBuilder()
                        .setUserId(userId)
                        .setId(runId)
                        .setLimit(Math.max(1, SNAPSHOT_EVENT_COUNT))
                        .setLatest(true)
                        .build());
        for (var e : eventsResp.getItemsList()) {
            recentEvents.add(new AgentRunEventResponse(
                    e.getId(), e.getRunId(), e.getSeq(), e.getEventType(),
                    parsePayloadObject(e.getPayloadJson()), e.getCreatedAt()));
        }
        snapshot.put("events", recentEvents);
        snapshot.put("eventCount", recentEvents.size());

        // Proto field eventCount is currently backed by findMaxSeq(); use a local maxSeq name to avoid semantic drift.
        int maxSeq = Math.max(0, statusMsg.getEventCount());
        for (AgentRunEventResponse event : recentEvents) {
            maxSeq = Math.max(maxSeq, event.seq());
        }
        snapshot.put("lastSeq", maxSeq);
        return new SnapshotData(snapshot, statusMsg);
    }

    private AgentRunStatusMessage loadStatus(String runId, String userId) {
        return agentDubboService.getStatus(
                GetAgentRunStatusRequest.newBuilder().setUserId(userId).setId(runId).build());
    }

    Map<String, Object> normalizeEnvelopeJson(String envelopeJson) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid agent event envelope JSON", e);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "agent.event");
        normalized.put("runId", strVal(envelope.get("runId")));
        normalized.put("seq", intVal(envelope.get("seq")));
        normalized.put("eventType", strVal(envelope.get("eventType")));
        normalized.put("payload", parsePayloadObject(strVal(envelope.get("payloadJson"))));
        normalized.put("createdAt", emptyToNull(strVal(envelope.get("createdAt"))));
        normalized.put("ts", System.currentTimeMillis());
        return normalized;
    }

    Map<String, Object> normalizeEventMessage(AgentRunEventMessage event) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "agent.event");
        normalized.put("runId", event.getRunId());
        normalized.put("seq", event.getSeq());
        normalized.put("eventType", event.getEventType());
        normalized.put("payload", parsePayloadObject(event.getPayloadJson()));
        normalized.put("createdAt", emptyToNull(event.getCreatedAt()));
        normalized.put("ts", System.currentTimeMillis());
        return normalized;
    }

    private void sendAgentEvent(SseEmitter emitter, Map<String, Object> event) {
        sendJsonEvent(emitter, SseEmitter.event()
                .name("agent.event")
                .id(String.valueOf(seqOf(event))), event);
    }

    private void emitRunStatus(SseEmitter emitter, String runId, AgentRunStatusMessage status, StatusState state) {
        state.status = emptyToNull(status.getStatus());
        state.phase = emptyToNull(status.getPhase());
        sendJsonEvent(emitter, SseEmitter.event().name("run.status"), statusPayload(runId, status));
    }

    private void emitRunStatusIfChanged(SseEmitter emitter, String runId,
                                        AgentRunStatusMessage status, StatusState state) {
        String statusValue = emptyToNull(status.getStatus());
        String phaseValue = emptyToNull(status.getPhase());
        if (equalsNullable(statusValue, state.status) && equalsNullable(phaseValue, state.phase)) {
            return;
        }
        state.status = statusValue;
        state.phase = phaseValue;
        sendJsonEvent(emitter, SseEmitter.event().name("run.status"), statusPayload(runId, status));
    }

    private void emitRunDoneIfTerminal(SseEmitter emitter, String runId,
                                       AgentRunStatusMessage status, AtomicBoolean doneSent) {
        if (!isTerminalStatus(status.getStatus()) || !doneSent.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> payload = statusPayload(runId, status);
        payload.put("type", "run.done");
        sendJsonEvent(emitter, SseEmitter.event().name("run.done"), payload);
    }

    private Map<String, Object> statusPayload(String runId, AgentRunStatusMessage status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "run.status");
        payload.put("runId", runId);
        payload.put("status", emptyToNull(status.getStatus()));
        payload.put("phase", emptyToNull(status.getPhase()));
        payload.put("lastSeq", Math.max(0, status.getEventCount()));
        payload.put("startedAtMs", status.getStartedAtMs() > 0 ? status.getStartedAtMs() : null);
        payload.put("completedAtMs", status.getCompletedAtMs() > 0 ? status.getCompletedAtMs() : null);
        payload.put("ts", System.currentTimeMillis());
        return payload;
    }

    boolean isTerminalStatus(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private void sendJsonEvent(SseEmitter emitter, SseEmitter.SseEventBuilder builder, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(builder.data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }

    private void sendErrorAndClose(SseEmitter emitter, String code, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "error");
            error.put("code", code);
            error.put("message", message);
            error.put("ts", System.currentTimeMillis());
            sendJsonEvent(emitter, SseEmitter.event().name("error"), error);
        } catch (Exception ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void cleanup(SseEmitter emitter, String channelName) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(emitter);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        ScheduledFuture<?> statusWatcher = statusTasks.remove(emitter);
        if (statusWatcher != null) {
            statusWatcher.cancel(false);
        }
        MessageListener listener = listeners.remove(emitter);
        if (listener != null) {
            try {
                redisListenerContainer.removeMessageListener(listener, new ChannelTopic(channelName));
            } catch (Exception e) {
                log.debug("Error removing Redis listener for channel {}: {}", channelName, e.getMessage());
            }
        }
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayloadObject(String json) {
        Object value = parseJsonOrNull(json);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (value != null) {
            result.put("value", value);
        }
        return result;
    }

    private int seqOf(Map<String, Object> event) {
        return intVal(event.get("seq"));
    }

    private int intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String strVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private record SnapshotData(Map<String, Object> snapshot, AgentRunStatusMessage status) {
    }

    private static final class StatusState {
        private String status;
        private String phase;
    }
}
