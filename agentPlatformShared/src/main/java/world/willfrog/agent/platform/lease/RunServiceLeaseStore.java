package world.willfrog.agent.platform.lease;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Run 服务所有权租约的读写。
 *
 * <p>这一层只做一件事：把「谁可以动这条 Run」写成一条会过期的数据库事实，并让领取、续期、让出
 * 都带条件。所有判定交给语句本身——读一次再写一次会在两次之间被别人插进来，跨进程的账不对。</p>
 */
public interface RunServiceLeaseStore {

    /**
     * 领取或续上这条 Run 的服务所有权。
     *
     * <p>三种情况能领到：还没有这一行（新建，代际从 1 开始）；原来的持有者就是自己（续上，
     * 代际不变）；原来那一行已经过期（接手，代际加一，旧主人的号立刻作废）。别人正活着持有
     * 时返回空——那说明这条 Run 现在不归我们动。</p>
     */
    Optional<RunServiceLease> acquire(String runId, String ownerInstanceId, Duration ttl);

    /** 续期：只有还是这一位、这一代持有才续得上；返回是否续上。 */
    boolean renew(String runId, String ownerInstanceId, long fencingToken, Duration ttl);

    /**
     * 把这一位持有的、对应 Run 还在跑的租约续一遍，返回续上的行数。
     *
     * <p>条数就是本进程正在服务或排队的 Run 数，与它自己的容量同量级，所以这是一条有界的批量写。
     * 已经结束的 Run 不再续：它们的租约到期就到期，不用一直占着。</p>
     */
    int renewOwned(String ownerInstanceId, Duration ttl);

    /**
     * 这一位持有、且对应 Run 还在跑的租约，按取得时间升序取有界一页。
     *
     * <p>续期循环在续之前先说清「本该续上几条」，续完对不上就知道中间有租约被接手了。</p>
     */
    List<RunServiceLease> listOwnedWithLiveRun(String ownerInstanceId, int limit);

    /** 让出：执行完、被拒或进终态时把所有权交回去；只有还是这一位、这一代持有才让得掉。 */
    boolean release(String runId, String ownerInstanceId, long fencingToken);

    /** 读一条租约，供观测与核对。 */
    Optional<RunServiceLease> find(String runId);

    /**
     * 已经到期、且对应 Run 还没结束的那些租约，按到期时间升序取一页。
     *
     * <p>接手扫描用它：只取有界的一页，剩下的下一轮再看，不在一次扫描里翻整张表。</p>
     */
    List<RunServiceLease> listExpiredWithLiveRun(java.time.OffsetDateTime now, int limit);
}
