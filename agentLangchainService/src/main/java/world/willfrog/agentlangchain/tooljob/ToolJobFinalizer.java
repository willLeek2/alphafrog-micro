package world.willfrog.agentlangchain.tooljob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.Map;

/**
 * Shared reentrant finalizer for external tool jobs.
 * <p>
 * Both the synchronous fast-path Completed and the background reconciler enter
 * this finalizer. Each step records its outcome in the durable anchor; on re-entry
 * the finalizer resumes from the first incomplete step.
 * <p>
 * P0 steps:
 * <ol>
 *   <li>Persist terminal envelope to anchor (real data from sandbox)</li>
 *   <li>CAS run status WAITING_TOOL_JOB → RECEIVED</li>
 *   <li>Mark resumeState READY, try synchronous resume launch</li>
 * </ol>
 * Capacity release, usage upsert, and appendOnce event are separate concerns:
 * capacity release is done by the reconciler using real sandbox result data;
 * usage upsert is T4 recorder; appendOnce is Codex event slice.
 */
@Service
public class ToolJobFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ToolJobFinalizer.class);

    static final String STEP_ENVELOPE = "ENVELOPE";
    static final String STEP_CAS_STATUS = "CAS_STATUS";
    static final String STEP_RESUME_READY = "RESUME_READY";

    private static final Map<String, Integer> STEP_ORDER = Map.of(
            STEP_ENVELOPE, 1,
            STEP_CAS_STATUS, 2,
            STEP_RESUME_READY, 3);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;

    public ToolJobFinalizer(ToolJobAnchorService anchorService,
                            ToolJobRedisCache redisCache,
                            ToolJobResumeService resumeService,
                            ToolJobConfig config) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.resumeService = resumeService;
        this.config = config;
    }

    /**
     * Entry point when sandbox reports terminal (SUCCEEDED / FAILED / CANCELED).
     * {@code terminalStatus} is the real sandbox status string, not a reservation state name.
     */
    public void handleTerminal(String runId, ToolJobAnchor anchor, String terminalStatus) {
        Instant now = Instant.now();

        // Step 1: persist terminal status to anchor
        if (!isBeyond(anchor, STEP_ENVELOPE)) {
            anchor.setTerminalStatus(terminalStatus);
            anchor.setSandboxTerminalStatus(terminalStatus);
            anchor.setTerminalAt(now);
            anchor.setFinalizerStep(STEP_ENVELOPE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) {
                log.warn("Finalizer ENVELOPE CAS failed for run={}, will retry", runId);
                return;
            }
        }

        // Step 2: CAS run status WAITING_TOOL_JOB → RECEIVED
        if (!isBeyond(anchor, STEP_CAS_STATUS)) {
            boolean casOk = anchorService.casUpdateStatus(runId, AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
            if (!casOk) {
                log.warn("Finalizer CAS_STATUS failed for run={} — run was paused/canceled", runId);
                anchor.setAutoResume(false);
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                return;
            }
            anchor.setFinalizerStep(STEP_CAS_STATUS);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
                log.warn("Finalizer CAS_STATUS anchor update failed for run={}", runId);
                return;
            }
        }

        // Step 3: mark resumeState READY, try synchronous resume launch
        if (!isBeyond(anchor, STEP_RESUME_READY)) {
            anchor.setResumeState("READY");
            anchor.setFinalizerStep(STEP_RESUME_READY);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            redisCache.writePendingCache(runId, anchor);
            resumeService.tryResume(runId);
        }
    }

    /**
     * Entry point when sandbox reports NOT_FOUND for the task.
     */
    public void handleNotFound(String runId, ToolJobAnchor anchor) {
        Instant now = Instant.now();

        if (anchor.getTerminalConfirmedAt() != null) {
            long elapsed = java.time.Duration.between(anchor.getTerminalConfirmedAt(), now).toSeconds();
            int attempts = anchor.getResultFetchAttempts() + 1;
            anchor.setResultFetchAttempts(attempts);

            if (elapsed > config.getResultRetentionDeadlineSeconds()
                    || attempts >= config.getResultFetchMaxAttempts()) {
                anchor.setResultFetchState("LOST");
                anchor.setTerminalStatus("RESULT_LOST");
                anchor.setTerminalAt(now);
                log.error("Result permanently lost for run={}, taskId={}, attempts={}", runId, anchor.getTaskId(), attempts);
                handleTerminal(runId, anchor, "RESULT_LOST");
                return;
            }
            anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
        } else {
            anchor.setResultFetchState("PENDING");
            anchor.setTerminalConfirmedAt(now);
            anchor.setResultFetchAttempts(1);
            anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
        }

        anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        redisCache.upsertDue(runId, anchor);
        redisCache.writePendingCache(runId, anchor);
    }

    // ---- internal helpers ----

    private boolean isBeyond(ToolJobAnchor anchor, String step) {
        String current = anchor.getFinalizerStep();
        if (current == null) {
            return false;
        }
        int currentOrdinal = STEP_ORDER.getOrDefault(current, 0);
        int stepOrdinal = STEP_ORDER.getOrDefault(step, 0);
        return currentOrdinal > stepOrdinal;
    }
}
