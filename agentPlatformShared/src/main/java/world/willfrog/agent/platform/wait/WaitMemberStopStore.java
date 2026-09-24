package world.willfrog.agent.platform.wait;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 已取消外部等待成员的持久停机任务；写操作加入调用方事务。 */
public interface WaitMemberStopStore {
    /** 领取一条到期任务；租约过期的领取也会重新开放。 */
    Optional<WaitMemberStopTask> claimDue(String owner, String claimToken,
                                          OffsetDateTime now, OffsetDateTime leaseUntil);

    /** 外调失败或任务尚未终态时，按同一固定取消身份稍后再试。 */
    boolean retry(long stopId, String claimToken, OffsetDateTime nextAttemptAt, String reason);

    /** 身份或派发证明确定冲突时停止自动发送，保留任务供人工核查。 */
    boolean blockProof(long stopId, String claimToken, String reason);

    /** 仅在 PostgreSQL 已有匹配的 Sandbox 终态凭证时确认停机。 */
    boolean confirmSandboxTerminal(long stopId, String claimToken,
                                   String taskId, String terminalStatus);

    Optional<WaitMemberStopTask> findByWaitMemberId(long waitMemberId);

    /** 按任务编号分页读取某 Run 尚未确认的停机任务，包括派发证明缺失的任务。 */
    List<WaitMemberStopTask> listUnconfirmedByRun(String runId, long afterStopId, int limit);

    /** 根 Run 自身或任一子 Run 仍有未确认停机任务时，保留整棵树的业务许可。 */
    boolean hasUnconfirmedByRootRunId(String rootRunId);
}
