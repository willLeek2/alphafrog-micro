package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Shared reentrant finalizer for external tool jobs. Full step gate per §9.7/§9.8:
 * <ol>
 *   <li>ENVELOPE — persist sandbox terminal data (status + result/rawRef/error/usage)</li>
 *   <li>RELEASE — transition reservation → TERMINAL_CONFIRMED, build real envelope, release</li>
 *   <li>USAGE — T4 upsertUsage stub (P0: mark usagePersisted=false, T4 fills later)</li>
 *   <li>EVENT — Codex appendOnce stub (P0: mark terminalEventEmitted=false)</li>
 *   <li>CAS_STATUS — WAITING_TOOL_JOB → RECEIVED</li>
 *   <li>RESUME_READY — mark ready + generate token + try resume</li>
 * </ol>
 * Cleanup is deferred until usagePersisted && terminalEventEmitted.
 */
@Service
public class ToolJobFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ToolJobFinalizer.class);

    static final String STEP_ENVELOPE = "ENVELOPE";
    static final String STEP_RELEASE = "RELEASE";
    static final String STEP_USAGE = "USAGE";
    static final String STEP_EVENT = "EVENT";
    static final String STEP_CAS_STATUS = "CAS_STATUS";
    static final String STEP_RESUME_READY = "RESUME_READY";

    private static final Map<String, Integer> STEP_ORDER = Map.of(
            STEP_ENVELOPE, 1, STEP_RELEASE, 2, STEP_USAGE, 3,
            STEP_EVENT, 4, STEP_CAS_STATUS, 5, STEP_RESUME_READY, 6);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final DataAnalysisCapacityService capacityService;
    private final ToolJobResumeService resumeService;
    private final ToolJobConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
     * Entry point when sandbox reports terminal.
     * {@code terminalStatus} is the real sandbox status string.
     * {@code resultResp} is the result of getTaskResult (may be null for RESULT_LOST).
     */
    public void handleTerminal(String runId, ToolJobAnchor anchor,
                                String terminalStatus, TaskResultResponse resultResp) {
        Instant now = Instant.now();

        // Step 1: ENVELOPE — persist sandbox terminal data
        if (!isBeyond(anchor, STEP_ENVELOPE)) {
            anchor.setTerminalStatus(terminalStatus);
            anchor.setSandboxTerminalStatus(terminalStatus);
            anchor.setTerminalAt(now);
            if (resultResp != null) {
                anchor.setTerminalResultPreview(emptyToNull(resultResp.getStdout()));
                anchor.setTerminalRawRef(emptyToNull(resultResp.getDatasetDir()));
                anchor.setTerminalErrorCode(emptyToNull(resultResp.getError()));
                try {
                    anchor.setTerminalUsageJson(objectMapper.writeValueAsString(resultResp.getResourceUsage()));
                } catch (Exception e) {
                    log.warn("Failed to serialize resourceUsage for run={}", runId, e);
                }
            }
            anchor.setFinalizerStep(STEP_ENVELOPE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) {
                log.warn("ENVELOPE CAS failed for run={}, will retry", runId);
                return;
            }
        }

        // Step 2: RELEASE — capacity release via real reservation
        if (!isBeyond(anchor, STEP_RELEASE)) {
            if (anchor.getReservationJson() != null && !anchor.getReservationJson().isBlank()) {
                try {
                    DataAnalysisReservation current = objectMapper.readValue(
                            anchor.getReservationJson(), DataAnalysisReservation.class);
                    releaseCapacity(current, anchor);
                } catch (Exception e) {
                    log.error("RELEASE failed for run={}, will retry", runId, e);
                    return; // retry on next reconciler cycle
                }
            }
            anchor.setFinalizerStep(STEP_RELEASE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) {
                return;
            }
        }

        // Step 3: USAGE — T4 stub (P0 placeholder)
        if (!isBeyond(anchor, STEP_USAGE)) {
            anchor.setUsagePersisted(false);
            anchor.setFinalizerStep(STEP_USAGE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) return;
        }

        // Step 4: EVENT — Codex stub (P0 placeholder)
        if (!isBeyond(anchor, STEP_EVENT)) {
            anchor.setTerminalEventEmitted(false);
            anchor.setFinalizerStep(STEP_EVENT);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) return;
        }

        // Step 5: CAS run status WAITING_TOOL_JOB → RECEIVED
        if (!isBeyond(anchor, STEP_CAS_STATUS)) {
            boolean casOk = anchorService.casUpdateStatus(runId, AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
            if (!casOk) {
                log.warn("CAS_STATUS failed for run={} — paused/canceled", runId);
                anchor.setAutoResume(false);
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
                return;
            }
            anchor.setFinalizerStep(STEP_CAS_STATUS);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
                log.warn("CAS_STATUS anchor update failed for run={}", runId);
                return;
            }
        }

        // Step 6: RESUME_READY — mark ready, generate token, try resume
        if (!isBeyond(anchor, STEP_RESUME_READY)) {
            anchor.setResumeState("READY");
            anchor.setResumeToken(UUID.randomUUID().toString());
            anchor.setFinalizerStep(STEP_RESUME_READY);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
                log.warn("RESUME_READY anchor update failed for run={}", runId);
                return;
            }
            redisCache.writePendingCache(runId, anchor);
            resumeService.tryResume(runId);
        }
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
                log.error("Result permanently lost for run={}, taskId={}", runId, anchor.getTaskId());
                handleTerminal(runId, anchor, "RESULT_LOST", null);
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

    // ---- capacity release ----

    private void releaseCapacity(DataAnalysisReservation current, ToolJobAnchor anchor) {
        if (current.state() == DataAnalysisReservationState.RELEASED) return;

        // Transition to TERMINAL_CONFIRMED
        DataAnalysisReservation confirmed;
        if (current.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
            confirmed = new DataAnalysisReservation(current.reservationId(), current.identity(),
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

        // Build real terminal envelope and release
        DataAnalysisTerminalEnvelope envelope = buildEnvelope(confirmed, anchor);
        if (envelope == null) return;

        DataAnalysisReleaseRequest req = new DataAnalysisReleaseRequest(confirmed,
                new DataAnalysisReleaseProof.Terminal(envelope),
                DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
        DataAnalysisReleaseOutcome outcome = capacityService.releaseReservation(req);
        log.info("Capacity release outcome={} for reservationId={}", outcome, confirmed.reservationId());
    }

    private DataAnalysisTerminalEnvelope buildEnvelope(DataAnalysisReservation reservation, ToolJobAnchor anchor) {
        try {
            DataAnalysisResourceUsage usage = buildResourceUsage(reservation.resourceClass(), anchor.getTerminalUsageJson());
            DataAnalysisEstimate estimate = new DataAnalysisEstimate(0, 0, 0, 0.0, 0,
                    java.util.List.of(), reservation.resourceClass(), reservation.capacityUnits());
            String status = anchor.getTerminalStatus();
            boolean success = "SUCCEEDED".equals(status);
            String rawRef = anchor.getTerminalRawRef();
            String resultPreview = anchor.getTerminalResultPreview();
            String errorCode = anchor.getTerminalErrorCode();

            // Validate: success needs resultPreview or rawRef; failure needs error info
            if (success && rawRef == null && resultPreview == null) {
                log.warn("SUCCEEDED without resultPreview/rawRef for run={}, using empty preview",
                        anchor.getOperationId());
                resultPreview = "(no preview available)";
            }
            if (!success && errorCode == null) {
                errorCode = status; // use sandbox status as error code
            }

            return new DataAnalysisTerminalEnvelope(
                    reservation.identity().runId(), reservation.identity().toolCallId(),
                    reservation.identity().attempt(), reservation.operationId(), reservation.taskId(),
                    status, success, resultPreview, rawRef, errorCode,
                    success ? null : (resultPreview != null ? resultPreview : "sandbox " + status),
                    !success && !"RESULT_LOST".equals(status),
                    estimate, reservation, usage, anchor.getTerminalAt(), true);
        } catch (Exception e) {
            log.error("Failed to build terminal envelope for reservationId={}, terminalStatus={}",
                    reservation.reservationId(), anchor.getTerminalStatus(), e);
            return null;
        }
    }

    private DataAnalysisResourceUsage buildResourceUsage(DataAnalysisResourceClass rc, String usageJson) {
        if (usageJson == null) return DataAnalysisResourceUsage.missing(rc);
        try {
            SandboxResourceUsage s = objectMapper.readValue(usageJson, SandboxResourceUsage.class);
            return new DataAnalysisResourceUsage(rc,
                    s.hasCpuMillis() ? s.getCpuMillis() : null,
                    s.hasMemoryPeakBytes() ? s.getMemoryPeakBytes() : null,
                    s.hasMemoryByteMillis() ? s.getMemoryByteMillis() : null,
                    s.hasLogicalBytesScanned() ? s.getLogicalBytesScanned() : null,
                    s.hasArtifactBytesWritten() ? s.getArtifactBytesWritten() : null,
                    s.hasTemporaryBytesWritten() ? s.getTemporaryBytesWritten() : null,
                    s.hasQueueWaitMillis() ? s.getQueueWaitMillis() : null,
                    s.hasPrepareMillis() ? s.getPrepareMillis() : null,
                    s.hasExecutionWallMillis() ? s.getExecutionWallMillis() : null,
                    s.hasCleanupMillis() ? s.getCleanupMillis() : null,
                    s.hasDatasetOpenCount() ? s.getDatasetOpenCount() : null,
                    s.getExitReason(),
                    s.getOomKilled(), s.getTimedOut(), false, null, null);
        } catch (Exception e) {
            log.warn("Failed to parse resourceUsage JSON, using missing", e);
            return DataAnalysisResourceUsage.missing(rc);
        }
    }

    // ---- helpers ----

    private boolean isBeyond(ToolJobAnchor anchor, String step) {
        String current = anchor.getFinalizerStep();
        if (current == null) return false;
        return STEP_ORDER.getOrDefault(current, 0) > STEP_ORDER.getOrDefault(step, 0);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
