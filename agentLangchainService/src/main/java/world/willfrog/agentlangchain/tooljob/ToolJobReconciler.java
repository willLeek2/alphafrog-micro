package world.willfrog.agentlangchain.tooljob;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.Set;

/**
 * Periodically polls due tool jobs, queries sandbox status, and hands terminal
 * jobs to the shared reentrant finalizer.
 * <p>
 * Two trigger paths:
 * <ol>
 *   <li>ZRANGEBYSCORE on the Redis due ZSET (short-cycle).</li>
 *   <li>Periodic full scan of DB anchors (long-cycle, rebuilds Redis if lost).</li>
 * </ol>
 */
@Service
public class ToolJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(ToolJobReconciler.class);

    // Known sandbox terminal status strings
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String CANCELED = "CANCELED";
    private static final String NOT_FOUND = "NOT_FOUND";

    private final ToolJobRedisCache redisCache;
    private final ToolJobAnchorService anchorService;
    private final ToolJobFinalizer finalizer;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;

    @DubboReference
    private PythonSandboxService sandboxService;

    public ToolJobReconciler(ToolJobRedisCache redisCache,
                             ToolJobAnchorService anchorService,
                             ToolJobFinalizer finalizer,
                             ToolJobResumeService resumeService,
                             ToolJobConfig config) {
        this.redisCache = redisCache;
        this.anchorService = anchorService;
        this.finalizer = finalizer;
        this.resumeService = resumeService;
        this.config = config;
    }

    /**
     * Short-cycle reconciliation from the Redis due ZSET.
     */
    @Scheduled(fixedDelayString = "${agent.tool-job.reconciler-interval-ms:5000}")
    public void reconcileFromDue() {
        try {
            Set<String> dueRunIds = redisCache.fetchDue(20);
            if (dueRunIds.isEmpty()) {
                return;
            }
            log.debug("Reconciler found {} due items", dueRunIds.size());
            for (String runId : dueRunIds) {
                processItem(runId);
            }
        } catch (Exception e) {
            log.error("Reconciler due-cycle error", e);
        }
    }

    /**
     * Long-cycle rebuild: scans all DB anchors and rebuilds Redis due/cache.
     */
    @Scheduled(fixedDelayString = "${agent.tool-job.rebuild-interval-ms:60000}")
    public void rebuildFromAnchors() {
        try {
            var activeRuns = anchorService.listActive(100);
            for (AgentRun run : activeRuns) {
                ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
                if (anchor == null) {
                    continue;
                }
                redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                if (anchor.getNextPollAt() != null
                        && !anchor.getNextPollAt().isAfter(Instant.now())) {
                    processItem(run.getId());
                }
            }
        } catch (Exception e) {
            log.error("Reconciler rebuild-cycle error", e);
        }

        // Scan for resume-ready runs (CAS-ed to RECEIVED but launch may have been lost)
        try {
            var resumeReadyRuns = anchorService.listResumeReady(50);
            for (AgentRun run : resumeReadyRuns) {
                ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
                if (anchor != null) {
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    resumeService.tryResume(run.getId());
                }
            }
        } catch (Exception e) {
            log.error("Reconciler resume-ready scan error", e);
        }
    }

    private void processItem(String runId) {
        try {
            ToolJobAnchor anchor = anchorService.loadAnchor(runId);
            if (anchor == null) {
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
                return;
            }

            if (!anchor.isAutoResume()) {
                checkSandboxAndReleaseCapacity(runId, anchor);
                return;
            }

            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(anchor.getTaskId()).build());

            String status = statusResp.getStatus();

            if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                finalizer.handleTerminal(runId, anchor, statusResp);
            } else if (NOT_FOUND.equals(status)) {
                finalizer.handleNotFound(runId, anchor);
            } else {
                anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                redisCache.upsertDue(runId, anchor);
                redisCache.writePendingCache(runId, anchor);
            }
        } catch (Exception e) {
            log.error("Reconciler error processing run={}", runId, e);
        }
    }

    private void checkSandboxAndReleaseCapacity(String runId, ToolJobAnchor anchor) {
        try {
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(anchor.getTaskId()).build());
            String status = statusResp.getStatus();
            if (SUCCEEDED.equals(status) || FAILED.equals(status)
                    || CANCELED.equals(status) || NOT_FOUND.equals(status)) {
                anchor.setTerminalStatus(status);
                anchor.setTerminalAt(Instant.now());
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                finalizer.releaseCapacityAndCleanup(runId, anchor);
            }
        } catch (Exception e) {
            log.error("Capacity-tracking error for paused run={}", runId, e);
        }
    }
}
