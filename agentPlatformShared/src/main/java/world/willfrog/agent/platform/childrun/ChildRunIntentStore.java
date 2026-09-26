package world.willfrog.agent.platform.childrun;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 子执行的持久创建意图。所有写方法加入调用方现有事务；父等待组挂起与预留必须同事务提交。
 * 子 Run 主记录的创建与 {@link #markAccepted} 也必须同事务提交。
 */
public interface ChildRunIntentStore {
    ChildRunReservation reserveIntent(ChildRunReserveRequest request, int maxActiveChildren);

    Optional<ChildRunOutboxDelivery> claimDueOutbox(String owner, String claimToken,
                                                    OffsetDateTime now, OffsetDateTime leaseUntil);

    Optional<ChildRunOutboxDelivery> claimDueOutboxForDeployment(String owner, String claimToken,
            OffsetDateTime now, OffsetDateTime leaseUntil, String deploymentId, String generationId);

    /** 校验父控制版本与取消状态并确认受理；失败时调用方不得创建子 Run。 */
    boolean markAccepted(long outboxId, String claimToken);

    /** 父控制版本变化或取消后，尚未受理的意图停止投递并归还预留。 */
    boolean cancelUnacceptedIfParentChanged(long intentId);

    /** 已受理子 Run 收到父取消请求；仅记录请求，不释放实际在用容量。 */
    boolean requestCancellation(String childRunId);

    /** 子 Run 进入终态。只有物理执行也已停止时才归还树级容量。 */
    boolean markChildTerminal(String childRunId);

    /**
     * 仅当子 Run 正常终态、工具锚点已清理、没有在途工作项或后代，且每个外部作业成员
     * 已正常结束并有匹配的派发证明、成员结果与 Sandbox 终态用量记录时，才记录物理停止。
     * 取消或证明缺失会保留树容量，等待独立的停机确认入口。
     */
    boolean markPhysicalStopped(String childRunId);

    /** 普通根 Run 返回自身；尚未受理、身份不完整或不存在的子 Run 返回空。查询失败直接抛错。 */
    Optional<String> rootRunIdOf(String runId);

    /** 尚有未受理或未同时确认终态与物理停止的后代时为 true。 */
    boolean hasUnsettledDescendants(String rootRunId);

    /** 按根 Run 编号分页扫描仍占树级容量的根，供启动恢复重建业务准入。 */
    List<String> listReservedRootRunIds(String afterRootRunId, int limit);

    List<String> listReservedRootRunIdsForDeployment(String afterRootRunId, int limit,
            String deploymentId, String generationId);

    /** 启动后按意图编号扫描已受理但子 Run 尚未终态的记录；调用方需核工作项后补投。 */
    List<ChildRunIntentView> listAcceptedChildrenNeedingLaunch(long afterIntentId, int limit);

    List<ChildRunIntentView> listAcceptedChildrenNeedingLaunchForDeployment(long afterIntentId, int limit,
            String deploymentId, String generationId);

    /** 按父 Run 扫描仍占根树容量的子执行，供取消传播与收尾。 */
    List<ChildRunIntentView> listUnsettledByParent(String parentRunId, long afterIntentId, int limit);

    /** 按根 Run 扫描全部未结清的后代，供启动恢复与整棵树取消。 */
    List<ChildRunIntentView> listUnsettledByRoot(String rootRunId, long afterIntentId, int limit);

    /** 子执行已受理但原 spawn 工具成员还未收到结果时，按意图编号补扫。 */
    List<ChildRunIntentView> listAcceptedSpawnMembersPending(long afterIntentId, int limit);

    /** 仅补扫本部署代际的父 Run，避免共享库里其他部署的意图占满分页。 */
    default List<ChildRunIntentView> listAcceptedSpawnMembersPending(String deploymentId,
                                                                       String deploymentGenerationId,
                                                                       long afterIntentId, int limit) {
        throw new UnsupportedOperationException("deployment-scoped spawn member scan is not implemented");
    }

    /** 子 Run 终态回接时按稳定 childRunId 读取父等待成员身份。 */
    Optional<ChildRunIntentView> findByChildRunId(String childRunId);
}
