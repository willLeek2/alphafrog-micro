package world.willfrog.agentlangchain.tooljob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;

import java.time.Instant;

/**
 * executePython 分发阶段的 PostgreSQL 真相源实现。
 * PREPARING、ATTACHED、PENDING 三次推进都先写 DB；只有 PENDING 成功后才派生
 * Redis 索引（跨进程恢复模式）或登记进程内 continuation tracker（默认模式）。
 */
@Service
@Slf4j
public class PythonSandboxDispatchStoreImpl implements PythonSandboxDispatchStore {

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final ToolJobConfig config;
    private final ObjectProvider<ToolJobContinuationTracker> trackerProvider;
    private final LangchainSchedulerMetrics metrics;

    public PythonSandboxDispatchStoreImpl(ToolJobAnchorService anchorService,
                                          ToolJobRedisCache redisCache,
                                          ToolJobConfig config,
                                          ObjectProvider<ToolJobContinuationTracker> trackerProvider,
                                          LangchainSchedulerMetrics metrics) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.config = config;
        this.trackerProvider = trackerProvider;
        this.metrics = metrics;
    }

    @Override
    public boolean persistPreparing(String runId, ToolJobAnchor anchor) {
        // 调用方必须明确提供 PREPARING；claim SQL 还要求当前 Run anchor 为空。
        return "PREPARING".equals(anchor.getAnchorState())
                && anchorService.claimPreparing(runId, anchor, AgentRunStatus.EXECUTING);
    }

    @Override
    public boolean persistPreparingFromResume(String runId,
                                              ToolJobAnchor anchor,
                                              String expectedResumeToken,
                                              long expectedResumeLeaseVersion) {
        return "PREPARING".equals(anchor.getAnchorState())
                && anchorService.claimPreparingFromResume(
                runId, anchor, expectedResumeToken, expectedResumeLeaseVersion);
    }

    @Override
    public boolean persistAttached(String runId, ToolJobAnchor anchor) {
        // fast-path 可能直接推进 TERMINAL，因此 ATTACHED/TERMINAL 都按 operationId 更新 active anchor。
        if (!"ATTACHED".equals(anchor.getAnchorState())
                && !"TERMINAL".equals(anchor.getAnchorState())) {
            return false;
        }
        if (ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
            return anchorService.updateLiveDagBlocking(
                    runId,
                    anchor,
                    AgentRunStatus.EXECUTING,
                    anchor.getOperationId(),
                    anchor.getBlockingOwnerId(),
                    anchor.getBlockingLeaseUntil());
        }
        return anchorService.updateActive(
                runId, anchor, AgentRunStatus.EXECUTING, anchor.getOperationId());
    }

    @Override
    public boolean transferToPending(String runId, ToolJobAnchor anchor) {
        // PENDING anchor 已把占用的资源名额转给后台任务继续管理，此时才能释放 Agent worker。
        if (!"PENDING".equals(anchor.getAnchorState())) {
            return false;
        }
        // 一条 SQL 同时更新 anchor 与 Run 状态（EXECUTING→WAITING_TOOL_JOB），要么都成功、要么都不改，消除半状态窗口。
        boolean durable = anchorService.updateActiveAndStatus(
                runId, anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, anchor.getOperationId());
        if (!durable) {
            return false;
        }
        // 写库成功后，上层会收到挂起信号（通知任务已转后台）并释放 worker 线程；两种模式都记指标。
        metrics.recordWorkerReleased();
        if (config.isDurableRecoveryEnabled()) {
            // 跨进程恢复模式：写 Redis due，由 ToolJobReconciler 跨进程发现终态。
            try {
                redisCache.atomicWritePendingAndDue(runId, anchor);
            } catch (Exception cacheFailure) {
                log.warn("Pending Redis derivative write failed for run={}, durable anchor will rebuild it: {}",
                        runId, cacheFailure.getMessage());
            }
        } else {
            // 进程内续接模式：登记 tracker，它按 pollInterval 轮询 Sandbox 终态。
            ToolJobContinuationTracker tracker = trackerProvider.getIfAvailable();
            if (tracker == null) {
                // 同一开关下 tracker bean 必然存在；缺失属于装配错误，直接判失败、不释放 worker。
                log.error("Continuation tracker unavailable for run={} but durable recovery is off; "
                        + "run will not be resumed in-process", runId);
                return false;
            }
            tracker.register(runId, anchor);
        }
        // 返回 true 后，上层抛出挂起信号（通知任务已转后台）并释放 worker 线程。
        return true;
    }

    @Override
    public boolean clearActive(String runId, String operationId) {
        return anchorService.clearActive(runId, AgentRunStatus.EXECUTING, operationId);
    }

    @Override
    public boolean clearSynchronouslyCompleted(String runId, String operationId) {
        ToolJobAnchor current = anchorService.loadAnchor(runId);
        if (current == null) {
            return false;
        }
        if (!ToolJobRunDisposition.isDagBlocking(current.getRunDisposition())) {
            return anchorService.clearSynchronouslyCompleted(
                    runId, AgentRunStatus.EXECUTING, operationId);
        }
        if (!ToolJobRunDisposition.isLiveDagBlocking(current.getRunDisposition())
                || !operationId.equals(current.getOperationId())
                || !DagBlockingWorkerLease.processOwnerId()
                        .equals(current.getBlockingOwnerId())
                || current.getBlockingLeaseUntil() == null) {
            return false;
        }
        return anchorService.clearLiveDagBlockingSynchronouslyCompleted(
                runId,
                operationId,
                current.getBlockingOwnerId(),
                current.getBlockingLeaseUntil());
    }

    @Override
    public boolean renewDagBlockingLease(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil) {
        if (!ToolJobRunDisposition.isLiveDagBlocking(anchor.getRunDisposition())) {
            return false;
        }
        return anchorService.updateLiveDagBlocking(
                runId,
                anchor,
                AgentRunStatus.EXECUTING,
                anchor.getOperationId(),
                anchor.getBlockingOwnerId(),
                expectedLeaseUntil);
    }

    @Override
    public boolean promoteDagBlockingWorkerLost(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil) {
        if (!ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
            return false;
        }
        boolean promoted = anchorService.updateLiveDagBlocking(
                runId,
                anchor,
                AgentRunStatus.EXECUTING,
                anchor.getOperationId(),
                anchor.getBlockingOwnerId(),
                expectedLeaseUntil);
        if (!promoted) {
            return false;
        }
        // PostgreSQL 是 owner 的权威来源；Redis 只加速 cleanup，失败后可由备用扫描重建。
        try {
            redisCache.atomicWritePendingAndDue(runId, anchor);
        } catch (Exception cacheFailure) {
            log.warn("DAG cleanup Redis derivative write failed for run={}, durable marker remains: {}",
                    runId, cacheFailure.getMessage());
        }
        return true;
    }

    @Override
    public boolean beginDagBlockingPreparingAbort(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil) {
        if (!"ABORTING".equals(anchor.getAnchorState())
                || !ToolJobRunDisposition.isDagPreparingAbort(
                        anchor.getRunDisposition())) {
            return false;
        }
        return anchorService.beginLiveDagBlockingPreparingAbort(
                runId,
                anchor,
                AgentRunStatus.EXECUTING,
                anchor.getOperationId(),
                anchor.getBlockingOwnerId(),
                expectedLeaseUntil);
    }

    @Override
    public boolean completeDagBlockingPreparingAbort(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil) {
        if (!"ABORTING".equals(anchor.getAnchorState())
                || !ToolJobRunDisposition.isDagPreparingAbort(
                        anchor.getRunDisposition())
                || expectedLeaseUntil == null
                || !expectedLeaseUntil.equals(anchor.getBlockingLeaseUntil())) {
            return false;
        }
        ToolJobPreparingAbortRecoveryService.Outcome outcome =
                new ToolJobPreparingAbortRecoveryService()
                        .completeAcceptedRelease(
                                runId,
                                anchor,
                                anchorService,
                                redisCache);
        return outcome == ToolJobPreparingAbortRecoveryService.Outcome.COMPLETED;
    }
}
