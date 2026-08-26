package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 在不查询 Sandbox 的前提下重入已持久化的 DAG PREPARING abort 流程。
 *
 * <p>anchor 会在进程内容量释放前先把 reservation 写成 RELEASED；而
 * {@link DataAnalysisReleaseRequest} 对
 * {@link DataAnalysisReleaseProof.PreDispatchAbort} 只接受 PREPARING
 * reservation。恢复路径因此按同一 identity 重建释放前快照，同时把数据库里
 * 已写成 RELEASED 的 anchor 继续当作权威的取消意图。只有携带完整证明的幂等
 * outcome 才能清理。</p>
 */
public final class ToolJobPreparingAbortRecoveryService {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    public enum Outcome {
        COMPLETED,
        CLEAR_PENDING,
        CONFLICT,
        INVALID_EVIDENCE,
        OWNERSHIP_LOST,
        RETRYABLE
    }

    public Outcome recover(
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisCapacityService capacityService,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache) {
        if (!hasDurableAbortIdentity(runId, anchor)
                || capacityService == null
                || anchorService == null
                || redisCache == null) {
            return Outcome.INVALID_EVIDENCE;
        }

        DataAnalysisReservation released;
        try {
            released = OBJECT_MAPPER.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
        } catch (Exception invalidReservation) {
            return Outcome.INVALID_EVIDENCE;
        }
        if (!matchesDurableAbort(runId, anchor, released)) {
            return Outcome.INVALID_EVIDENCE;
        }

        DataAnalysisReservation preparing = new DataAnalysisReservation(
                released.reservationId(),
                released.identity(),
                released.resourceClass(),
                released.capacityUnits(),
                DataAnalysisReservationState.PREPARING,
                null,
                released.acquiredAt());

        DataAnalysisReleaseOutcome releaseOutcome;
        try {
            releaseOutcome = capacityService.releaseReservation(
                    new DataAnalysisReleaseRequest(
                            preparing,
                            new DataAnalysisReleaseProof.PreDispatchAbort(
                                    preparing.identity()),
                            DataAnalysisReleaseReason.PREPARING_ABORTED));
        } catch (Exception transientFailure) {
            return Outcome.RETRYABLE;
        }
        if (releaseOutcome == DataAnalysisReleaseOutcome.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (releaseOutcome != DataAnalysisReleaseOutcome.RELEASED
                && releaseOutcome != DataAnalysisReleaseOutcome.ALREADY_RELEASED
                && releaseOutcome != DataAnalysisReleaseOutcome.NOT_FOUND) {
            return Outcome.RETRYABLE;
        }
        return completeAcceptedRelease(runId, anchor, anchorService, redisCache);
    }

    public Outcome completeAcceptedRelease(
            String runId,
            ToolJobAnchor anchor,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache) {
        if (!hasDurableAbortIdentity(runId, anchor)
                || anchorService == null
                || redisCache == null) {
            return Outcome.INVALID_EVIDENCE;
        }
        try {
            DataAnalysisReservation released = OBJECT_MAPPER.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
            if (!matchesDurableAbort(runId, anchor, released)) {
                return Outcome.INVALID_EVIDENCE;
            }
        } catch (Exception invalidReservation) {
            return Outcome.INVALID_EVIDENCE;
        }
        ToolJobAnchor cleanupAnchor = ToolJobAnchor.fromJson(anchor.toJson());
        if ("ABORTING".equals(anchor.getAnchorState())) {
            cleanupAnchor.setCleanupSourceOwnerId(
                    anchor.getBlockingOwnerId());
            cleanupAnchor.setCleanupSourceLeaseUntil(
                    anchor.getBlockingLeaseUntil());
        } else if (anchor.getCleanupSourceOwnerId() == null
                || anchor.getCleanupSourceOwnerId().isBlank()
                || anchor.getCleanupSourceLeaseUntil() == null) {
            // 没有冻结旧 Redis 身份的 CLEARING 不能安全接管，避免误改后来任务的索引。
            return Outcome.INVALID_EVIDENCE;
        }
        cleanupAnchor.setAnchorState("CLEARING");
        cleanupAnchor.setBlockingOwnerId(
                DagBlockingWorkerLease.processOwnerId()
                        + "/abort-cleanup/"
                        + UUID.randomUUID());
        cleanupAnchor.setBlockingLeaseUntil(
                DagBlockingWorkerLease.renewedUntil(Instant.now()));

        boolean claimed;
        try {
            claimed = anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                    runId,
                    cleanupAnchor,
                    anchor.getOperationId(),
                    anchor.getBlockingOwnerId(),
                    anchor.getBlockingLeaseUntil());
        } catch (Exception claimFailure) {
            claimed = false;
        }
        if (!claimed) {
            ToolJobAnchor current;
            try {
                current = anchorService.loadAnchor(runId);
            } catch (Exception reloadFailure) {
                return Outcome.RETRYABLE;
            }
            if (current == null) {
                return Outcome.COMPLETED;
            }
            if (!sameCleanupIdentity(cleanupAnchor, current)) {
                return sameAbortIdentity(anchor, current)
                        ? Outcome.RETRYABLE
                        : Outcome.OWNERSHIP_LOST;
            }
        }

        ToolJobRedisCache.OwnedIndexClaimResult indexClaim;
        try {
            indexClaim = redisCache.claimPreparingAbortCleanupIndexes(
                    runId, anchor, cleanupAnchor);
        } catch (Exception redisFailure) {
            return Outcome.CLEAR_PENDING;
        }
        if (indexClaim == ToolJobRedisCache.OwnedIndexClaimResult.MISMATCHED) {
            return Outcome.CLEAR_PENDING;
        }
        if (indexClaim == ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED) {
            ToolJobRedisCache.OwnedIndexDeleteResult deleteResult;
            try {
                deleteResult = redisCache.removePendingAndDueIfMatches(
                        runId,
                        cleanupAnchor.getOperationId(),
                        cleanupAnchor.getRunDisposition(),
                        cleanupAnchor.getBlockingOwnerId(),
                        cleanupAnchor.getBlockingLeaseUntil());
            } catch (Exception redisFailure) {
                return Outcome.CLEAR_PENDING;
            }
            if (deleteResult
                    == ToolJobRedisCache.OwnedIndexDeleteResult.MISMATCHED) {
                return Outcome.CLEAR_PENDING;
            }
        }

        boolean cleared;
        try {
            cleared = anchorService.completeLiveDagBlockingPreparingAbort(
                    runId,
                    AgentRunStatus.EXECUTING,
                    cleanupAnchor.getOperationId(),
                    cleanupAnchor.getBlockingOwnerId(),
                    cleanupAnchor.getBlockingLeaseUntil());
        } catch (Exception clearFailure) {
            return Outcome.CLEAR_PENDING;
        }
        if (cleared) {
            return Outcome.COMPLETED;
        }

        try {
            ToolJobAnchor current = anchorService.loadAnchor(runId);
            if (current == null) {
                return Outcome.COMPLETED;
            }
            if (!sameCleanupIdentity(cleanupAnchor, current)) {
                return Outcome.OWNERSHIP_LOST;
            }
        } catch (Exception reloadFailure) {
            return Outcome.CLEAR_PENDING;
        }
        return Outcome.CLEAR_PENDING;
    }

    private static boolean hasDurableAbortIdentity(String runId, ToolJobAnchor anchor) {
        return runId != null
                && !runId.isBlank()
                && anchor != null
                && ("ABORTING".equals(anchor.getAnchorState())
                    || "CLEARING".equals(anchor.getAnchorState()))
                && ToolJobRunDisposition.isDagPreparingAbort(
                        anchor.getRunDisposition())
                && !anchor.isAutoResume()
                && anchor.getOperationId() != null
                && !anchor.getOperationId().isBlank()
                && anchor.getBlockingOwnerId() != null
                && !anchor.getBlockingOwnerId().isBlank()
                && anchor.getBlockingLeaseUntil() != null
                && anchor.getReservationJson() != null
                && !anchor.getReservationJson().isBlank();
    }

    private static boolean matchesDurableAbort(
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisReservation released) {
        return released != null
                && released.state() == DataAnalysisReservationState.RELEASED
                && released.taskId() == null
                && released.identity() != null
                && runId.equals(released.identity().runId())
                && anchor.getOperationId().equals(released.operationId());
    }

    private static boolean sameAbortIdentity(
            ToolJobAnchor expected,
            ToolJobAnchor current) {
        return expected.getAnchorState().equals(current.getAnchorState())
                && ToolJobRunDisposition.isDagPreparingAbort(
                        current.getRunDisposition())
                && expected.getOperationId().equals(current.getOperationId())
                && expected.getBlockingOwnerId().equals(
                        current.getBlockingOwnerId())
                && expected.getBlockingLeaseUntil().equals(
                        current.getBlockingLeaseUntil());
    }

    private static boolean sameCleanupIdentity(
            ToolJobAnchor expected,
            ToolJobAnchor current) {
        return "CLEARING".equals(current.getAnchorState())
                && ToolJobRunDisposition.isDagPreparingAbort(
                        current.getRunDisposition())
                && expected.getOperationId().equals(current.getOperationId())
                && expected.getBlockingOwnerId().equals(
                        current.getBlockingOwnerId())
                && expected.getBlockingLeaseUntil().equals(
                        current.getBlockingLeaseUntil());
    }
}
