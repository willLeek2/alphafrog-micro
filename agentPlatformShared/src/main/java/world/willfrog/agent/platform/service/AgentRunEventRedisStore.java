package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRunEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed durable store for high-volume agent run events (7-day TTL).
 *
 * <p>Key layout: sorted set {@code agent:run:events:<runId>} (score = seq, member = JSON).
 * Writes are batched: every {@code K} events (from Nacos {@code eventStore.redisFlushBatchSize})
 * flush via Redis pipeline; reads always flush pending first.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunEventRedisStore {

    /** ZSET key: {@code agent:run:events:<runId>} */
    static final String EVENTS_KEY_PREFIX = "agent:run:events:";

    static final Duration EVENTS_TTL = Duration.ofDays(7);

    private static final int DEFAULT_FLUSH_BATCH_SIZE = 1;
    private static final int DEFAULT_FLUSH_STALE_MS = 3_000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLlmLocalConfigLoader llmLocalConfigLoader;

    private final ConcurrentHashMap<String, RunEventBuffer> pendingByRunId = new ConcurrentHashMap<>();

    public void append(AgentRunEvent event) {
        if (event == null || event.getRunId() == null || event.getRunId().isBlank() || event.getSeq() == null) {
            return;
        }
        String runId = event.getRunId();
        RunEventBuffer buffer = pendingByRunId.computeIfAbsent(runId, ignored -> new RunEventBuffer());
        int batchSize = resolveFlushBatchSize();
        boolean forceFlush = isTerminalEventType(event.getEventType());
        List<AgentRunEvent> toFlush;
        synchronized (buffer.lock) {
            buffer.pending.add(event);
            buffer.lastAppendAtMs = System.currentTimeMillis();
            if (!forceFlush && buffer.pending.size() < batchSize) {
                return;
            }
            toFlush = new ArrayList<>(buffer.pending);
            buffer.pending.clear();
        }
        writeBatch(runId, toFlush);
    }

    /** 兜底：长时间无新 event 的 pending buffer 仍刷入 Redis，避免 run 尾部滞留。 */
    @Scheduled(fixedDelayString = "${agent.event.redis-flush-sweep-interval-ms:2000}")
    public void flushStaleBuffers() {
        long staleBefore = System.currentTimeMillis() - resolveFlushStaleMs();
        for (String runId : List.copyOf(pendingByRunId.keySet())) {
            RunEventBuffer buffer = pendingByRunId.get(runId);
            if (buffer == null) {
                continue;
            }
            boolean shouldFlush;
            synchronized (buffer.lock) {
                shouldFlush = !buffer.pending.isEmpty() && buffer.lastAppendAtMs <= staleBefore;
            }
            if (shouldFlush) {
                flush(runId);
            }
        }
    }

    public void flush(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        RunEventBuffer buffer = pendingByRunId.get(runId);
        if (buffer == null) {
            return;
        }
        List<AgentRunEvent> toFlush;
        synchronized (buffer.lock) {
            if (buffer.pending.isEmpty()) {
                return;
            }
            toFlush = new ArrayList<>(buffer.pending);
            buffer.pending.clear();
        }
        writeBatch(runId, toFlush);
        if (buffer.pending.isEmpty()) {
            pendingByRunId.remove(runId, buffer);
        }
    }

    public boolean hasEvents(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        if (hasPending(runId)) {
            return true;
        }
        Long size = redisTemplate.opsForZSet().zCard(eventsKey(runId));
        return size != null && size > 0;
    }

    public List<AgentRunEvent> listByRunIdAfterSeq(String runId, int afterSeq, int limit) {
        flush(runId);
        if (runId == null || runId.isBlank() || limit <= 0) {
            return List.of();
        }
        double min = afterSeq + 1;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(eventsKey(runId), min, Double.POSITIVE_INFINITY, 0, limit);
        return decodeTuples(tuples);
    }

    public List<AgentRunEvent> listLatestByRunId(String runId, int limit) {
        flush(runId);
        if (runId == null || runId.isBlank() || limit <= 0) {
            return List.of();
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(eventsKey(runId), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, limit);
        List<AgentRunEvent> latest = decodeTuples(tuples);
        latest.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        return latest;
    }

    public List<AgentRunEvent> listByRunId(String runId) {
        return listByRunIdAfterSeq(runId, 0, Integer.MAX_VALUE);
    }

    public AgentRunEvent findLatestByRunId(String runId) {
        List<AgentRunEvent> latest = listLatestByRunId(runId, 1);
        return latest.isEmpty() ? null : latest.get(latest.size() - 1);
    }

    public Integer findMaxSeq(String runId) {
        flush(runId);
        if (runId == null || runId.isBlank()) {
            return null;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(eventsKey(runId), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 1);
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        Double score = tuples.iterator().next().getScore();
        return score == null ? null : score.intValue();
    }

    private boolean hasPending(String runId) {
        RunEventBuffer buffer = pendingByRunId.get(runId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer.lock) {
            return !buffer.pending.isEmpty();
        }
    }

    private int resolveFlushBatchSize() {
        return llmLocalConfigLoader.current()
                .map(AgentLlmProperties::getEventStore)
                .map(AgentLlmProperties.EventStoreConfig::getRedisFlushBatchSize)
                .map(value -> Math.max(1, value))
                .orElse(DEFAULT_FLUSH_BATCH_SIZE);
    }

    private int resolveFlushStaleMs() {
        return llmLocalConfigLoader.current()
                .map(AgentLlmProperties::getEventStore)
                .map(AgentLlmProperties.EventStoreConfig::getRedisFlushStaleMs)
                .map(value -> Math.max(500, value))
                .orElse(DEFAULT_FLUSH_STALE_MS);
    }

    private static boolean isTerminalEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        String upper = eventType.toUpperCase();
        return upper.endsWith("_COMPLETED")
                || upper.endsWith("_FAILED")
                || upper.contains("CANCELED")
                || upper.contains("CANCELLED")
                || "RUN_EXPIRED".equals(upper);
    }

    private void writeBatch(String runId, List<AgentRunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        String key = eventsKey(runId);
        try {
            List<ZSetOperations.TypedTuple<String>> tuples = new ArrayList<>(events.size());
            for (AgentRunEvent event : events) {
                tuples.add(ZSetOperations.TypedTuple.of(
                        objectMapper.writeValueAsString(toPayload(event)),
                        event.getSeq().doubleValue()));
            }
            redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                    ZSetOperations<String, String> zset = operations.opsForZSet();
                    zset.add(key, new HashSet<>(tuples));
                    operations.expire(key, EVENTS_TTL);
                    return null;
                }
            });
        } catch (Exception e) {
            String msg = String.format(
                    "Redis event batch write failed (fail-fast): runId=%s, count=%d",
                    runId,
                    events.size()
            );
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    static String eventsKey(String runId) {
        return EVENTS_KEY_PREFIX + runId;
    }

    private static final class RunEventBuffer {
        private final Object lock = new Object();
        private final List<AgentRunEvent> pending = new ArrayList<>();
        private long lastAppendAtMs = System.currentTimeMillis();
    }

    private List<AgentRunEvent> decodeTuples(Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<AgentRunEvent> events = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            AgentRunEvent event = decodeMember(tuple.getValue(), tuple.getScore());
            if (event != null) {
                events.add(event);
            }
        }
        events.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        return events;
    }

    private AgentRunEvent decodeMember(String member, Double score) {
        if (member == null || member.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(member, new TypeReference<>() {});
            AgentRunEvent event = new AgentRunEvent();
            event.setRunId(stringValue(map.get("runId")));
            Integer seq = intValue(map.get("seq"));
            if (seq == null && score != null) {
                seq = score.intValue();
            }
            event.setSeq(seq);
            event.setEventType(stringValue(map.get("eventType")));
            event.setPayloadJson(stringValue(map.get("payloadJson")));
            String createdAt = stringValue(map.get("createdAt"));
            if (createdAt != null && !createdAt.isBlank()) {
                event.setCreatedAt(OffsetDateTime.parse(createdAt));
            }
            return event;
        } catch (Exception e) {
            log.warn("[AgentRunEventRedisStore] skip corrupt member: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> toPayload(AgentRunEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", event.getRunId());
        map.put("seq", event.getSeq());
        map.put("eventType", event.getEventType());
        map.put("payloadJson", event.getPayloadJson());
        map.put("createdAt", event.getCreatedAt() == null ? null : event.getCreatedAt().toString());
        return map;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
