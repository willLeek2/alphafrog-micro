package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.common.agent.AgentRunTerminalStatus;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentEventEnvelopeMapper;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentExternalObservabilityMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 实时事件流服务，统一 snapshot/replay/live schema 并管理连接生命周期。
 *
 * <p>连接注册后始终先缓冲 Redis live，首帧 snapshot 发送成功后再执行 replay，最后在同一
 * 临界区内完成 buffer flush → live 切换。这样 snapshot 与 replay 之间不会丢失、重复或交叉
 * durable event；seq=0 的 live-only event 仍会送达，但不带 SSE id，也不推进 durable cursor。</p>
 */
@Service
@Slf4j
public class AgentSseService {

    static final String REDIS_CHANNEL_PREFIX = "agent:events:";
    private static final String REDIS_PATTERN_TOPIC = REDIS_CHANNEL_PREFIX + "*";
    private static final int SNAPSHOT_EVENT_COUNT = 10;
    private static final int REPLAY_PAGE_SIZE = 200;
    static final int LIVE_REPLAY_BUFFER_LIMIT = 500;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long STATUS_WATCH_INTERVAL_MS = 5_000L;

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboService;

    @SuppressWarnings("unused")
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Set<SseSession>> sessionsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, SseSession> emitterSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> statusTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "agent-sse");
        thread.setDaemon(true);
        return thread;
    });
    private final MessageListener globalRedisListener = this::onRedisMessage;
    private final AtomicBoolean redisFanoutRegistered = new AtomicBoolean(false);

    public AgentSseService(StringRedisTemplate stringRedisTemplate,
                           RedisMessageListenerContainer redisListenerContainer,
                           ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisListenerContainer = redisListenerContainer;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initRedisFanoutSubscription() {
        if (!redisFanoutRegistered.compareAndSet(false, true)) {
            return;
        }
        redisListenerContainer.addMessageListener(globalRedisListener, new PatternTopic(REDIS_PATTERN_TOPIC));
        log.info("Agent SSE Redis fan-out subscribed: pattern={}", REDIS_PATTERN_TOPIC);
    }

    /** 兼容旧调用面；普通用户 snapshot 使用严格 {@code View.PLAN}。 */
    public void connect(String runId, String userId, Integer resumeAfterSeq, SseEmitter emitter) {
        connect(runId, userId, false, resumeAfterSeq, emitter);
    }

    /** 建立 snapshot-first SSE 连接。 */
    public void connect(String runId, String userId, boolean admin,
                        Integer resumeAfterSeq, SseEmitter emitter) {
        int safeResumeAfterSeq = resumeAfterSeq == null ? 0 : Math.max(0, resumeAfterSeq);
        SseSession session = new SseSession(runId, userId, emitter);
        registerSession(session);
        registerEmitterCallbacks(session);

        try {
            SnapshotData snapshotData = buildSnapshot(runId, userId, admin, safeResumeAfterSeq);
            session.lastDurableSeq.set(snapshotData.lastSeq());
            sendJsonEvent(emitter, SseEmitter.event().name("snapshot"), snapshotData.snapshot());
            emitRunStatus(session, snapshotData.status());

            int replayMaxSeq = snapshotData.lastSeq();
            if (safeResumeAfterSeq > 0) {
                replayMaxSeq = replayEvents(session, snapshotData.lastSeq());
            }
            if (!session.finishInitialization(replayMaxSeq)) {
                return;
            }
            if (emitRunDoneIfTerminal(session, snapshotData.status())) {
                return;
            }
        } catch (Exception e) {
            log.warn("SSE initialization failed for runId={}, sending error and closing", runId, e);
            sendErrorAndClose(emitter, "STREAM_INIT_FAILED", "无法初始化 run stream");
            cleanup(emitter);
            return;
        }

        scheduleHeartbeat(session);
        scheduleStatusWatcher(session);
        log.info("SSE connected for runId={}, resumeAfterSeq={}", runId, safeResumeAfterSeq);
    }

    private void registerEmitterCallbacks(SseSession session) {
        session.emitter.onCompletion(() -> {
            log.debug("SSE completed for runId={}", session.runId);
            cleanup(session.emitter);
        });
        session.emitter.onError(error -> {
            log.debug("SSE error for runId={}: {}", session.runId, error.getMessage());
            cleanup(session.emitter);
        });
        session.emitter.onTimeout(() -> {
            log.debug("SSE timeout for runId={}", session.runId);
            cleanup(session.emitter);
        });
    }

    private void scheduleHeartbeat(SseSession session) {
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "heartbeat");
            payload.put("runId", session.runId);
            payload.put("ts", System.currentTimeMillis());
            try {
                sendJsonEvent(session.emitter, SseEmitter.event().name("heartbeat"), payload);
            } catch (Exception e) {
                log.debug("SSE heartbeat failed for runId={}, closing session", session.runId);
                cleanup(session.emitter);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        heartbeatTasks.put(session.emitter, heartbeat);
    }

    private void scheduleStatusWatcher(SseSession session) {
        ScheduledFuture<?> watcher = scheduler.scheduleWithFixedDelay(() -> {
            try {
                AgentRunStatusMessage status = loadStatus(session.runId, session.userId);
                emitRunStatusIfChanged(session, status);
                emitRunDoneIfTerminal(session, status);
            } catch (Exception e) {
                log.debug("SSE status watcher failed for runId={}: {}", session.runId, e.getMessage());
            }
        }, STATUS_WATCH_INTERVAL_MS, STATUS_WATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        statusTasks.put(session.emitter, watcher);
    }

    void onRedisMessage(Message message, byte[] pattern) {
        String runId = parseRunIdFromChannel(message.getChannel());
        if (runId == null || runId.isBlank()) {
            return;
        }
        Set<SseSession> sessions = sessionsByRunId.get(runId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        AgentRunEventResponse normalized;
        try {
            normalized = AgentEventEnvelopeMapper.fromRedisEnvelope(
                    objectMapper, new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Ignore invalid Redis envelope for runId={}: {}", runId, e.getMessage());
            return;
        }
        if (!runId.equals(normalized.runId())) {
            log.warn("Ignore Redis envelope with mismatched runId: channel={}, envelope={}", runId, normalized.runId());
            return;
        }
        for (SseSession session : sessions) {
            session.dispatchRedisMessage(normalized);
        }
    }

    static String parseRunIdFromChannel(byte[] channelBytes) {
        if (channelBytes == null || channelBytes.length == 0) {
            return null;
        }
        String channel = new String(channelBytes, StandardCharsets.UTF_8);
        if (!channel.startsWith(REDIS_CHANNEL_PREFIX)) {
            return null;
        }
        return channel.substring(REDIS_CHANNEL_PREFIX.length());
    }

    private void registerSession(SseSession session) {
        emitterSessions.put(session.emitter, session);
        sessionsByRunId.computeIfAbsent(session.runId, ignored -> new CopyOnWriteArraySet<>()).add(session);
    }

    private void unregisterSession(SseSession session) {
        if (session == null) {
            return;
        }
        emitterSessions.remove(session.emitter, session);
        Set<SseSession> sessions = sessionsByRunId.get(session.runId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByRunId.remove(session.runId, sessions);
            }
        }
    }

    private final class SseSession {
        private final String runId;
        private final String userId;
        private final SseEmitter emitter;
        private final Object bufferLock = new Object();
        private final List<AgentRunEventResponse> liveBuffer = new ArrayList<>();
        private final AtomicBoolean doneSent = new AtomicBoolean(false);
        private final AtomicInteger lastDurableSeq = new AtomicInteger(0);
        private final StatusState statusState = new StatusState();
        private boolean initializing = true;
        private boolean overflow;
        private boolean overflowClosed;

        private SseSession(String runId, String userId, SseEmitter emitter) {
            this.runId = runId;
            this.userId = userId;
            this.emitter = emitter;
        }

        void dispatchRedisMessage(AgentRunEventResponse event) {
            boolean closeForOverflow = false;
            boolean sendDirect = false;
            synchronized (bufferLock) {
                if (initializing) {
                    if (liveBuffer.size() >= LIVE_REPLAY_BUFFER_LIMIT) {
                        if (!overflow) {
                            overflow = true;
                            overflowClosed = true;
                            closeForOverflow = true;
                        }
                    } else if (!overflow) {
                        liveBuffer.add(event);
                    }
                } else {
                    sendDirect = true;
                }
            }
            if (closeForOverflow) {
                sendErrorAndClose(emitter, "LIVE_REPLAY_BUFFER_OVERFLOW",
                        "SSE live buffer overflow; please repair via REST events");
                cleanup(emitter);
                return;
            }
            if (sendDirect) {
                try {
                    sendAgentEvent(this, event);
                } catch (Exception e) {
                    log.debug("SSE live event send failed for runId={}, closing session: {}",
                            runId, e.getMessage());
                    cleanup(emitter);
                }
            }
        }

        /** 原子完成 replay → buffered live → direct live 切换。 */
        boolean finishInitialization(int replayAfterSeq) {
            int cursor = Math.max(lastDurableSeq.get(), replayAfterSeq);
            Set<String> sentIdentities = new HashSet<>();
            while (true) {
                List<AgentRunEventResponse> buffered;
                synchronized (bufferLock) {
                    if (overflow) {
                        initializing = false;
                        if (!overflowClosed) {
                            overflowClosed = true;
                            sendErrorAndClose(emitter, "LIVE_REPLAY_BUFFER_OVERFLOW",
                                    "SSE live buffer overflow; please repair via REST events");
                        }
                        cleanup(emitter);
                        return false;
                    }
                    if (liveBuffer.isEmpty()) {
                        lastDurableSeq.accumulateAndGet(cursor, Math::max);
                        initializing = false;
                        return true;
                    }
                    buffered = new ArrayList<>(liveBuffer);
                    liveBuffer.clear();
                }

                List<AgentRunEventResponse> durableEvents = buffered.stream()
                        .filter(AgentRunEventResponse::durable)
                        .sorted(Comparator.comparingInt(AgentRunEventResponse::seq))
                        .toList();
                for (AgentRunEventResponse event : durableEvents) {
                    String identity = event.runId() + ":" + event.seq();
                    if (event.seq() <= cursor || !sentIdentities.add(identity)) {
                        continue;
                    }
                    sendAgentEvent(this, event);
                    cursor = Math.max(cursor, event.seq());
                }
                for (AgentRunEventResponse event : buffered) {
                    if (!event.durable()) {
                        sendAgentEvent(this, event);
                    }
                }
            }
        }
    }

    private int replayEvents(SseSession session, int afterSeq) {
        int cursor = afterSeq;
        while (true) {
            ListAgentRunEventsResponse page = agentDubboService.listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(session.userId)
                            .setId(session.runId)
                            .setAfterSeq(cursor)
                            .setLimit(REPLAY_PAGE_SIZE)
                            .build());
            List<AgentRunEventResponse> events = page.getItemsList().stream()
                    .map(event -> AgentEventEnvelopeMapper.fromEventMessage(objectMapper, event))
                    .sorted(Comparator.comparingInt(AgentRunEventResponse::seq))
                    .toList();
            int confirmedCursor = cursor;
            for (AgentRunEventResponse event : events) {
                if (event.seq() <= confirmedCursor) {
                    continue;
                }
                sendAgentEvent(session, event);
                confirmedCursor = event.seq();
            }
            session.lastDurableSeq.accumulateAndGet(confirmedCursor, Math::max);
            if (!page.getHasMore() || page.getItemsCount() == 0) {
                return confirmedCursor;
            }
            int nextCursor = Math.max(confirmedCursor, page.getNextAfterSeq());
            if (nextCursor <= cursor) {
                throw new IllegalStateException("Agent event replay cursor did not advance");
            }
            cursor = nextCursor;
        }
    }

    private SnapshotData buildSnapshot(String runId, String userId, boolean admin, int resumeAfterSeq) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "snapshot");
        snapshot.put("schemaVersion", AgentEventEnvelopeMapper.SCHEMA_VERSION);
        snapshot.put("runId", runId);
        snapshot.put("ts", System.currentTimeMillis());

        AgentRunStatusMessage status = loadStatus(runId, userId);
        snapshot.put("status", AgentRunTerminalStatus.normalize(status.getStatus()));
        snapshot.put("phase", emptyToNull(status.getPhase()));
        snapshot.put("startedAtMs", status.getStartedAtMs() > 0 ? status.getStartedAtMs() : null);
        snapshot.put("completedAtMs", status.getCompletedAtMs() > 0 ? status.getCompletedAtMs() : null);

        String planJson = status.getPlanJson();
        if (planJson != null && !planJson.isBlank()) {
            AgentExternalObservabilityMapper.View view = admin
                    ? AgentExternalObservabilityMapper.View.ADMIN
                    : AgentExternalObservabilityMapper.View.PLAN;
            snapshot.put("plan", AgentExternalObservabilityMapper.parse(objectMapper, planJson, view));
        }

        ListAgentRunEventsResponse response = agentDubboService.listEvents(
                ListAgentRunEventsRequest.newBuilder()
                        .setUserId(userId)
                        .setId(runId)
                        .setLimit(Math.max(1, SNAPSHOT_EVENT_COUNT))
                        .setLatest(true)
                        .build());
        int serverLastSeq = 0;
        for (AgentRunEventMessage event : response.getItemsList()) {
            serverLastSeq = Math.max(serverLastSeq, event.getSeq());
        }
        int confirmedResumeAfterSeq = Math.min(resumeAfterSeq, serverLastSeq);
        List<AgentRunEventResponse> recentEvents = new ArrayList<>();
        for (AgentRunEventMessage event : response.getItemsList()) {
            if (confirmedResumeAfterSeq > 0 && event.getSeq() > confirmedResumeAfterSeq) {
                continue;
            }
            recentEvents.add(AgentEventEnvelopeMapper.fromEventMessage(objectMapper, event));
        }
        recentEvents.sort(Comparator.comparingInt(AgentRunEventResponse::seq));
        snapshot.put("events", recentEvents);
        snapshot.put("eventCount", Math.max(0, status.getEventCount()));

        int lastSeq = confirmedResumeAfterSeq;
        for (AgentRunEventResponse event : recentEvents) {
            lastSeq = Math.max(lastSeq, event.seq());
        }
        snapshot.put("lastSeq", lastSeq);
        return new SnapshotData(snapshot, status, lastSeq);
    }

    private AgentRunStatusMessage loadStatus(String runId, String userId) {
        return agentDubboService.getStatus(
                GetAgentRunStatusRequest.newBuilder().setUserId(userId).setId(runId).build());
    }

    Map<String, Object> normalizeEnvelopeJson(String envelopeJson) {
        return AgentEventEnvelopeMapper.fromRedisEnvelope(objectMapper, envelopeJson).toWireMap();
    }

    Map<String, Object> normalizeEventMessage(AgentRunEventMessage event) {
        return AgentEventEnvelopeMapper.fromEventMessage(objectMapper, event).toWireMap();
    }

    private void sendAgentEvent(SseSession session, AgentRunEventResponse event) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name("agent.event");
        if (event.durable() && event.seq() >= 1) {
            builder.id(String.valueOf(event.seq()));
        }
        sendJsonEvent(session.emitter, builder, event.toWireMap());
        if (event.durable() && event.seq() >= 1) {
            session.lastDurableSeq.accumulateAndGet(event.seq(), Math::max);
        }
    }

    private void emitRunStatus(SseSession session, AgentRunStatusMessage status) {
        session.statusState.status = AgentRunTerminalStatus.normalize(status.getStatus());
        session.statusState.phase = emptyToNull(status.getPhase());
        sendJsonEvent(session.emitter, SseEmitter.event().name("run.status"), statusPayload(session, status));
    }

    private void emitRunStatusIfChanged(SseSession session, AgentRunStatusMessage status) {
        String statusValue = AgentRunTerminalStatus.normalize(status.getStatus());
        String phaseValue = emptyToNull(status.getPhase());
        if (equalsNullable(statusValue, session.statusState.status)
                && equalsNullable(phaseValue, session.statusState.phase)) {
            return;
        }
        session.statusState.status = statusValue;
        session.statusState.phase = phaseValue;
        sendJsonEvent(session.emitter, SseEmitter.event().name("run.status"), statusPayload(session, status));
    }

    /**
     * 终态成功发送后立即 complete + cleanup；发送失败也 fail-closed 关闭，不创建新 watcher。
     */
    private boolean emitRunDoneIfTerminal(SseSession session, AgentRunStatusMessage status) {
        String terminalStatus = AgentRunTerminalStatus.normalize(status.getStatus());
        if (!AgentRunTerminalStatus.isTerminal(terminalStatus)) {
            return false;
        }
        if (!session.doneSent.compareAndSet(false, true)) {
            return true;
        }
        try {
            Map<String, Object> payload = statusPayload(session, status);
            payload.put("type", "run.done");
            payload.put("status", terminalStatus);
            sendJsonEvent(session.emitter, SseEmitter.event().name("run.done"), payload);
            session.emitter.complete();
        } catch (Exception e) {
            log.warn("SSE run.done send failed for runId={}, closing stream: {}", session.runId, e.getMessage());
            sendErrorAndClose(session.emitter, "RUN_DONE_SEND_FAILED", "run.done 发送失败");
        } finally {
            cleanup(session.emitter);
        }
        return true;
    }

    private Map<String, Object> statusPayload(SseSession session, AgentRunStatusMessage status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "run.status");
        payload.put("runId", session.runId);
        payload.put("status", AgentRunTerminalStatus.normalize(status.getStatus()));
        payload.put("phase", emptyToNull(status.getPhase()));
        payload.put("lastSeq", Math.max(0, session.lastDurableSeq.get()));
        payload.put("startedAtMs", status.getStartedAtMs() > 0 ? status.getStartedAtMs() : null);
        payload.put("completedAtMs", status.getCompletedAtMs() > 0 ? status.getCompletedAtMs() : null);
        payload.put("ts", System.currentTimeMillis());
        return payload;
    }

    boolean isTerminalStatus(String status) {
        return AgentRunTerminalStatus.isTerminal(status);
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

    private void cleanup(SseEmitter emitter) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(emitter);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        ScheduledFuture<?> watcher = statusTasks.remove(emitter);
        if (watcher != null) {
            watcher.cancel(false);
        }
        unregisterSession(emitterSessions.get(emitter));
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private record SnapshotData(Map<String, Object> snapshot, AgentRunStatusMessage status, int lastSeq) {
    }

    private static final class StatusState {
        private String status;
        private String phase;
    }
}
