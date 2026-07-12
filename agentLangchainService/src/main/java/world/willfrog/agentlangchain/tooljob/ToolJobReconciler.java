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

@Service
public class ToolJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(ToolJobReconciler.class);

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

    @Scheduled(fixedDelayString = "${agent.tool-job.reconciler-interval-ms:5000}")
    public void reconcileFromDue() {
        try {
            Set<String> dueRunIds = redisCache.fetchDue(20);
            if (dueRunIds.isEmpty()) return;
            for (String runId : dueRunIds) {
                processItem(runId);
            }
        } catch (Exception e) {
            log.error("Reconciler due-cycle error", e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.rebuild-interval-ms:60000}")
    public void rebuildFromAnchors() {
        try {
            var activeRuns = anchorService.listActive(100);
            for (AgentRun run : activeRuns) {
                ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
                if (anchor == null) continue;
                redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                if (anchor.getNextPollAt() != null && !anchor.getNextPollAt().isAfter(Instant.now())) {
                    processItem(run.getId());
                }
            }
        } catch (Exception e) {
            log.error("Reconciler rebuild-cycle error", e);
        }

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
                checkSandboxTerminal(runId, anchor);
                return;
            }

            String taskId = anchor.getTaskId();
            if (taskId == null || taskId.isBlank()) {
                log.warn("Anchor for run={} has no taskId", runId);
                return;
            }

            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                // Pass the real sandbox terminal status to the finalizer
                // (not a reservation state name like "TERMINAL_CONFIRMED")
                finalizer.handleTerminal(runId, anchor, status);
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

    private void checkSandboxTerminal(String runId, ToolJobAnchor anchor) {
        try {
            String taskId = anchor.getTaskId();
            if (taskId == null) return;
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();
            if (SUCCEEDED.equals(status) || FAILED.equals(status)
                    || CANCELED.equals(status) || NOT_FOUND.equals(status)) {
                anchor.setTerminalStatus(status);
                anchor.setTerminalAt(Instant.now());
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                // For paused/canceled runs, just clean up Redis without auto-resume
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
            }
        } catch (Exception e) {
            log.error("Capacity-tracking error for paused run={}", runId, e);
        }
    }
}
