package world.willfrog.agentlangchain.tooljob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.List;

/**
 * Shared reentrant finalizer for external tool jobs.
 * <p>
 * Both the synchronous fast-path Completed and the background reconciler enter
 * this finalizer. Each step records its outcome in the durable anchor; on re-entry
 * the finalizer resumes from the first incomplete step.
 * <p>
 * P0 scope:
 * <ol>
 *   <li>Persist terminal envelope to anchor</li>
 *   <li>Release reservation (best-effort via capacity service)</li>
 *   <li>CAS run status WAITING_TOOL_JOB → RECEIVED</li>
 *   <li>Mark resumeState READY (Codex pipeline consumes this)</li>
 *   <li>Cleanup anchor/cache after CONSUMED</li>
 * </ol>
 * Steps 3-4 (usage upsert and appendOnce event) are T4 and Codex slices, wired later.
 */
@Service
public class ToolJobFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ToolJobFinalizer.class);

    // Finalizer step names (ordered)
    static final String STEP_ENVELOPE = "ENVELOPE";
    static final String STEP_RELEASE = "RELEASE";
    static final String STEP_USAGE = "USAGE";
    static final String STEP_EVENT = "EVENT";
    static final String STEP_CAS_STATUS = "CAS_STATUS";
    static final String STEP_RESUME_READY = "RESUME_READY";
    static final String STEP_CLEANUP = "CLEANUP";

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final DataAnalysisCapacityService capacityService;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;

    public ToolJobFinalizer(ToolJobAnchorService anchorService,
                            ToolJobRedisCache redisCache,
                            DataAnalysisCapacityService capacityService,
                            ToolJobResumeService resumeService,
                            ToolJobConfig config) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.capacityService = capacityService;
        this.resumeService = resumeService;
        this.config = config;
    }

    /**
     * Entry point when sandbox reports terminal (SUCCEEDED / FAILED / CANCELED).
     */
    public void handleTerminal(String runId, ToolJobAnchor anchor, TaskStatusResponse statusResp) {
        String status = statusResp.getStatus();
        Instant now = Instant.now();

        // Step 1: persist terminal envelope to anchor
        if (!isBeyond(anchor, STEP_ENVELOPE)) {
            anchor.setTerminalStatus(status);
            anchor.setSandboxTerminalStatus(status);
            anchor.setTerminalAt(now);
            anchor.setFinalizerStep(STEP_ENVELOPE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) {
                log.warn("Finalizer ENVELOPE CAS failed for run={}, will retry", runId);
                return;
            }
        }

        // Step 2: release reservation (best-effort, idempotent)
        if (!isBeyond(anchor, STEP_RELEASE)) {
            tryReleaseReservation(anchor);
            anchor.setFinalizerStep(STEP_RELEASE);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        }

        // Steps 3-4: usage + event — P0 stubs, advanced by T4 and Codex
        if (!isBeyond(anchor, STEP_USAGE)) {
            anchor.setUsagePersisted(false);
            anchor.setFinalizerStep(STEP_USAGE);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        }
        if (!isBeyond(anchor, STEP_EVENT)) {
            anchor.setTerminalEventEmitted(false);
            anchor.setFinalizerStep(STEP_EVENT);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        }

        // Step 5: CAS run status WAITING_TOOL_JOB → RECEIVED
        if (!isBeyond(anchor, STEP_CAS_STATUS)) {
            boolean casOk = anchorService.casUpdateStatus(runId, AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
            if (!casOk) {
                log.warn("Finalizer CAS_STATUS failed for run={} — run was paused/canceled", runId);
                anchor.setAutoResume(false);
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                return;
            }
            anchor.setFinalizerStep(STEP_CAS_STATUS);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
        }

        // Step 6: mark resumeState READY and attempt synchronous resume launch
        if (!isBeyond(anchor, STEP_RESUME_READY)) {
            anchor.setResumeState("READY");
            anchor.setFinalizerStep(STEP_RESUME_READY);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            redisCache.writePendingCache(runId, anchor);

            // Attempt synchronous resume. If launcher is not wired or rejects,
            // the READY state remains and will be picked up by periodic scan.
            resumeService.tryResume(runId);
        }

        // Step 7 (CLEANUP) is deferred — invoked by Codex after CONSUMED
    }

    /**
     * Entry point when sandbox reports NOT_FOUND.
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
                log.error("Result lost for run={}, taskId={}, attempts={}", runId, anchor.getTaskId(), attempts);
                handleTerminalLost(runId, anchor);
                return;
            }
            anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
        } else {
            // First NOT_FOUND: mark RESULT_FETCH_PENDING, release capacity
            anchor.setResultFetchState("PENDING");
            anchor.setTerminalConfirmedAt(now);
            anchor.setResultFetchAttempts(1);
            anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
            tryReleaseReservation(anchor);
        }

        anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        redisCache.upsertDue(runId, anchor);
        redisCache.writePendingCache(runId, anchor);
    }

    private void handleTerminalLost(String runId, ToolJobAnchor anchor) {
        anchor.setFinalizerStep(STEP_ENVELOPE);
        anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        tryReleaseReservation(anchor);
        anchorService.casUpdateStatus(runId, AgentRunStatus.FAILED, AgentRunStatus.WAITING_TOOL_JOB);
        releaseCapacityAndCleanup(runId, anchor);
    }

    /**
     * Release capacity and clean up after terminal handling is complete.
     * Called for paused/canceled runs after sandbox terminal confirmed,
     * or after Codex pipeline marks resumeState CONSUMED.
     */
    public void releaseCapacityAndCleanup(String runId, ToolJobAnchor anchor) {
        tryReleaseReservation(anchor);
        anchor.setResultConsumed(true);
        anchor.setFinalizerStep(STEP_CLEANUP);
        anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);

        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);

        // Clear anchor from DB
        anchorService.updateAnchorAndStatus(runId, new ToolJobAnchor(),
                anchor.getRunDisposition() != null
                        && List.of("PAUSED", "CANCELED", "CAS_REJECTED").contains(anchor.getRunDisposition())
                        ? AgentRunStatus.WAITING : AgentRunStatus.RECEIVED,
                AgentRunStatus.WAITING_TOOL_JOB);
    }

    // ---- internal helpers ----

    private static final java.util.Map<String, Integer> STEP_ORDER = java.util.Map.of(
            STEP_ENVELOPE, 1,
            STEP_RELEASE, 2,
            STEP_USAGE, 3,
            STEP_EVENT, 4,
            STEP_CAS_STATUS, 5,
            STEP_RESUME_READY, 6,
            STEP_CLEANUP, 7);

    /** Returns true if the anchor's current finalizer step is past the given step. */
    private boolean isBeyond(ToolJobAnchor anchor, String step) {
        String current = anchor.getFinalizerStep();
        if (current == null) {
            return false;
        }
        int currentOrdinal = STEP_ORDER.getOrDefault(current, 0);
        int stepOrdinal = STEP_ORDER.getOrDefault(step, 0);
        return currentOrdinal > stepOrdinal;
    }

    private void tryReleaseReservation(ToolJobAnchor anchor) {
        if (anchor.getReservationJson() == null || anchor.getReservationJson().isBlank()) {
            return;
        }
        try {
            DataAnalysisReservation current = parseReservationFromJson(anchor.getReservationJson());
            if (current == null || current.state() == DataAnalysisReservationState.RELEASED) {
                return;
            }

            // Transition to TERMINAL_CONFIRMED if not already
            DataAnalysisReservation confirmed;
            if (current.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
                confirmed = new DataAnalysisReservation(
                        current.reservationId(), current.identity(),
                        current.resourceClass(), current.capacityUnits(),
                        DataAnalysisReservationState.TERMINAL_CONFIRMED,
                        current.taskId(), current.acquiredAt());
                DataAnalysisRestoreOutcome restored = capacityService.restoreReservation(confirmed);
                if (restored == DataAnalysisRestoreOutcome.CONFLICT) {
                    log.warn("Failed to transition reservation to TERMINAL_CONFIRMED, id={}", current.reservationId());
                    return;
                }
            } else {
                confirmed = current;
            }

            DataAnalysisTerminalEnvelope envelope = buildMinimalEnvelope(confirmed);
            if (envelope == null) {
                return;
            }
            DataAnalysisReleaseRequest releaseRequest = new DataAnalysisReleaseRequest(
                    confirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            DataAnalysisReleaseOutcome outcome = capacityService.releaseReservation(releaseRequest);
            log.info("Reservation release outcome={} for reservationId={}", outcome, confirmed.reservationId());
        } catch (Exception e) {
            log.error("Failed to release reservation for operationId={}", anchor.getOperationId(), e);
        }
    }

    private DataAnalysisTerminalEnvelope buildMinimalEnvelope(DataAnalysisReservation reservation) {
        try {
            DataAnalysisResourceUsage usage = DataAnalysisResourceUsage.missing(reservation.resourceClass());
            DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                    0, 0, 0, 0.0, 0,
                    java.util.List.of(),
                    reservation.resourceClass(),
                    reservation.capacityUnits());
            Instant now = Instant.now();
            return new DataAnalysisTerminalEnvelope(
                    reservation.identity().runId(),
                    reservation.identity().toolCallId(),
                    reservation.identity().attempt(),
                    reservation.operationId(),
                    reservation.taskId(),
                    "TERMINAL_CONFIRMED",
                    true,
                    null,
                    null,
                    null,
                    null,
                    false,
                    estimate,
                    reservation,
                    usage,
                    now,
                    true);
        } catch (Exception e) {
            log.error("Failed to construct terminal envelope for reservationId={}", reservation.reservationId(), e);
            return null;
        }
    }

    private DataAnalysisReservation parseReservationFromJson(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules();
            return mapper.readValue(json, DataAnalysisReservation.class);
        } catch (Exception e) {
            log.error("Failed to parse reservation JSON", e);
            return null;
        }
    }
}
