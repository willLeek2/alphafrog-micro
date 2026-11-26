package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.asynctask.AsyncToolTask;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 异步工具任务表的 SQL 入口。
 *
 * <p>所有推进都带持有实例与领取轮次条件，影响 0 行表示调用方已经失去推进权，
 * 不得再写库。到期扫描直接读本表的部分索引，不再依赖 Redis 到期索引。</p>
 */
@Mapper
public interface AsyncToolTaskMapper {

    int insert(AsyncToolTask task);

    AsyncToolTask findByJobId(@Param("jobId") long jobId);

    AsyncToolTask findByOperationId(@Param("operationId") String operationId);

    /** 同一运行、同一工作项下某种工具的失败任务行，供修复判重与次数派生使用。 */
    List<AsyncToolTask> listFailedByWorkItem(@Param("runId") String runId,
                                             @Param("workItemId") Long workItemId,
                                             @Param("toolName") String toolName,
                                             @Param("limit") int limit);

    /** 扫描到期的未完结任务；按 job_id 稳定分页。 */
    List<AsyncToolTask> scanDue(@Param("now") OffsetDateTime now,
                                @Param("afterJobId") long afterJobId,
                                @Param("limit") int limit);

    /** 抢占到期任务的推进权；租约仍有效时影响 0 行。 */
    int claimDue(@Param("jobId") long jobId,
                 @Param("owner") String owner,
                 @Param("leaseUntil") OffsetDateTime leaseUntil,
                 @Param("now") OffsetDateTime now);

    /** 沙箱任务编号已证实，按外部作业身份把任务推进到执行中。 */
    int updateAttachedByOperation(@Param("operationId") String operationId,
                                  @Param("taskId") String taskId,
                                  @Param("nextPollAt") OffsetDateTime nextPollAt,
                                  @Param("timeoutAt") OffsetDateTime timeoutAt);

    /** 沙箱任务编号已证实，任务进入执行中并写下一次轮询时间。 */
    int attachTask(@Param("jobId") long jobId,
                   @Param("owner") String owner,
                   @Param("claimEpoch") long claimEpoch,
                   @Param("taskId") String taskId,
                   @Param("nextPollAt") OffsetDateTime nextPollAt,
                   @Param("timeoutAt") OffsetDateTime timeoutAt,
                   @Param("leaseUntil") OffsetDateTime leaseUntil);

    /** 轮询前续租并写下一次轮询时间。 */
    int updateProgress(@Param("jobId") long jobId,
                       @Param("owner") String owner,
                       @Param("claimEpoch") long claimEpoch,
                       @Param("nextPollAt") OffsetDateTime nextPollAt,
                       @Param("pollCount") int pollCount,
                       @Param("leaseUntil") OffsetDateTime leaseUntil);

    /** 按持有权写终态；旧执行者迟到写回影响 0 行。 */
    int markTerminal(@Param("jobId") long jobId,
                     @Param("owner") String owner,
                     @Param("claimEpoch") long claimEpoch,
                     @Param("state") String state,
                     @Param("resultRefJson") String resultRefJson,
                     @Param("finishedAt") OffsetDateTime finishedAt);

    /** 取消一笔运行时，把该运行所有未完结任务标成待停止；同一事务内调用。时间取数据库时钟。 */
    int requestStopByRun(@Param("runId") String runId);

    /** 扫描待停止或停机重试到期的任务。 */
    List<AsyncToolTask> scanStopDue(@Param("now") OffsetDateTime now,
                                    @Param("afterJobId") long afterJobId,
                                    @Param("limit") int limit);

    /** 抢占停机动作；复用任务租约，避免和轮询同时推进同一行。 */
    int claimStop(@Param("jobId") long jobId,
                  @Param("owner") String owner,
                  @Param("leaseUntil") OffsetDateTime leaseUntil,
                  @Param("now") OffsetDateTime now);

    /** 沙箱已确认停机，停止状态收尾。 */
    int finishStop(@Param("jobId") long jobId,
                   @Param("owner") String owner,
                   @Param("claimEpoch") long claimEpoch);

    /**
     * 停机无需继续：没有外部作业、或身份对不上不再重试。停机收尾并把原因留在最后错误里。
     */
    int abandonStop(@Param("jobId") long jobId,
                    @Param("owner") String owner,
                    @Param("claimEpoch") long claimEpoch,
                    @Param("reason") String reason);

    /** 停机失败，记录次数与最后错误，等待下一次重试。 */
    int retryStop(@Param("jobId") long jobId,
                  @Param("owner") String owner,
                  @Param("claimEpoch") long claimEpoch,
                  @Param("nextPollAt") OffsetDateTime nextPollAt,
                  @Param("lastError") String lastError);
}
