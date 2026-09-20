package world.willfrog.agentlangchain.support.pool;

import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.time.Duration;
import java.util.Optional;

/**
 * 测试控制面里的显式转交：把一份活从旧执行者交给新执行者，并且不靠租约到期。
 *
 * <p>为什么不能靠租约到期换人：老 Worker 可能仍在运行，领取代际能拒绝它的数据库提交，却管不了它已经产生的
 * 外部副作用；垃圾回收停顿、线程卡顿或网络抖动都会让租约过期，而原执行还在跑。所以这里强制三步顺序——
 * 先请求暂停，再确认它报告了「已经停下」，最后才转交。等不到报告就直接报错，不做转交。</p>
 *
 * <p>转交语句本身不看租约，只看「状态在已领取或执行中」与「期望代际匹配」，所以这一组验收能证明转交是显式的、
 * 不是租约到期的副作用。</p>
 */
public final class ExplicitHandover {

    /** 等原执行者报告停止的时间上限。 */
    public static final Duration DEFAULT_STOP_WAIT = Duration.ofSeconds(5);

    /** 转交后给新执行者的租约，刻意取得比本组验收的时长长，避免被当成租约到期换人。 */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(1);

    private ExplicitHandover() {
    }

    /**
     * 显式转交。返回空表示期望代际或状态不匹配，这一行没有被交出去。
     *
     * @param oldWorker 旧执行者；必须先由它报告「已经停下」，否则这里直接抛错
     */
    public static Optional<NodeWorkItemClaim> handOver(NodeWorkItemStore store,
                                                       NodeWorkItemIdentity identity,
                                                       int expectedClaimEpoch,
                                                       String newOwner,
                                                       PausableWorker oldWorker) throws InterruptedException {
        return handOver(store, identity, expectedClaimEpoch, newOwner, oldWorker, DEFAULT_STOP_WAIT, DEFAULT_LEASE);
    }

    public static Optional<NodeWorkItemClaim> handOver(NodeWorkItemStore store,
                                                       NodeWorkItemIdentity identity,
                                                       int expectedClaimEpoch,
                                                       String newOwner,
                                                       PausableWorker oldWorker,
                                                       Duration stopWait,
                                                       Duration lease) throws InterruptedException {
        if (oldWorker != null) {
            oldWorker.pause();
            if (!oldWorker.awaitStopped(stopWait)) {
                throw new IllegalStateException(
                        "原执行者没有在 " + stopWait.toSeconds() + " 秒内报告停止，不做转交：" + identity.describe());
            }
        }
        return store.handOverClaim(identity, expectedClaimEpoch, newOwner, lease);
    }
}
