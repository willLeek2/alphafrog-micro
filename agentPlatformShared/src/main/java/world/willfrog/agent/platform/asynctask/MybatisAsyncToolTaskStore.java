package world.willfrog.agent.platform.asynctask;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.mapper.AsyncToolTaskMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 异步工具任务存储包的默认实现。
 *
 * <p>写路径一律按持有实例与领取轮次做条件更新；影响 0 行表示调用方已经失去推进权，
 * 这里只返回 false，不重试、不改写。</p>
 */
@Service
@RequiredArgsConstructor
public class MybatisAsyncToolTaskStore implements AsyncToolTaskStore {

    private final AsyncToolTaskMapper mapper;

    @Override
    public AsyncToolTask register(AsyncToolTask task) {
        if (task == null) {
            throw new IllegalArgumentException("异步工具任务不能为空");
        }
        required(task.getJobKind(), "任务种类");
        required(task.getRunId(), "运行编号");
        required(task.getToolName(), "工具名");
        if (task.getState() == null) {
            task.setState(AsyncToolTaskState.PENDING.name());
        }
        if (task.getRequestJson() == null) {
            task.setRequestJson("{}");
        }
        if (task.getResultRefJson() == null) {
            task.setResultRefJson("{}");
        }
        if (task.getSchemaVersion() == null) {
            task.setSchemaVersion(1);
        }
        if (task.getClaimEpoch() == null) {
            task.setClaimEpoch(0L);
        }
        if (task.getPollCount() == null) {
            task.setPollCount(0);
        }
        if (task.getStopAttemptCount() == null) {
            task.setStopAttemptCount(0);
        }
        if (task.getNextPollAt() == null) {
            task.setNextPollAt(OffsetDateTime.now());
        }
        mapper.insert(task);
        if (task.getJobId() == null) {
            throw new IllegalStateException("异步工具任务写入后没有返回任务编号");
        }
        return task;
    }

    @Override
    public Optional<AsyncToolTask> findByJobId(long jobId) {
        positive(jobId);
        return Optional.ofNullable(mapper.findByJobId(jobId));
    }

    @Override
    public Optional<AsyncToolTask> findByOperationId(String operationId) {
        required(operationId, "外部作业身份");
        return Optional.ofNullable(mapper.findByOperationId(operationId));
    }

    @Override
    public List<AsyncToolTask> listFailedByWorkItem(String runId, Long workItemId,
                                                    String toolName, int limit) {
        required(runId, "运行编号");
        required(toolName, "工具名");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("失败任务分页参数无效");
        }
        return mapper.listFailedByWorkItem(runId, workItemId, toolName, limit);
    }

    @Override
    public List<AsyncToolTask> scanDue(OffsetDateTime now, long afterJobId, int limit) {
        if (now == null || afterJobId < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("到期任务扫描参数无效");
        }
        return mapper.scanDue(now, afterJobId, limit);
    }

    @Override
    public Optional<AsyncToolTask> claimDue(long jobId, String owner, OffsetDateTime now,
                                            OffsetDateTime leaseUntil) {
        positive(jobId);
        required(owner, "持有实例");
        requireLease(now, leaseUntil);
        if (mapper.claimDue(jobId, owner, leaseUntil, now) != 1) {
            return Optional.empty();
        }
        return findByJobId(jobId);
    }

    @Override
    public boolean attachTask(long jobId, String owner, long claimEpoch, String taskId,
                              OffsetDateTime nextPollAt, OffsetDateTime timeoutAt,
                              OffsetDateTime leaseUntil) {
        positive(jobId);
        required(owner, "持有实例");
        required(taskId, "沙箱任务编号");
        if (nextPollAt == null || timeoutAt == null || leaseUntil == null
                || !leaseUntil.isAfter(nextPollAt)) {
            throw new IllegalArgumentException("任务时间参数无效");
        }
        return mapper.attachTask(jobId, owner, claimEpoch, taskId, nextPollAt, timeoutAt, leaseUntil) == 1;
    }

    @Override
    public boolean updateProgress(long jobId, String owner, long claimEpoch,
                                  OffsetDateTime nextPollAt, int pollCount,
                                  OffsetDateTime leaseUntil) {
        positive(jobId);
        required(owner, "持有实例");
        if (nextPollAt == null || leaseUntil == null || pollCount < 0
                || !leaseUntil.isAfter(nextPollAt)) {
            throw new IllegalArgumentException("轮询时间参数无效");
        }
        return mapper.updateProgress(jobId, owner, claimEpoch, nextPollAt, pollCount, leaseUntil) == 1;
    }

    @Override
    public boolean markTerminal(long jobId, String owner, long claimEpoch,
                                AsyncToolTaskState state, String resultRefJson,
                                OffsetDateTime finishedAt) {
        positive(jobId);
        required(owner, "持有实例");
        if (state == null || !state.isTerminal() || finishedAt == null) {
            throw new IllegalArgumentException("终态参数无效");
        }
        String result = resultRefJson == null || resultRefJson.isBlank() ? "{}" : resultRefJson;
        return mapper.markTerminal(jobId, owner, claimEpoch, state.name(), result, finishedAt) == 1;
    }

    @Override
    public int requestStopByRun(String runId) {
        required(runId, "运行编号");
        return mapper.requestStopByRun(runId);
    }

    @Override
    public List<AsyncToolTask> scanStopDue(OffsetDateTime now, long afterJobId, int limit) {
        if (now == null || afterJobId < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("停机扫描参数无效");
        }
        return mapper.scanStopDue(now, afterJobId, limit);
    }

    @Override
    public Optional<AsyncToolTask> claimStop(long jobId, String owner, OffsetDateTime now,
                                             OffsetDateTime leaseUntil) {
        positive(jobId);
        required(owner, "持有实例");
        requireLease(now, leaseUntil);
        if (mapper.claimStop(jobId, owner, leaseUntil, now) != 1) {
            return Optional.empty();
        }
        return findByJobId(jobId);
    }

    @Override
    public boolean finishStop(long jobId, String owner, long claimEpoch) {
        positive(jobId);
        required(owner, "持有实例");
        return mapper.finishStop(jobId, owner, claimEpoch) == 1;
    }

    @Override
    public boolean abandonStop(long jobId, String owner, long claimEpoch, String reason) {
        positive(jobId);
        required(owner, "持有实例");
        required(reason, "停机收尾原因");
        String bounded = reason.length() > 512 ? reason.substring(0, 512) : reason;
        return mapper.abandonStop(jobId, owner, claimEpoch, bounded) == 1;
    }

    @Override
    public boolean retryStop(long jobId, String owner, long claimEpoch,
                             OffsetDateTime nextPollAt, String lastError) {
        positive(jobId);
        required(owner, "持有实例");
        required(lastError, "停机失败原因");
        if (nextPollAt == null || nextPollAt.isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("停机重试时刻必须晚于现在");
        }
        String bounded = lastError.length() > 512 ? lastError.substring(0, 512) : lastError;
        return mapper.retryStop(jobId, owner, claimEpoch, nextPollAt, bounded) == 1;
    }

    private static void requireLease(OffsetDateTime now, OffsetDateTime leaseUntil) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("任务租期必须晚于领取时刻");
        }
    }

    private static void positive(long jobId) {
        if (jobId <= 0) {
            throw new IllegalArgumentException("任务编号必须为正数");
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
