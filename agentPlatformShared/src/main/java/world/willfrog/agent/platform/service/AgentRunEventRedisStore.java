package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
public class AgentRunEventRedisStore {

    /** ZSET key: {@code agent:run:events:<runId>} */
    static final String EVENTS_KEY_PREFIX = "agent:run:events:";

    /**
     * 事件流在 Redis 里活多久。
     *
     * <p>这是这一份保留期的唯一出处：写事件流、算剩余寿命、以及按保留期回扫的补投器都读它，
     * 免得两边各自维护一个「7 天」然后慢慢漂开——漂开之后「窗口里的都补过」就不再成立。</p>
     */
    private final Duration eventsTtl;

    private static final int DEFAULT_FLUSH_BATCH_SIZE = 1;
    private static final int DEFAULT_FLUSH_STALE_MS = 3_000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLlmLocalConfigLoader llmLocalConfigLoader;

    public AgentRunEventRedisStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentLlmLocalConfigLoader llmLocalConfigLoader,
            @Value("${agent.event.redis-events-ttl-days:7}") long eventsTtlDays) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.llmLocalConfigLoader = llmLocalConfigLoader;
        this.eventsTtl = Duration.ofDays(Math.max(1L, eventsTtlDays));
    }

    /** 事件流的保留期：补投器按它决定回扫窗口。 */
    public Duration retention() {
        return eventsTtl;
    }

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

    /**
     * 把一条「库里已经有、事件流里没有」的事件补进去：只补缺的那一个，别的一概不碰。
     *
     * <p>写法与普通写入刻意有三处不同。成员已经在时一个字节都不写——普通写入每批都会把整条 Run 的
     * 事件 key 重新设成保留期，靠它补投等于每轮都把事件流的寿命往后推，保留期会越长越离谱。
     * 补进去时用 {@code ZADD NX}：与并发写撞上时不去覆盖别人已经写好的成员。只有这一条把空 key
     * 建起来（或 key 没有到期时间）时，才按事实自己的时间算一遍剩余寿命——给的是「这条事实本来
     * 还能活多久」，不是从头再算一份完整保留期。</p>
     *
     * @return 真的补进去了返回 true；成员已经在里面、或者没有可补的内容返回 false
     */
    public boolean repairMissing(AgentRunEvent event) {
        if (event == null || event.getRunId() == null || event.getRunId().isBlank()
                || event.getSeq() == null) {
            return false;
        }
        String runId = event.getRunId();
        String key = eventsKey(runId);
        String member;
        try {
            member = memberOf(event);
        } catch (Exception e) {
            throw new IllegalStateException("补投的事件成员构造不出来: runId=" + runId
                    + ", seq=" + event.getSeq(), e);
        }
        try {
            Long ttlBefore = redisTemplate.getExpire(key);
            Boolean added = redisTemplate.opsForZSet()
                    .addIfAbsent(key, member, event.getSeq().doubleValue());
            if (!Boolean.TRUE.equals(added)) {
                // 已经在里面：什么都不写，也不动这条 key 的到期时间。
                return false;
            }
            boolean keyWasMissing = ttlBefore == null || ttlBefore == -2L;
            boolean keyWithoutExpiry = ttlBefore == -1L;
            if (keyWasMissing || keyWithoutExpiry) {
                redisTemplate.expire(key, remainingRetention(event));
            }
            return true;
        } catch (Exception e) {
            String msg = String.format("补投事件到事件流失败: runId=%s, seq=%d", runId, event.getSeq());
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /** 这条事实自己还能在流里活多久：按它的发生时间算，不从此刻重新起算一份完整保留期。 */
    Duration remainingRetention(AgentRunEvent event) {
        if (event == null || event.getCreatedAt() == null) {
            return eventsTtl;
        }
        Duration remaining = Duration.between(OffsetDateTime.now(), event.getCreatedAt().plus(eventsTtl));
        return remaining.isNegative() || remaining.isZero() ? Duration.ofSeconds(1) : remaining;
    }

    private String memberOf(AgentRunEvent event) throws Exception {
        return objectMapper.writeValueAsString(toPayload(event));
    }

    private void writeBatch(String runId, List<AgentRunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        String key = eventsKey(runId);
        try {
            List<ZSetOperations.TypedTuple<String>> tuples = new ArrayList<>(events.size());
            for (AgentRunEvent event : events) {
                tuples.add(ZSetOperations.TypedTuple.of(memberOf(event), event.getSeq().doubleValue()));
            }
            redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                    ZSetOperations<String, String> zset = operations.opsForZSet();
                    zset.add(key, new HashSet<>(tuples));
                    operations.expire(key, eventsTtl);
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
