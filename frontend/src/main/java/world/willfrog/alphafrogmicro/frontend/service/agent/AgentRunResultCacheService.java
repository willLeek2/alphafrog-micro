package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AgentRunResultCacheService {

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED");

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboService;

    @Value("${agent.run-result.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    /** Role is an explicit part of both the upstream authorization request and the cache key. */
    public AgentRunResultMessage getRunResult(String userId, String runId, boolean isAdmin) {
        if (userId == null || userId.isBlank() || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("userId and runId are required");
        }
        long ttlMs = Math.max(1L, cacheTtlSeconds) * 1000L;
        long now = System.currentTimeMillis();
        String key = cacheKey(userId, runId, isAdmin);
        CacheEntry hit = cache.get(key);
        // 非终态结果不参与缓存：取消后必须重新读取，不能继续返回取消前的执行中状态。
        if (hit != null && hit.expiresAtMs() > now && isTerminal(hit.result().getStatus())) {
            return hit.result();
        }
        AgentRunResultMessage result = agentDubboService.getResult(
                GetAgentRunResultRequest.newBuilder()
                        .setUserId(userId)
                        .setId(runId)
                        .setIsAdmin(isAdmin)
                        .build()
        );
        if (isTerminal(result.getStatus())) {
            cache.put(key, new CacheEntry(result, now + ttlMs));
        } else {
            cache.remove(key);
        }
        return result;
    }

    private static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    /** 取消成功后必须立刻读到取消结果，清掉该用户该运行的用户与管理两份缓存。 */
    public void invalidate(String userId, String runId) {
        if (userId == null || userId.isBlank() || runId == null || runId.isBlank()) {
            return;
        }
        cache.remove(cacheKey(userId, runId, false));
        cache.remove(cacheKey(userId, runId, true));
    }

    void clearForTest() {
        cache.clear();
    }

    private static String cacheKey(String userId, String runId, boolean isAdmin) {
        return userId + ":" + runId + ":" + isAdmin;
    }

    private record CacheEntry(AgentRunResultMessage result, long expiresAtMs) {
    }
}
