package world.willfrog.agent.platform.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.AsyncToolTaskMapper;
import world.willfrog.agent.platform.mapper.RunCancellationMapper;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 取消一笔运行的默认事务实现。
 *
 * <p>整笔 PostgreSQL 事务里改运行主记录、等待组、节点工作项、未完结异步任务与停机意图；
 * 取消事件仍由控制层在方法返回后追加，不走这里，避免绕过事件的 Redis 序号。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MybatisRunCancellationStore implements RunCancellationStore {

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED");

    private final RunCancellationMapper mapper;
    private final AsyncToolTaskMapper asyncToolTaskMapper;
    private final WaitGroupStore waitGroupStore;
    private final NodeWorkItemStore nodeWorkItemStore;

    @Override
    @Transactional
    public CancelRunOutcome cancelRunInTransaction(String runId, String userId,
                                                   String snapshotJson,
                                                   OffsetDateTime interruptedExpiresAt) {
        required(runId, "运行编号");
        required(userId, "用户编号");
        CancelableRun run = mapper.lockRunForUser(runId, userId);
        if (run == null) {
            // 与现码 RunOwnershipGateway.requireOwnedRunForUser 的归属失败保持同一异常与文案。
            // 部署标识与代际仍由控制层先经 gateway 校验，本方法只校验用户归属。
            throw new IllegalStateException("原测试部署已停用");
        }
        if (run.getStatus() == null || TERMINAL_STATUSES.contains(run.getStatus())) {
            // 已终态不重试；控制层读到终态后直接返回当前运行。
            return CancelRunOutcome.NOT_APPLIED;
        }
        boolean hasUnfinishedTask = mapper.hasUnfinishedTask(runId);
        String anchorOperationId = run.getAnchorOperationId();
        boolean hasActiveAnchor = anchorOperationId != null && !anchorOperationId.isBlank();
        if (hasActiveAnchor || hasUnfinishedTask) {
            if (hasActiveAnchor) {
                // 与现码相同：先做窄合并写，关闭自动恢复并记账取消；V2 同一语句递增控制版本。
                if (mapper.persistCancelDisposition(runId, run.getStatus(), anchorOperationId) != 1) {
                    throw new IllegalStateException(
                            "cancel_anchor_cas_failed: unable to persist cancel disposition");
                }
            }
            if (mapper.snapshotIfStatus(runId, userId, run.getStatus(), snapshotJson) != 1) {
                return CancelRunOutcome.STATE_CHANGED;
            }
            cancelChildren(runId);
            return CancelRunOutcome.CANCEL_INTENT_RECORDED;
        }
        if (mapper.cancelTerminalNoActiveTask(runId, userId, snapshotJson,
                interruptedExpiresAt) != 1) {
            return CancelRunOutcome.NOT_APPLIED;
        }
        cancelChildren(runId);
        return CancelRunOutcome.TERMINAL_WRITTEN;
    }

    /** 等待组、异步任务、工作项与恢复通知在同一事务里一起收口。 */
    private void cancelChildren(String runId) {
        long afterGroupId = 0;
        while (true) {
            List<WaitGroup> groups = waitGroupStore.listOpenGroupsByRun(runId, afterGroupId, 100);
            if (groups.isEmpty()) {
                break;
            }
            for (WaitGroup group : groups) {
                // 旧等待成员与停机记录的取消仍走原有语句；新表任务由下面这一条统一收口。
                waitGroupStore.cancelChain(group.getId());
                afterGroupId = group.getId();
            }
        }
        asyncToolTaskMapper.requestStopByRun(runId);
        mapper.cancelWaitGroups(runId);
        mapper.cancelRecoveryNotifications(runId);
        cancelWorkItems(runId);
    }

    /**
     * 逐条带运行控制版本与领取轮次做条件取消，不覆盖仍在执行的领取者；随后按状态收口。
     *
     * <p>任何一条语句失败都向外抛出，让整笔事务回滚：同一事务里语句失败后事务已中止，
     * 吞掉异常再提交等于让服务端回滚，却把「已提交」的结果交给控制层。</p>
     */
    private void cancelWorkItems(String runId) {
        final String reason = "run_explicitly_canceled";
        for (NodeWorkItem item : nodeWorkItemStore.listUnfinishedByRun(runId)) {
            long runControlVersion = item.getRunControlVersion() == null ? 0L : item.getRunControlVersion();
            int claimEpoch = item.getClaimEpoch() == null ? 0 : item.getClaimEpoch();
            if (!nodeWorkItemStore.cancel(item.identity(), runControlVersion, claimEpoch,
                    reason).applied()) {
                log.warn("取消运行时工作项条件更新未命中，交由同事务收口：runId={} identity={}",
                        runId, item.identity().describe());
            }
        }
        // 列候选之后才被领取的工作项会未命中上一条；运行行锁在本事务手上，按状态一次收口。
        mapper.cancelAllUnfinishedWorkItems(runId, reason);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
