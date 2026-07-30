package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * pending 工具任务的 Redis 热副本与到期索引。
 * <p>键布局：</p>
 * <ul>
 *   <li>{@code agent:run:{runId}:pending_tool_job} — JSON cache of pending job state</li>
 *   <li>{@code agent:tool-job:due} — ZSET scored by nextPollAt epoch millis</li>
 * </ul>
 * <p>PostgreSQL {@code tool_job_anchor_json} 始终是真相源；本类写失败不会丢上下文，
 * startup recovery/reconciler 会从数据库重建。Redis 只避免全表扫描并降低轮询延迟。</p>
 */
@Service
public class ToolJobRedisCache {

    private static final Logger log = LoggerFactory.getLogger(ToolJobRedisCache.class);

    private static final String PENDING_CACHE_PREFIX = "agent:run:";
    private static final String PENDING_CACHE_SUFFIX = ":pending_tool_job";
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";
    static final String CLAIM_PREPARING_ABORT_CLEANUP_INDEXES_SCRIPT = """
            local cached = redis.call('GET', KEYS[1])
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if (not cached) and (not score) then
                return 2
            end
            if (not cached) or (not score) then
                return 0
            end
            local ok, anchor = pcall(cjson.decode, cached)
            if not ok then
                return 0
            end
            if anchor['operationId'] == ARGV[2]
                and anchor['anchorState'] == 'CLEARING'
                and anchor['blockingOwnerId'] == ARGV[9]
                and anchor['blockingLeaseUntil'] == ARGV[10] then
                return 1
            end
            if anchor['operationId'] ~= ARGV[2] then
                return 0
            end
            if ARGV[3] == 'CLEARING' then
                if anchor['anchorState'] ~= 'CLEARING'
                    or anchor['blockingOwnerId'] ~= ARGV[4]
                    or anchor['blockingLeaseUntil'] ~= ARGV[5] then
                    return 0
                end
            else
                if ARGV[3] ~= 'ABORTING'
                    or (anchor['anchorState'] ~= 'PREPARING'
                        and anchor['anchorState'] ~= 'ABORTING')
                    or (anchor['runDisposition'] ~= 'DAG_BLOCKING_NO_RESUME'
                        and anchor['runDisposition'] ~= 'DAG_BLOCKING_PREPARING_ABORT')
                    or anchor['blockingOwnerId'] ~= ARGV[4]
                    or anchor['blockingLeaseUntil'] ~= ARGV[5] then
                    return 0
                end
            end
            redis.call('SET', KEYS[1], ARGV[6], 'EX', ARGV[7])
            redis.call('ZADD', KEYS[2], ARGV[8], ARGV[1])
            return 1
            """;
    static final String REMOVE_OWNED_PENDING_AND_DUE_SCRIPT = """
            local cached = redis.call('GET', KEYS[1])
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if (not cached) and (not score) then
                return 2
            end
            if (not cached) or (not score) then
                return 0
            end
            local ok, anchor = pcall(cjson.decode, cached)
            if (not ok)
                or anchor['operationId'] ~= ARGV[2]
                or anchor['anchorState'] ~= 'CLEARING'
                or anchor['runDisposition'] ~= ARGV[3]
                or anchor['blockingOwnerId'] ~= ARGV[4]
                or anchor['blockingLeaseUntil'] ~= ARGV[5] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """;

    public enum OwnedIndexClaimResult {
        CLAIMED,
        ALREADY_CLEAN,
        MISMATCHED
    }

    public enum OwnedIndexDeleteResult {
        REMOVED,
        ALREADY_CLEAN,
        MISMATCHED
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ToolJobConfig config;

    public ToolJobRedisCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, ToolJobConfig config) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ---- pending cache ----

    static String pendingCacheKey(String runId) {
        return PENDING_CACHE_PREFIX + runId + PENDING_CACHE_SUFFIX;
    }

    public void writePendingCache(String runId, ToolJobAnchor anchor) {
        // 每个 Run 一个热副本键，不与 due ZSET 的调度分数混在同一结构。
        String key = pendingCacheKey(runId);
        try {
            // 缓存完整 anchor 便于诊断，但业务 CAS 仍要回 PostgreSQL。
            String json = anchor.toJson();
            // TTL 覆盖任务 timeout 与终态保留窗口，且至少 60 秒。
            long ttlSeconds = calculateTtlSeconds(anchor);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.error("Failed to serialize pending cache for run={}", runId, e);
        }
    }

    public ToolJobAnchor readPendingCache(String runId) {
        String json = redisTemplate.opsForValue().get(pendingCacheKey(runId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ToolJobAnchor.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize pending cache for run={}", runId, e);
            return null;
        }
    }

    public void deletePendingCache(String runId) {
        redisTemplate.delete(pendingCacheKey(runId));
    }

    // ---- due ZSET ----

    /**
     * Add or update a run in the due ZSET, scored by the anchor's nextPollAt.
     */
    public void upsertDue(String runId, ToolJobAnchor anchor) {
        // ZSET score 使用 durable nextPollAt；缺失时立即到期以便补扫修复。
        Instant nextPoll = anchor.getNextPollAt();
        if (nextPoll == null) {
            nextPoll = Instant.now();
        }
        redisTemplate.opsForZSet().add(DUE_ZSET_KEY, runId, nextPoll.toEpochMilli());
    }

    /**
     * Remove a run from the due ZSET (e.g. after terminal cleanup).
     */
    public void removeDue(String runId) {
        redisTemplate.opsForZSet().remove(DUE_ZSET_KEY, runId);
    }

    /**
     * Fetch due runs whose nextPollAt <= now, up to limit.
     */
    public Set<String> fetchDue(int limit) {
        // 只取 score<=now 的前 limit 个成员，限制单轮 reconciler 压力。
        Set<String> members = redisTemplate.opsForZSet()
                .rangeByScore(DUE_ZSET_KEY, 0, System.currentTimeMillis(), 0, limit);
        return members != null ? members : Collections.emptySet();
    }

    /**
     * Atomically write pending cache + due ZSET using a Lua script.
     * Returns true on success.
     */
    public boolean atomicWritePendingAndDue(String runId, ToolJobAnchor anchor) {
        // cache 与 ZSET 必须同成同败，避免有缓存无索引或有索引无缓存的短窗口。
        String cacheKey = pendingCacheKey(runId);
        String cacheJson;
        try {
            cacheJson = anchor.toJson();
        } catch (RuntimeException e) {
            log.error("Failed to serialize anchor for atomic write, run={}", runId, e);
            return false;
        }
        // TTL/score 都从同一 anchor 快照计算。
        long ttlSeconds = calculateTtlSeconds(anchor);
        long score = anchor.getNextPollAt() != null
                ? anchor.getNextPollAt().toEpochMilli()
                : System.currentTimeMillis();

        // Lua 在 Redis 单线程内原子执行 SET + ZADD；不提供数据库所有权语义。
        String lua = """
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
                return 1
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        // KEYS 只包含当前 Run cache 与全局 due ZSET，ARGV 传序列化值和时间。
        redisTemplate.execute(script,
                java.util.List.of(cacheKey, DUE_ZSET_KEY),
                cacheJson, String.valueOf(ttlSeconds),
                String.valueOf(score), runId);
        return true;
    }

    /**
     * 仅删除仍属于同一 operation/CLEARING token/lease 的 Redis 派生索引。
     *
     * <p>脚本把身份核对与两个删除放在同一个 Redis 原子步骤里。过期 cleanup
     * owner 即使在暂停后恢复，也不能删除 takeover owner 或新 operation 的索引。</p>
     */
    public OwnedIndexDeleteResult removePendingAndDueIfMatches(
            String runId,
            String expectedOperationId,
            String expectedDisposition,
            String expectedOwnerId,
            Instant expectedLeaseUntil) {
        if (runId == null || runId.isBlank()
                || expectedOperationId == null || expectedOperationId.isBlank()
                || expectedDisposition == null || expectedDisposition.isBlank()
                || expectedOwnerId == null || expectedOwnerId.isBlank()
                || expectedLeaseUntil == null) {
            return OwnedIndexDeleteResult.MISMATCHED;
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                REMOVE_OWNED_PENDING_AND_DUE_SCRIPT,
                Long.class);
        Long result = redisTemplate.execute(
                script,
                java.util.List.of(pendingCacheKey(runId), DUE_ZSET_KEY),
                runId,
                expectedOperationId,
                expectedDisposition,
                expectedOwnerId,
                expectedLeaseUntil.toString());
        if (Long.valueOf(1L).equals(result)) {
            return OwnedIndexDeleteResult.REMOVED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return OwnedIndexDeleteResult.ALREADY_CLEAN;
        }
        return OwnedIndexDeleteResult.MISMATCHED;
    }

    /**
     * 把旧 abort 的 Redis 热副本原子推进到当前 CLEARING token。
     *
     * <p>初次 claim 只接受同一 operation、owner、lease 的 PREPARING/ABORTING 热副本；
     * takeover 也只接受精确旧 token/lease。暂停后恢复的过期 owner 因此无法覆盖新 token，
     * 即使 operationId 被错误复用也会 fail closed。</p>
     */
    public OwnedIndexClaimResult claimPreparingAbortCleanupIndexes(
            String runId,
            ToolJobAnchor expectedAnchor,
            ToolJobAnchor cleanupAnchor) {
        if (runId == null || runId.isBlank()
                || expectedAnchor == null
                || cleanupAnchor == null
                || expectedAnchor.getOperationId() == null
                || expectedAnchor.getBlockingOwnerId() == null
                || expectedAnchor.getBlockingLeaseUntil() == null
                || cleanupAnchor.getBlockingOwnerId() == null
                || cleanupAnchor.getBlockingLeaseUntil() == null
                || !"CLEARING".equals(cleanupAnchor.getAnchorState())) {
            return OwnedIndexClaimResult.MISMATCHED;
        }
        String cleanupJson;
        try {
            cleanupJson = cleanupAnchor.toJson();
        } catch (RuntimeException serializationFailure) {
            log.error("Failed to serialize PREPARING abort cleanup cache for run={}",
                    runId, serializationFailure);
            return OwnedIndexClaimResult.MISMATCHED;
        }
        long ttlSeconds = calculateTtlSeconds(cleanupAnchor);
        long score = cleanupAnchor.getNextPollAt() != null
                ? cleanupAnchor.getNextPollAt().toEpochMilli()
                : System.currentTimeMillis();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                CLAIM_PREPARING_ABORT_CLEANUP_INDEXES_SCRIPT,
                Long.class);
        Long result = redisTemplate.execute(
                script,
                java.util.List.of(pendingCacheKey(runId), DUE_ZSET_KEY),
                runId,
                expectedAnchor.getOperationId(),
                expectedAnchor.getAnchorState(),
                expectedAnchor.getBlockingOwnerId(),
                expectedAnchor.getBlockingLeaseUntil().toString(),
                cleanupJson,
                String.valueOf(ttlSeconds),
                String.valueOf(score),
                cleanupAnchor.getBlockingOwnerId(),
                cleanupAnchor.getBlockingLeaseUntil().toString());
        if (Long.valueOf(1L).equals(result)) {
            return OwnedIndexClaimResult.CLAIMED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return OwnedIndexClaimResult.ALREADY_CLEAN;
        }
        return OwnedIndexClaimResult.MISMATCHED;
    }

    // ---- helpers ----

    private long calculateTtlSeconds(ToolJobAnchor anchor) {
        // 历史 anchor 无 timeoutAt 时使用默认任务超时加终态保留期。
        Instant timeout = anchor.getTimeoutAt();
        if (timeout == null) {
            return config.getDefaultTimeoutSeconds() + config.getTerminalRetentionSeconds();
        }
        // 已临近/超过 timeout 的任务仍保留至少 60 秒，给 finalizer/recovery 清理窗口。
        long ttl = Duration.between(Instant.now(), timeout).toSeconds()
                + config.getTerminalRetentionSeconds();
        return Math.max(60, ttl);
    }
}
