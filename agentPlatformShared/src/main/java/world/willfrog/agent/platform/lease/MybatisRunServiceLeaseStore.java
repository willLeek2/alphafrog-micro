package world.willfrog.agent.platform.lease;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.mapper.RunServiceLeaseMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link RunServiceLeaseStore} 的 MyBatis 实现。
 *
 * <p>这一层只把语句的结果翻成调用方能判断的东西：领到就是领到，没领到就是没领到。不在这里
 * 「先读一次再决定」，读与写之间别人可以插进来，跨进程的账就靠不住了。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MybatisRunServiceLeaseStore implements RunServiceLeaseStore {

    private final RunServiceLeaseMapper mapper;

    @Override
    public Optional<RunServiceLease> acquire(String runId, String ownerInstanceId, Duration ttl) {
        requireRunId(runId);
        requireOwner(ownerInstanceId);
        long ttlMillis = requireTtl(ttl);
        return Optional.ofNullable(mapper.acquire(runId, ownerInstanceId, ttlMillis));
    }

    @Override
    public Optional<RunServiceLease> acquirePastDeadline(String runId, String ownerInstanceId, Duration ttl,
                                                         ServiceOwnershipScope scope, String expectedOwner) {
        requireRunId(runId);
        requireOwner(ownerInstanceId);
        requireOwner(expectedOwner);
        requireScope(scope);
        if (ownerInstanceId.equals(expectedOwner)) {
            throw new IllegalArgumentException("强制接手时期望的旧持有人不能是自己");
        }
        return Optional.ofNullable(mapper.acquirePastDeadline(
                runId, ownerInstanceId, requireTtl(ttl), scope, expectedOwner));
    }

    @Override
    public boolean renew(String runId, String ownerInstanceId, long fencingToken, Duration ttl,
                         ServiceOwnershipScope scope) {
        requireRunId(runId);
        requireOwner(ownerInstanceId);
        requireScope(scope);
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("服务所有权代际必须为正数：" + fencingToken);
        }
        return mapper.renew(runId, ownerInstanceId, fencingToken, requireTtl(ttl), scope) > 0;
    }

    @Override
    public int renewOwned(String ownerInstanceId, Duration ttl, ServiceOwnershipScope scope) {
        requireOwner(ownerInstanceId);
        requireScope(scope);
        return mapper.renewOwned(ownerInstanceId, requireTtl(ttl), scope);
    }

    @Override
    public List<RunServiceLease> listOwnedWithLiveRun(String ownerInstanceId, int limit) {
        requireOwner(ownerInstanceId);
        if (limit <= 0) {
            throw new IllegalArgumentException("清点条数必须为正数：" + limit);
        }
        return mapper.listOwnedWithLiveRun(ownerInstanceId, limit);
    }

    @Override
    public boolean release(String runId, String ownerInstanceId, long fencingToken) {
        requireRunId(runId);
        requireOwner(ownerInstanceId);
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("租约代际必须为正数：" + fencingToken);
        }
        return mapper.release(runId, ownerInstanceId, fencingToken) > 0;
    }

    @Override
    public Optional<RunServiceLease> find(String runId) {
        requireRunId(runId);
        return Optional.ofNullable(mapper.find(runId));
    }

    @Override
    public List<RunServiceLease> listExpiredWithLiveRun(OffsetDateTime now, int limit) {
        if (now == null) {
            throw new IllegalArgumentException("接手扫描必须给出现在的时刻");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("接手扫描条数必须为正数：" + limit);
        }
        return mapper.listExpiredWithLiveRun(now, limit);
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("租约必须挂在一条 Run 上");
        }
    }

    private static void requireOwner(String ownerInstanceId) {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            throw new IllegalArgumentException("租约必须留下持有者标识");
        }
    }

    private static void requireScope(ServiceOwnershipScope scope) {
        if (scope == null
                || scope.deploymentId() == null || scope.deploymentId().isBlank()
                || scope.deploymentGenerationId() == null || scope.deploymentGenerationId().isBlank()) {
            throw new IllegalArgumentException("续期和强制接手必须带上部署和代际");
        }
    }

    private static long requireTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("租约有效期必须为正：" + ttl);
        }
        return Math.max(1L, ttl.toMillis());
    }
}
