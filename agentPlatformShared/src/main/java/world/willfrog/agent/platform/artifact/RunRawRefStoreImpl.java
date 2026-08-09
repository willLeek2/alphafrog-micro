package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunRawRefStoreImpl implements RunRawRefStore {

    private static final String MAPPING_KEY_PREFIX = "agent:raw-ref-mapping:";
    private static final String ARTIFACT_TYPE = "raw-ref";
    private static final String SHORT_ID_PREFIX = "raw_ref_";
    private static final int DEFAULT_MAX_LIMIT = 4000;

    private final PersistentArtifactRegistry artifactRegistry;
    private final StringRedisTemplate redisTemplate;

    @Override
    public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
        // Atomic sequence via Redis INCR, avoiding read-modify-write race.
        String counterKey = "agent:raw-ref-counter:" + runId;
        Long seq = redisTemplate.opsForValue().increment(counterKey);
        redisTemplate.expire(counterKey, ttlSeconds, TimeUnit.SECONDS);
        String shortId = SHORT_ID_PREFIX + String.format("%03d", seq);

        long ttlHours = Math.max(1, (ttlSeconds + 3599) / 3600);
        // D22-5.1.3：显式上下文入口——runId/userId 直接来自调用方参数（此前 userId 参数
        // 未使用、meta 靠 AgentContext 线程态补齐）。注意必须走非幂等 registerExplicit：
        // 本 store 的 logicalId 固定为 runId，同 run 多条 rawRef 若走幂等路径会撞身份字段。
        PersistentArtifactRegistration registration = artifactRegistry.registerExplicit(
                runId, userId, ARTIFACT_TYPE, runId, displayName, content, ttlHours);

        String mappingKey = mappingKey(runId);
        redisTemplate.opsForHash().put(mappingKey, shortId, registration.getArtifactId());
        redisTemplate.expire(mappingKey, ttlSeconds, TimeUnit.SECONDS);

        log.debug("Registered rawRef shortId={} -> artifactId={} for runId={} (seq={})",
                shortId, registration.getArtifactId(), runId, seq);
        return shortId;
    }

    @Override
    public String read(String runId, String shortId) {
        String artifactId = resolveArtifactId(runId, shortId);
        return artifactRegistry.readContent(artifactId);
    }

    @Override
    public ToolOutputReadResult read(String runId, String shortId, int offset, int limit, String keyword) {
        String artifactId = resolveArtifactId(runId, shortId);
        String content = artifactRegistry.readContent(artifactId);
        String source = filterByKeyword(content, keyword);
        int total = source.length();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int capped = limit > 0 ? limit : DEFAULT_MAX_LIMIT;
        int end = Math.min(total, safeOffset + capped);
        return ToolOutputReadResult.builder()
                .content(source.substring(safeOffset, end))
                .hasMore(end < total)
                .nextOffset(end)
                .totalLength(total)
                .build();
    }

    @Override
    public boolean belongsToRun(String runId, String shortId) {
        if (runId == null || shortId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(mappingKey(runId), shortId));
    }

    private String resolveArtifactId(String runId, String shortId) {
        Object artifactId = redisTemplate.opsForHash().get(mappingKey(runId), shortId);
        if (artifactId == null) {
            throw new IllegalArgumentException("rawRef not found: " + shortId + " for run " + runId);
        }
        return artifactId.toString();
    }

    private String mappingKey(String runId) {
        return MAPPING_KEY_PREFIX + runId;
    }

    private String filterByKeyword(String content, String keyword) {
        if (keyword == null || keyword.isBlank() || content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        return Arrays.stream(content.split("\\R", -1))
                .filter(line -> line.contains(keyword))
                .collect(Collectors.joining("\n"));
    }
}
