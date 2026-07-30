package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobRedisCacheTest {

    @Test
    void initialAbortClaimFencesOnOriginalOwnerAndLease() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ToolJobConfig config = new ToolJobConfig();
        ToolJobRedisCache cache =
                new ToolJobRedisCache(redisTemplate, new ObjectMapper(), config);
        ToolJobAnchor expected = abortingAnchor(
                "operation-1",
                "worker-old",
                Instant.parse("2026-07-30T10:00:00Z"));
        ToolJobAnchor cleanup = clearingAnchor(
                expected,
                "cleanup-new",
                Instant.parse("2026-07-30T10:01:00Z"));

        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                eq("run-1"),
                eq("operation-1"),
                eq("ABORTING"),
                eq("worker-old"),
                eq("2026-07-30T10:00:00Z"),
                any(String.class),
                any(String.class),
                any(String.class),
                eq("cleanup-new"),
                eq("2026-07-30T10:01:00Z"),
                eq("worker-old"),
                eq("2026-07-30T10:00:00Z")))
                .thenReturn(0L);

        assertThat(cache.claimPreparingAbortCleanupIndexes(
                "run-1", expected, cleanup))
                .isEqualTo(ToolJobRedisCache.OwnedIndexClaimResult.MISMATCHED);

        ArgumentCaptor<RedisScript<Long>> scriptCaptor =
                ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                anyList(),
                eq("run-1"),
                eq("operation-1"),
                eq("ABORTING"),
                eq("worker-old"),
                eq("2026-07-30T10:00:00Z"),
                any(String.class),
                any(String.class),
                any(String.class),
                eq("cleanup-new"),
                eq("2026-07-30T10:01:00Z"),
                eq("worker-old"),
                eq("2026-07-30T10:00:00Z"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertThat(script)
                .contains("anchor['blockingOwnerId'] ~= ARGV[4]")
                .contains("anchor['blockingLeaseUntil'] ~= ARGV[5]");
    }

    @Test
    void clearingTakeoverCanClaimStaleAbortCacheOnlyByFrozenSourceIdentity() {
        String script =
                ToolJobRedisCache.CLAIM_PREPARING_ABORT_CLEANUP_INDEXES_SCRIPT;

        assertThat(script)
                .contains("local matchesPreRedisCrash")
                .contains("anchor['blockingOwnerId'] == ARGV[11]")
                .contains("anchor['blockingLeaseUntil'] == ARGV[12]")
                .contains("(not matchesPreviousCleanup) and (not matchesPreRedisCrash)");
        assertThat(script.indexOf("anchor['blockingOwnerId'] == ARGV[11]"))
                .isLessThan(script.indexOf("redis.call('SET'"));
        assertThat(script.indexOf("anchor['blockingLeaseUntil'] == ARGV[12]"))
                .isLessThan(script.indexOf("redis.call('SET'"));
    }

    @Test
    void clearingTakeoverPassesFrozenPreRedisCrashIdentityToLua() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ToolJobRedisCache cache = new ToolJobRedisCache(
                redisTemplate, new ObjectMapper(), new ToolJobConfig());
        ToolJobAnchor expected = abortingAnchor(
                "operation-1",
                "worker-old",
                Instant.parse("2026-07-30T10:00:00Z"));
        expected.setCleanupSourceOwnerId("worker-source");
        expected.setCleanupSourceLeaseUntil(
                Instant.parse("2026-07-30T09:59:00Z"));
        expected.setAnchorState("CLEARING");
        ToolJobAnchor cleanup = ToolJobAnchor.fromJson(expected.toJson());
        cleanup.setBlockingOwnerId("cleanup-new");
        cleanup.setBlockingLeaseUntil(
                Instant.parse("2026-07-30T10:01:00Z"));

        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                eq("run-1"),
                eq("operation-1"),
                eq("CLEARING"),
                eq("worker-old"),
                eq("2026-07-30T10:00:00Z"),
                any(String.class),
                any(String.class),
                any(String.class),
                eq("cleanup-new"),
                eq("2026-07-30T10:01:00Z"),
                eq("worker-source"),
                eq("2026-07-30T09:59:00Z")))
                .thenReturn(1L);

        assertThat(cache.claimPreparingAbortCleanupIndexes(
                "run-1", expected, cleanup))
                .isEqualTo(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
    }

    @Test
    void sameOperationWithChangedOwnerOrLeaseIsRejectedWithoutAClaim() {
        String script = ToolJobRedisCache.CLAIM_PREPARING_ABORT_CLEANUP_INDEXES_SCRIPT;

        assertThat(script)
                .contains("anchor['operationId'] ~= ARGV[2]")
                .contains("anchor['blockingOwnerId'] ~= ARGV[4]")
                .contains("anchor['blockingLeaseUntil'] ~= ARGV[5]")
                .contains("return 0");
        assertThat(script.indexOf("anchor['blockingOwnerId'] ~= ARGV[4]"))
                .isLessThan(script.indexOf("redis.call('SET'"));
        assertThat(script.indexOf("anchor['blockingLeaseUntil'] ~= ARGV[5]"))
                .isLessThan(script.indexOf("redis.call('SET'"));
    }

    private static ToolJobAnchor abortingAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setBlockingOwnerId(ownerId);
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setTimeoutAt(leaseUntil.plusSeconds(300));
        return anchor;
    }

    private static ToolJobAnchor clearingAnchor(
            ToolJobAnchor expected,
            String ownerId,
            Instant leaseUntil) {
        ToolJobAnchor cleanup = ToolJobAnchor.fromJson(expected.toJson());
        cleanup.setCleanupSourceOwnerId(expected.getBlockingOwnerId());
        cleanup.setCleanupSourceLeaseUntil(expected.getBlockingLeaseUntil());
        cleanup.setAnchorState("CLEARING");
        cleanup.setBlockingOwnerId(ownerId);
        cleanup.setBlockingLeaseUntil(leaseUntil);
        return cleanup;
    }
}
