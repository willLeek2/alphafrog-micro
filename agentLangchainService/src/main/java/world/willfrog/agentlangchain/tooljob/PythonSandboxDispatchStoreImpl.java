package world.willfrog.agentlangchain.tooljob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

/**
 * executePython 分发阶段的 PostgreSQL 真相源实现。
 * PREPARING、ATTACHED、PENDING 三次推进都先写 DB；只有 PENDING 成功后才派生 Redis 索引。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonSandboxDispatchStoreImpl implements PythonSandboxDispatchStore {

    private static final String DAG_BLOCKING_NO_RESUME = "DAG_BLOCKING_NO_RESUME";
    private static final String DAG_BLOCKING_WORKER_LOST = "DAG_BLOCKING_WORKER_LOST";
    private static final String DAG_BLOCKING_PREPARING_ABORT =
            "DAG_BLOCKING_PREPARING_ABORT";

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;

    @Override
    public boolean persistPreparing(String runId, ToolJobAnchor anchor) {
        // 调用方必须明确提供 PREPARING；claim SQL 还要求当前 Run anchor 为空。
        return "PREPARING".equals(anchor.getAnchorState())
                && anchorService.claimPreparing(runId, anchor, AgentRunStatus.EXECUTING);
    }

    @Override
    public boolean persistAttached(String runId, ToolJobAnchor anchor) {
        // fast-path 可能直接推进 TERMINAL，因此 ATTACHED/TERMINAL 都按 operationId 更新 active anchor。
        if (!"ATTACHED".equals(anchor.getAnchorState())
                && !"TERMINAL".equals(anchor.getAnchorState())) {
            return false;
        }
        if (DAG_BLOCKING_NO_RESUME.equals(anchor.getRunDisposition())) {
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
        // 只有已经完成容量转交的 PENDING anchor 才能释放 Agent worker。
        if (!"PENDING".equals(anchor.getAnchorState())) {
            return false;
        }
        // 单条 CAS 同时写 anchor 和 EXECUTING→WAITING_TOOL_JOB，消除半状态窗口。
        boolean durable = anchorService.updateActiveAndStatus(
                runId, anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, anchor.getOperationId());
        if (!durable) {
            return false;
        }
        // DB 成功后才写 Redis；Redis 失败不回滚 durable pending。
        try {
            redisCache.atomicWritePendingAndDue(runId, anchor);
        } catch (Exception cacheFailure) {
            log.warn("Pending Redis derivative write failed for run={}, durable anchor will rebuild it: {}",
                    runId, cacheFailure.getMessage());
        }
        // true 是上层抛 pending 信号并释放 worker 的最终许可。
        return true;
    }

    @Override
    public boolean clearActive(String runId, String operationId) {
        return anchorService.clearActive(runId, AgentRunStatus.EXECUTING, operationId);
    }

    @Override
    public boolean clearSynchronouslyCompleted(String runId, String operationId) {
        return anchorService.clearSynchronouslyCompleted(
                runId, AgentRunStatus.EXECUTING, operationId);
    }

    @Override
    public boolean renewDagBlockingLease(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil) {
        if (!DAG_BLOCKING_NO_RESUME.equals(anchor.getRunDisposition())) {
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
        if (!DAG_BLOCKING_WORKER_LOST.equals(anchor.getRunDisposition())) {
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
        // PostgreSQL 是 owner 真相；Redis 只加速 cleanup，失败后可由 fallback scan 重建。
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
                || !DAG_BLOCKING_PREPARING_ABORT.equals(anchor.getRunDisposition())) {
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
                || !DAG_BLOCKING_PREPARING_ABORT.equals(anchor.getRunDisposition())) {
            return false;
        }
        return anchorService.completeLiveDagBlockingPreparingAbort(
                runId,
                AgentRunStatus.EXECUTING,
                anchor.getOperationId(),
                anchor.getBlockingOwnerId(),
                expectedLeaseUntil);
    }
}
