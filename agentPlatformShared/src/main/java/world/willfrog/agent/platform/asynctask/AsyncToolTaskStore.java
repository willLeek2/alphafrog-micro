package world.willfrog.agent.platform.asynctask;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 编排调度存储包里的异步任务语义接口。
 *
 * <p>调用方只说「登记一条任务」「领到期任务」「按持有权写终态」「取消运行时留下停机意图」，
 * 不自己拼部署标识、泳道或表名。</p>
 */
public interface AsyncToolTaskStore {

    /** 登记一条任务；创建完成的状态由调用方给定，缺省为待执行。 */
    AsyncToolTask register(AsyncToolTask task);

    Optional<AsyncToolTask> findByJobId(long jobId);

    Optional<AsyncToolTask> findByOperationId(String operationId);

    /** 同一运行、同一工作项下某种工具的失败任务行，供修复判重与次数派生。 */
    List<AsyncToolTask> listFailedByWorkItem(String runId, Long workItemId, String toolName, int limit);

    /** 扫描到期的未完结任务，按 job_id 稳定分页。 */
    List<AsyncToolTask> scanDue(OffsetDateTime now, long afterJobId, int limit);

    /** 抢占到期任务；返回空表示租约仍被别人持有。 */
    Optional<AsyncToolTask> claimDue(long jobId, String owner, OffsetDateTime now,
                                     OffsetDateTime leaseUntil);

    /** 沙箱任务编号已证实，任务进入执行中。 */
    boolean attachTask(long jobId, String owner, long claimEpoch, String taskId,
                       OffsetDateTime nextPollAt, OffsetDateTime timeoutAt,
                       OffsetDateTime leaseUntil);

    /** 轮询前续租并写下一次轮询时间。 */
    boolean updateProgress(long jobId, String owner, long claimEpoch, OffsetDateTime nextPollAt,
                           int pollCount, OffsetDateTime leaseUntil);

    /** 按持有权写终态；旧执行者迟到写回返回 false。 */
    boolean markTerminal(long jobId, String owner, long claimEpoch, AsyncToolTaskState state,
                         String resultRefJson, OffsetDateTime finishedAt);

    /** 取消一笔运行：把该运行所有未完结任务标成已取消并留下待停止意图。 */
    int requestStopByRun(String runId, OffsetDateTime now);

    /** 扫描待停止或停机重试到期的任务。 */
    List<AsyncToolTask> scanStopDue(OffsetDateTime now, long afterJobId, int limit);

    /** 抢占停机动作；返回空表示别的实例已持有。 */
    Optional<AsyncToolTask> claimStop(long jobId, String owner, OffsetDateTime now,
                                      OffsetDateTime leaseUntil);

    /** 沙箱已确认停机。 */
    boolean finishStop(long jobId, String owner, long claimEpoch);

    /** 停机失败，记录次数与最后错误，等待下一次重试。 */
    boolean retryStop(long jobId, String owner, long claimEpoch, OffsetDateTime nextPollAt,
                      String lastError);
}
