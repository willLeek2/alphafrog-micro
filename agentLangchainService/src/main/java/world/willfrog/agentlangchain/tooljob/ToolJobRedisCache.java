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
 *   <li>{@code agent:tool-job:due:identity} — runId 到 operation/owner/lease 的身份索引</li>
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
    private static final String DUE_IDENTITY_HASH_KEY =
            "agent:tool-job:due:identity";
    static final String CLAIM_PREPARING_ABORT_CLEANUP_INDEXES_SCRIPT = """
            local cached = redis.call('GET', KEYS[1])
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local dueIdentity = redis.call('HGET', KEYS[3], ARGV[1])
            if (not cached) and (not score) then
                redis.call('HDEL', KEYS[3], ARGV[1])
                return 2
            end
            local function decodeAnchor(value)
                if not value then
                    return nil
                end
                local ok, anchor = pcall(cjson.decode, value)
                if not ok then
                    return nil
                end
                return anchor
            end
            local function isAbortDisposition(anchor)
                return anchor['runDisposition'] == 'DAG_BLOCKING_NO_RESUME'
                    or anchor['runDisposition'] == 'DAG_BLOCKING_PREPARING_ABORT'
            end
            local function matchesClaimable(anchor)
                if not anchor
                    or anchor['operationId'] ~= ARGV[2]
                    or not isAbortDisposition(anchor) then
                    return false
                end
                if anchor['anchorState'] == 'CLEARING'
                    and anchor['blockingOwnerId'] == ARGV[9]
                    and anchor['blockingLeaseUntil'] == ARGV[10] then
                    return true
                end
                if ARGV[3] ~= 'CLEARING' then
                    return ARGV[3] == 'ABORTING'
                        and (anchor['anchorState'] == 'PREPARING'
                            or anchor['anchorState'] == 'ABORTING')
                        and anchor['blockingOwnerId'] == ARGV[4]
                        and anchor['blockingLeaseUntil'] == ARGV[5]
                end
                local matchesPreviousCleanup =
                    anchor['anchorState'] == 'CLEARING'
                    and anchor['blockingOwnerId'] == ARGV[4]
                    and anchor['blockingLeaseUntil'] == ARGV[5]
                local matchesPreRedisCrash =
                    (anchor['anchorState'] == 'PREPARING'
                        or anchor['anchorState'] == 'ABORTING')
                    and (anchor['runDisposition'] == 'DAG_BLOCKING_NO_RESUME'
                        or anchor['runDisposition'] == 'DAG_BLOCKING_PREPARING_ABORT')
                    and anchor['blockingOwnerId'] == ARGV[11]
                    and anchor['blockingLeaseUntil'] == ARGV[12]
                return matchesPreviousCleanup or matchesPreRedisCrash
            end
            if cached and not matchesClaimable(decodeAnchor(cached)) then
                return 0
            end
            if score and not matchesClaimable(decodeAnchor(dueIdentity)) then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[6], 'EX', ARGV[7])
            redis.call('ZADD', KEYS[2], ARGV[8], ARGV[1])
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[13])
            return 1
            """;
    static final String REMOVE_OWNED_PENDING_AND_DUE_SCRIPT = """
            local cached = redis.call('GET', KEYS[1])
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local dueIdentity = redis.call('HGET', KEYS[3], ARGV[1])
            if (not cached) and (not score) then
                redis.call('HDEL', KEYS[3], ARGV[1])
                return 2
            end
            local function matchesCleanup(value)
                if not value then
                    return false
                end
                local ok, anchor = pcall(cjson.decode, value)
                return ok
                    and anchor['operationId'] == ARGV[2]
                    and anchor['anchorState'] == 'CLEARING'
                    and anchor['runDisposition'] == ARGV[3]
                    and anchor['blockingOwnerId'] == ARGV[4]
                    and anchor['blockingLeaseUntil'] == ARGV[5]
            end
            if cached and not matchesCleanup(cached) then
                return 0
            end
            if score and not matchesCleanup(dueIdentity) then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
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
     * Add or update a run in the due ZSET and its ownership sidecar.
     */
    public void upsertDue(String runId, ToolJobAnchor anchor) {
        // ZSET score 使用数据库里的 nextPollAt；缺失时立即到期以便补扫修复。
        Instant nextPoll = anchor.getNextPollAt();
        if (nextPoll == null) {
            nextPoll = Instant.now();
        }
        String identityJson;
        try {
            identityJson = dueIdentityJson(anchor);
        } catch (RuntimeException serializationFailure) {
            log.error("Failed to serialize due identity for run={}",
                    runId, serializationFailure);
            return;
        }
        String lua = """
                redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
                redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
                return 1
                """;
        redisTemplate.execute(
                new DefaultRedisScript<>(lua, Long.class),
                java.util.List.of(DUE_ZSET_KEY, DUE_IDENTITY_HASH_KEY),
                runId,
                String.valueOf(nextPoll.toEpochMilli()),
                identityJson);
    }

    /**
     * Remove a run from the due ZSET and its ownership sidecar.
     */
    public void removeDue(String runId) {
        String lua = """
                redis.call('ZREM', KEYS[1], ARGV[1])
                redis.call('HDEL', KEYS[2], ARGV[1])
                return 1
                """;
        redisTemplate.execute(
                new DefaultRedisScript<>(lua, Long.class),
                java.util.List.of(DUE_ZSET_KEY, DUE_IDENTITY_HASH_KEY),
                runId);
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
     * Atomically write pending cache + due ZSET + due identity using a Lua script.
     * Returns true on success.
     */
    public boolean atomicWritePendingAndDue(String runId, ToolJobAnchor anchor) {
        // cache 与 ZSET 必须同成同败，避免有缓存无索引或有索引无缓存的短窗口。
        String cacheKey = pendingCacheKey(runId);
        String cacheJson;
        String identityJson;
        try {
            cacheJson = anchor.toJson();
            identityJson = dueIdentityJson(anchor);
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
                redis.call('HSET', KEYS[3], ARGV[4], ARGV[5])
                return 1
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        // due 身份与索引同成同败；即使 cache TTL 先到，也能安全判断剩余索引属于哪次任务。
        redisTemplate.execute(script,
                java.util.List.of(
                        cacheKey, DUE_ZSET_KEY, DUE_IDENTITY_HASH_KEY),
                cacheJson, String.valueOf(ttlSeconds),
                String.valueOf(score), runId, identityJson);
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
                java.util.List.of(
                        pendingCacheKey(runId),
                        DUE_ZSET_KEY,
                        DUE_IDENTITY_HASH_KEY),
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
     * takeover 接受精确旧 token/lease，或数据库首次进入 CLEARING 后、Redis 尚未更新就崩溃
     * 时冻结的原 worker 身份。暂停后恢复的过期 owner 因此无法覆盖新 token；新 operation
     * 即使错误复用 operationId，只要 owner/lease 不同也会被拒绝。</p>
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
                || cleanupAnchor.getCleanupSourceOwnerId() == null
                || cleanupAnchor.getCleanupSourceLeaseUntil() == null
                || !"CLEARING".equals(cleanupAnchor.getAnchorState())) {
            return OwnedIndexClaimResult.MISMATCHED;
        }
        String cleanupJson;
        String cleanupIdentityJson;
        try {
            cleanupJson = cleanupAnchor.toJson();
            cleanupIdentityJson = dueIdentityJson(cleanupAnchor);
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
                java.util.List.of(
                        pendingCacheKey(runId),
                        DUE_ZSET_KEY,
                        DUE_IDENTITY_HASH_KEY),
                runId,
                expectedAnchor.getOperationId(),
                expectedAnchor.getAnchorState(),
                expectedAnchor.getBlockingOwnerId(),
                expectedAnchor.getBlockingLeaseUntil().toString(),
                cleanupJson,
                String.valueOf(ttlSeconds),
                String.valueOf(score),
                cleanupAnchor.getBlockingOwnerId(),
                cleanupAnchor.getBlockingLeaseUntil().toString(),
                cleanupAnchor.getCleanupSourceOwnerId(),
                cleanupAnchor.getCleanupSourceLeaseUntil().toString(),
                cleanupIdentityJson);
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

    private static String dueIdentityJson(ToolJobAnchor anchor) {
        ToolJobAnchor identity = new ToolJobAnchor();
        identity.setOperationId(anchor.getOperationId());
        identity.setAnchorState(anchor.getAnchorState());
        identity.setRunDisposition(anchor.getRunDisposition());
        identity.setBlockingOwnerId(anchor.getBlockingOwnerId());
        identity.setBlockingLeaseUntil(anchor.getBlockingLeaseUntil());
        return identity.toJson();
    }
}
