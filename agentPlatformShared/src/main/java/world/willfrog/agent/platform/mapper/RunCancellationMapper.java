package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.orchestration.CancelableRun;

import java.time.OffsetDateTime;

/**
 * 取消一笔运行所需的条件更新。所有方法都在调用方的事务里执行。
 */
@Mapper
public interface RunCancellationMapper {

    /** 锁定运行行并读出取消判断所需的事实；运行不属于该用户时返回空。 */
    CancelableRun lockRunForUser(@Param("runId") String runId,
                                 @Param("userId") String userId);

    /** 有活跃异步任务时只更新快照，不改运行状态。 */
    int snapshotIfStatus(@Param("runId") String runId,
                         @Param("userId") String userId,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("snapshotJson") String snapshotJson);

    /** 新表里是否还有未完结的异步任务。 */
    boolean hasUnfinishedTask(@Param("runId") String runId);

    /** 无活跃异步任务时把运行写成取消终态，并保持旧锚点为空。 */
    int cancelTerminalNoActiveTask(@Param("runId") String runId,
                                   @Param("userId") String userId,
                                   @Param("snapshotJson") String snapshotJson,
                                   @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);

    /** 有旧锚点时写取消处置：关闭自动恢复并记账取消，第二版调度同时递增控制版本。 */
    int persistCancelDisposition(@Param("runId") String runId,
                                 @Param("expectedStatus") String expectedStatus,
                                 @Param("expectedOperationId") String expectedOperationId);

    int cancelWaitGroups(@Param("runId") String runId);

    int cancelRecoveryNotifications(@Param("runId") String runId);

    /**
     * 取消收口：本运行所有非终态工作项按状态一次改成取消，不再比对领取条件。
     *
     * <p>逐条条件取消列候选之后、执行之前被抢走的领取，条件取消会未命中。本事务持有运行行锁，
     * 终态写入已经推进控制版本，提交后新领取必然失败；提交前抢先提交的那次领取由这一条覆盖，
     * 领取者的下一次守卫检查会发现已取消并停止。</p>
     */
    int cancelAllUnfinishedWorkItems(@Param("runId") String runId,
                                     @Param("reason") String reason);
}
