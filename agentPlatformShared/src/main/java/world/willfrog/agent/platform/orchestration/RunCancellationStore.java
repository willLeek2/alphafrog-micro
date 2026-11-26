package world.willfrog.agent.platform.orchestration;

import java.time.OffsetDateTime;

/**
 * 编排调度存储包里的取消入口。
 *
 * <p>一笔 PostgreSQL 事务里改运行主记录、等待组、节点工作项、未完结异步任务与停机意图，
 * 并写取消事件；方法返回即已提交。历史锚点的等待态过渡判断封在实现内部。</p>
 */
public interface RunCancellationStore {

    /**
     * 取消一笔运行。
     *
     * @param runId                  运行标识
     * @param userId                 调用者；与运行归属不匹配时抛出与
     *                               {@code ownershipGateway.requireOwnedRunForUser} 相同的异常，
     *                               不返回结果，也不写任何东西
     * @param snapshotJson           取消时的可观测快照；没有可传空
     * @param interruptedExpiresAt   中断运行的过期时刻；没有可传空
     * @return 四种结果之一，见 {@link CancelRunOutcome}
     */
    CancelRunOutcome cancelRunInTransaction(String runId, String userId,
                                            String snapshotJson,
                                            OffsetDateTime interruptedExpiresAt);
}
