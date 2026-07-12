package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Shared reentrant finalizer per §9.7/§9.8. Each step records outcome in the
 * durable anchor; re-entry resumes from the first incomplete step (isStepDone
 * uses {@code >=} so a completed step is not re-executed).
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

    @Autowired(required = false)
    private ToolJobUsageHook usageHook;

    @Autowired(required = false)
    private ToolJobEventHook eventHook;

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

    // ========== public entry points ==========

    /**
     * @param autoResume false for paused/canceled runs (envelope+release but no CAS/READY)
     */
    public void handleTerminal(String runId, ToolJobAnchor anchor,
                                String terminalStatus, TaskResultResponse resultResp,
                                boolean autoResume) {
        Instant now = Instant.now();

        // Step 1: ENVELOPE
        if (!isStepDone(anchor, STEP_ENVELOPE)) {
            anchor.setTerminalStatus(terminalStatus);
            anchor.setSandboxTerminalStatus(terminalStatus);
            anchor.setTerminalAt(now);
            if (resultResp != null) {
                anchor.setTerminalResultPreview(boundedPreview(resultResp.getStdout()));
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
                log.warn("ENVELOPE CAS failed for run={}", runId);
                return;
            }
        }

        // Step 2: RELEASE — capacity release, return value gate
        if (!isStepDone(anchor, STEP_RELEASE)) {
            if (!releaseCapacity(anchor)) {
                log.warn("RELEASE failed for run={}, will retry", runId);
                return;
            }
            anchor.setFinalizerStep(STEP_RELEASE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) return;
        }

        // Step 3: USAGE — true gate, blocks if hook absent or fails
        if (!isStepDone(anchor, STEP_USAGE)) {
            if (usageHook == null) {
                log.warn("USAGE hook not wired — blocking finalizer for run={}", runId);
                return;
            }
            boolean ok = usageHook.upsertUsage(runId, anchor);
            if (!ok) {
                log.warn("USAGE hook failed for run={}, will retry", runId);
                return;
            }
            anchor.setUsagePersisted(true);
            anchor.setFinalizerStep(STEP_USAGE);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) return;
        }

        // Step 4: EVENT — true gate, blocks if hook absent or fails
        if (!isStepDone(anchor, STEP_EVENT)) {
            if (eventHook == null) {
                log.warn("EVENT hook not wired — blocking finalizer for run={}", runId);
                return;
            }
            boolean ok = eventHook.emitTerminalEvent(runId, anchor);
            if (!ok) {
                log.warn("EVENT hook failed for run={}, will retry", runId);
                return;
            }
            anchor.setTerminalEventEmitted(true);
            anchor.setFinalizerStep(STEP_EVENT);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB)) return;
        }

        if (!autoResume) {
            if ("CHECKPOINT_FAILED".equals(anchor.getRunDisposition())) {
                anchor.setFinalizerError("durable_checkpoint_write_failed");
                if (!anchorService.updateAnchorAndStatus(runId, anchor,
                        AgentRunStatus.FAILED, AgentRunStatus.WAITING_TOOL_JOB)) {
                    log.warn("CHECKPOINT_FAILED terminal transition failed for run={}", runId);
                }
                return;
            }
            // Paused/canceled: stop here, keep WAITING_TOOL_JOB, no CAS/READY
            log.info("Terminal handled for paused run={}, not auto-resuming", runId);
            return;
        }

        // Step 5: CAS_STATUS atomically with step
        if (!isStepDone(anchor, STEP_CAS_STATUS)) {
            anchor.setFinalizerStep(STEP_CAS_STATUS);
            if (!anchorService.updateAnchorAndStatus(runId, anchor, AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB)) {
                log.warn("CAS_STATUS atomic update failed for run={}", runId);
                return;
            }
        }

        // Step 6: RESUME_READY
        if (!isStepDone(anchor, STEP_RESUME_READY)) {
            anchor.setResumeState("READY");
            anchor.setResumeToken(UUID.randomUUID().toString());
            anchor.setResumeLeaseVersion(anchor.getResumeLeaseVersion() + 1);
            anchor.setResumeClaimedAt(now);
            anchor.setFinalizerStep(STEP_RESUME_READY);
            if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
                log.warn("RESUME_READY anchor update failed for run={}", runId);
                return;
            }
            redisCache.writePendingCache(runId, anchor);
            resumeService.tryResume(runId);
        }
    }

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
                handleTerminal(runId, anchor, "RESULT_LOST", null, anchor.isAutoResume());
                return;
            }
        } else {
            anchor.setResultFetchState("PENDING");
            anchor.setTerminalConfirmedAt(now);
            anchor.setResultFetchAttempts(1);
        }
        anchor.setNextPollAt(now.plusMillis(config.getReconcilerIntervalMs()));
        anchorService.updateAnchor(runId, anchor, AgentRunStatus.WAITING_TOOL_JOB);
        redisCache.upsertDue(runId, anchor);
        redisCache.writePendingCache(runId, anchor);
    }

    // ========== capacity release ==========

    /** @return true if capacity was released (or already released) */
    private boolean releaseCapacity(ToolJobAnchor anchor) {
        if (anchor.getReservationJson() == null || anchor.getReservationJson().isBlank()) return true;
        try {
            DataAnalysisReservation current = objectMapper.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
            if (current.state() == DataAnalysisReservationState.RELEASED) return true;

            // Transition to TERMINAL_CONFIRMED (required for Terminal proof)
            DataAnalysisReservation confirmed;
            if (current.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
                confirmed = new DataAnalysisReservation(current.reservationId(), current.identity(),
                        current.resourceClass(), current.capacityUnits(),
                        DataAnalysisReservationState.TERMINAL_CONFIRMED,
                        current.taskId(), current.acquiredAt());
                DataAnalysisRestoreOutcome ro = capacityService.restoreReservation(confirmed);
                if (ro == DataAnalysisRestoreOutcome.CONFLICT) {
                    // Crash recovery: may already be RELEASED by prior attempt
                    // Try releaseReservation directly to detect ALREADY_RELEASED
                    DataAnalysisTerminalEnvelope env = buildEnvelope(confirmed, anchor);
                    if (env != null) {
                        DataAnalysisReleaseRequest req = new DataAnalysisReleaseRequest(confirmed,
                                new DataAnalysisReleaseProof.Terminal(env),
                                DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
                        DataAnalysisReleaseOutcome oo = capacityService.releaseReservation(req);
                        if (oo == DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                            writeReleasedReservation(anchor, confirmed);
                            return true;
                        }
                    }
                    log.warn("Reservation restore CONFLICT for id={}, release also failed", current.reservationId());
                    return false;
                }
            } else {
                confirmed = current;
            }

            DataAnalysisTerminalEnvelope envelope = buildEnvelope(confirmed, anchor);
            if (envelope == null) return false;

            DataAnalysisReleaseRequest req = new DataAnalysisReleaseRequest(confirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            DataAnalysisReleaseOutcome oo = capacityService.releaseReservation(req);
            boolean ok = oo == DataAnalysisReleaseOutcome.RELEASED
                    || oo == DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            if (!ok) {
                log.warn("Release outcome {} for reservationId={}", oo, confirmed.reservationId());
                return false;
            }

            // Write RELEASED state back to anchor so restart recovery doesn't
            // re-occupy capacity from stale PENDING/CONFIRMED reservation JSON
            return writeReleasedReservation(anchor, confirmed);
        } catch (Exception e) {
            log.error("releaseCapacity failed for reservation", e);
            return false;
        }
    }

    /** Serialize RELEASED state back to anchor.reservationJson so restart skips it. */
    private boolean writeReleasedReservation(ToolJobAnchor anchor,
                                              DataAnalysisReservation confirmed) throws Exception {
        DataAnalysisReservation released = new DataAnalysisReservation(
                confirmed.reservationId(), confirmed.identity(),
                confirmed.resourceClass(), confirmed.capacityUnits(),
                DataAnalysisReservationState.RELEASED,
                confirmed.taskId(), confirmed.acquiredAt());
        anchor.setReservationJson(objectMapper.writeValueAsString(released));
        return true;
    }

    private DataAnalysisTerminalEnvelope buildEnvelope(DataAnalysisReservation reservation, ToolJobAnchor anchor) {
        try {
            DataAnalysisResourceUsage usage = buildResourceUsage(reservation.resourceClass(),
                    anchor.getTerminalUsageJson());
            DataAnalysisEstimate estimate = parseEstimate(anchor.getEstimateJson());
            if (estimate == null) return null;
            String status = anchor.getTerminalStatus();
            boolean success = "SUCCEEDED".equals(status);
            String rawRef = anchor.getTerminalRawRef();
            String preview = boundedPreview(anchor.getTerminalResultPreview());
            String errorCode = anchor.getTerminalErrorCode();

            if (success && rawRef == null && preview == null) {
                log.warn("SUCCEEDED without preview/rawRef for op={}", anchor.getOperationId());
                preview = "(preview unavailable)";
            }
            if (!success && errorCode == null) errorCode = status;

            return new DataAnalysisTerminalEnvelope(
                    reservation.identity().runId(), reservation.identity().toolCallId(),
                    reservation.identity().attempt(), reservation.operationId(), reservation.taskId(),
                    status, success, preview, rawRef, errorCode,
                    success ? null : "sandbox " + status,
                    !success && !"RESULT_LOST".equals(status),
                    estimate, reservation, usage, anchor.getTerminalAt(), true);
        } catch (Exception e) {
            log.error("buildEnvelope failed for reservationId={}, status={}",
                    reservation.reservationId(), anchor.getTerminalStatus(), e);
            return null;
        }
    }

    private DataAnalysisResourceUsage buildResourceUsage(DataAnalysisResourceClass rc, String usageJson) {
        if (usageJson == null || usageJson.isBlank()) return DataAnalysisResourceUsage.missing(rc);
        try {
            SandboxResourceUsage s = objectMapper.readValue(usageJson, SandboxResourceUsage.class);

            Long cpu = s.hasCpuMillis() ? s.getCpuMillis() : null;
            Long mem = s.hasMemoryPeakBytes() ? s.getMemoryPeakBytes() : null;
            Long memMs = s.hasMemoryByteMillis() ? s.getMemoryByteMillis() : null;
            Long scan = s.hasLogicalBytesScanned() ? s.getLogicalBytesScanned() : null;
            Long art = s.hasArtifactBytesWritten() ? s.getArtifactBytesWritten() : null;
            Long tmp = s.hasTemporaryBytesWritten() ? s.getTemporaryBytesWritten() : null;
            Long qwait = s.hasQueueWaitMillis() ? s.getQueueWaitMillis() : null;
            Long prep = s.hasPrepareMillis() ? s.getPrepareMillis() : null;
            Long exec = s.hasExecutionWallMillis() ? s.getExecutionWallMillis() : null;
            Long clean = s.hasCleanupMillis() ? s.getCleanupMillis() : null;
            Integer dsOpen = s.hasDatasetOpenCount() ? s.getDatasetOpenCount() : null;

            List<String> missing = new ArrayList<>();
            if (cpu == null) missing.add("cpuMillis");
            if (mem == null) missing.add("memoryPeakBytes");
            if (scan == null) missing.add("logicalBytesScanned");
            if (qwait == null) missing.add("queueWaitMillis");
            if (prep == null) missing.add("prepareMillis");
            if (exec == null) missing.add("executionWallMillis");
            if (clean == null) missing.add("cleanupMillis");
            if (dsOpen == null) missing.add("datasetOpenCount");
            if (s.getExitReason().isBlank()) missing.add("exitReason");

            if (missing.isEmpty()) {
                return new DataAnalysisResourceUsage(rc, cpu, mem, memMs, scan, art, tmp,
                        qwait, prep, exec, clean, dsOpen, s.getExitReason(),
                        s.getOomKilled(), s.getTimedOut(), true, null, null);
            }
            // Partial: keep measured values, declare only actually-missing fields
            return new DataAnalysisResourceUsage(rc, cpu, mem, memMs, scan, art, tmp,
                    qwait, prep, exec, clean, dsOpen, s.getExitReason(),
                    s.getOomKilled(), s.getTimedOut(), false, null, missing);
        } catch (Exception e) {
            log.warn("Failed to parse resourceUsage, using missing", e);
            return DataAnalysisResourceUsage.missing(rc);
        }
    }

    /** @return parsed estimate or null (fail-closed: blocks RELEASE) */
    private DataAnalysisEstimate parseEstimate(String estimateJson) {
        if (estimateJson == null || estimateJson.isBlank()) {
            log.warn("estimateJson missing — cannot build valid envelope");
            return null;
        }
        try {
            return objectMapper.readValue(estimateJson, DataAnalysisEstimate.class);
        } catch (Exception e) {
            log.error("Failed to parse estimateJson", e);
            return null;
        }
    }

    // ========== helpers ==========

    private boolean isStepDone(ToolJobAnchor anchor, String step) {
        String current = anchor.getFinalizerStep();
        if (current == null) return false;
        return STEP_ORDER.getOrDefault(current, 0) >= STEP_ORDER.getOrDefault(step, 0);
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** Truncate to 16KB UTF-8 respecting MAX_RESULT_PREVIEW_BYTES including suffix. */
    static String boundedPreview(String s) {
        if (s == null) return null;
        String suffix = "…(truncated)";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        int max = DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;
        if (raw.length <= max) return s;
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        int cut = max - suffixBytes.length;
        if (cut <= 0) return suffix;
        while (cut > 0 && (raw[cut] & 0xC0) == 0x80) cut--;
        return new String(raw, 0, cut, StandardCharsets.UTF_8) + suffix;
    }
}
