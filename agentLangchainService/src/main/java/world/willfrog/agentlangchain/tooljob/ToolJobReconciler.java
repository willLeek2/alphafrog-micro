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
            ToolJobAnchor anchor = anchorService.loadAnchor(runId);
            if (anchor == null) { redisCache.removeDue(runId); redisCache.deletePendingCache(runId); return; }
            String taskId = anchor.getTaskId();
            if (taskId == null || taskId.isBlank()) return;

            if (!anchor.isAutoResume()) { checkPausedTerminal(runId, anchor); return; }

            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            if (SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status)) {
                TaskResultResponse resultResp = fetchResult(taskId, runId);
                if (resultResp == null) {
                    // Result not yet available — retry later
                    anchor.setNextPollAt(Instant.now().plusMillis(config.getPollIntervalMs()));
                    anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                    redisCache.upsertDue(runId, anchor);
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

    private TaskResultResponse fetchResult(String taskId, String runId) {
        try {
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            if (resp == null) return null;
            // Validate: response must have status and non-empty payload
            String status = resp.getStatus();
            if (status == null || status.isBlank()) {
                log.warn("fetchResult: empty status for taskId={}, run={}", taskId, runId);
                return null;
            }
            return resp;
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
            if (SUCCEEDED.equals(status) || FAILED.equals(status)
                    || CANCELED.equals(status) || NOT_FOUND.equals(status)) {
                TaskResultResponse resultResp = fetchResult(taskId, runId);
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
