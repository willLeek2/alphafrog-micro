package world.willfrog.agentlangchain.tooljob;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Duration;
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

    @Autowired(required = false)
    private ToolJobCheckpointFailureRecoveryService checkpointFailureRecoveryService;

    @DubboReference
    private PythonSandboxService sandboxService;

    public ToolJobReconciler(ToolJobRedisCache redisCache, ToolJobAnchorService anchorService,
                             ToolJobFinalizer finalizer, ToolJobResumeService resumeService,
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
            Set<String> due = redisCache.fetchDue(20);
            for (String runId : due) processItem(runId);
        } catch (Exception e) {
            log.error("Reconciler due-cycle error", e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.rebuild-interval-ms:60000}")
    public void rebuildFromAnchors() {
        try {
            for (AgentRun run : anchorService.listActive(100)) {
                ToolJobAnchor a = anchorService.loadAnchor(run.getId());
                if (a == null) continue;
                redisCache.atomicWritePendingAndDue(run.getId(), a);
                if (a.getNextPollAt() != null && !a.getNextPollAt().isAfter(Instant.now()))
                    processItem(run.getId());
            }
        } catch (Exception e) { log.error("Reconciler rebuild error", e); }
        try {
            for (AgentRun run : anchorService.listResumeReady(50)) {
                ToolJobAnchor a = anchorService.loadAnchor(run.getId());
                if (a != null) { redisCache.atomicWritePendingAndDue(run.getId(), a); resumeService.tryResume(run.getId()); }
            }
        } catch (Exception e) { log.error("Resume-ready scan error", e); }
    }

    private void processItem(String runId) {
        try {
            if (checkpointFailureRecoveryService != null
                    && !checkpointFailureRecoveryService.retryPending(runId)) {
                log.warn("Checkpoint-failure retry remains pending run={}", runId);
                return;
            }
            ToolJobAnchor anchor = anchorService.loadAnchor(runId);
            if (anchor == null) { redisCache.removeDue(runId); redisCache.deletePendingCache(runId); return; }
            String taskId = anchor.getTaskId();
            if (taskId == null || taskId.isBlank()) return;

            if (!anchor.isAutoResume()) { checkPausedTerminal(runId, anchor); return; }

            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                TaskResultResponse resultResp = fetchResult(taskId, runId, status);
                if (resultResp == null) {
                    Instant now = Instant.now();
                    if (anchor.getTerminalConfirmedAt() != null) {
                        long elapsed = Duration.between(anchor.getTerminalConfirmedAt(), now).toSeconds();
                        int attempts = anchor.getResultFetchAttempts() + 1;
                        anchor.setResultFetchAttempts(attempts);
                        if (elapsed > config.getResultRetentionDeadlineSeconds()
                                || attempts >= config.getResultFetchMaxAttempts()) {
                            anchor.setResultFetchState("LOST");
                            anchor.setTerminalStatus("RESULT_LOST");
                            anchor.setTerminalAt(now);
                            log.error("Result permanently lost for run={}, taskId={}", runId, taskId);
                            finalizer.handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                            return;
                        }
                    } else {
                        anchor.setResultFetchState("PENDING");
                        anchor.setTerminalConfirmedAt(now);
                        anchor.setResultFetchAttempts(1);
                    }
                    anchor.setNextPollAt(now.plusMillis(config.getPollIntervalMs()));
                    boolean updated = anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                    if (!updated) {
                        log.warn("Retry-state CAS failed for run={}, will reload from PG next cycle", runId);
                        redisCache.removeDue(runId);
                        return;
                    }
                    redisCache.upsertDue(runId, anchor);
                    redisCache.writePendingCache(runId, anchor);
                    return;
                }
                finalizer.handleTerminal(runId, anchor, status, resultResp, true);
            } else if (NOT_FOUND.equals(status)) {
                finalizer.handleNotFound(runId, anchor);
            } else {
                anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                redisCache.upsertDue(runId, anchor);
                redisCache.writePendingCache(runId, anchor);
            }
        } catch (Exception e) {
            log.error("Reconciler error run={}", runId, e);
        }
    }

    private TaskResultResponse fetchResult(String taskId, String runId, String expectedStatus) {
        try {
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            return ToolJobResultValidator.validate(taskId, runId, resp, expectedStatus);
        } catch (Exception e) {
            log.error("Failed to fetch result for taskId={}, run={}", taskId, runId, e);
            return null;
        }
    }

    private void checkPausedTerminal(String runId, ToolJobAnchor anchor) {
        try {
            String taskId = anchor.getTaskId();
            if (taskId == null) return;
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();
            if (NOT_FOUND.equals(status)) {
                finalizer.handleNotFound(runId, anchor);
            } else if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                TaskResultResponse resultResp = fetchResult(taskId, runId, status);
                if (resultResp != null) {
                    // Envelope + release but autoResume=false (no CAS/READY)
                    finalizer.handleTerminal(runId, anchor, status, resultResp, false);
                }
        }
        } catch (Exception e) {
            log.error("Paused-terminal check error run={}", runId, e);
        }
    }
}
