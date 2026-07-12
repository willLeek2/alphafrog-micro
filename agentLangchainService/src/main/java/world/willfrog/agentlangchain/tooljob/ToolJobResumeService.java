package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;

import java.util.Collections;
import java.util.List;

/**
 * Reads the durable anchor, restores dataset state via T1 registry,
 * builds a resume context, and delegates to the Codex pipeline launcher.
 * <p>
 * CAS protocol:
 * <ul>
 *   <li>READY → LAUNCHING: atomic CAS on both status=RECEIVED AND resumeState=READY</li>
 *   <li>LAUNCHING: idempotent re-launch if launcher supports it (crash recovery)</li>
 *   <li>CONSUMED: no-op, already finished</li>
 * </ul>
 */
@Service
public class ToolJobResumeService {

    private static final Logger log = LoggerFactory.getLogger(ToolJobResumeService.class);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentRunDatasetRegistry datasetRegistry;

    @Autowired(required = false)
    private ToolJobResumeLauncher resumeLauncher;

    public ToolJobResumeService(ToolJobAnchorService anchorService,
                                ToolJobRedisCache redisCache,
                                ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempt to resume a run. Handles READY (first launch attempt) and
     * LAUNCHING (crash recovery via idempotent re-launch).
     *
     * @return true if the resume was launched or already in progress
     */
    public boolean tryResume(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) {
            return false;
        }

        String resumeState = anchor.getResumeState();
        if ("CONSUMED".equals(resumeState)) {
            // Already done — just clean up Redis
            redisCache.removeDue(runId);
            redisCache.deletePendingCache(runId);
            return true;
        }

        if ("READY".equals(resumeState)) {
            return launchFromReady(runId, anchor);
        }

        if ("LAUNCHING".equals(resumeState)) {
            return reenterLaunching(runId, anchor);
        }

        return false;
    }

    /**
     * First launch attempt: atomic CAS READY → LAUNCHING, then launch.
     */
    private boolean launchFromReady(String runId, ToolJobAnchor anchor) {
        // Restore dataset registry before claiming (best-effort, failure is non-fatal)
        restoreDatasetRegistry(anchor);

        // Atomic CAS: claim READY → LAUNCHING
        anchor.setResumeState("LAUNCHING");
        boolean claimed = anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "READY");
        if (!claimed) {
            log.info("Resume CAS READY→LAUNCHING failed for run={}, another process claimed it", runId);
            return false;
        }

        return doLaunch(runId, anchor);
    }

    /**
     * Crash recovery: re-enter a LAUNCHING anchor.
     * The launcher must be idempotent — if the run is already executing, it should
     * return true (already launched). If it was never started, it starts it now.
     */
    private boolean reenterLaunching(String runId, ToolJobAnchor anchor) {
        log.info("Re-entering LAUNCHING resume for run={}", runId);

        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — cannot recover LAUNCHING run={}", runId);
            return false;
        }

        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            boolean accepted = resumeLauncher.launch(runId, ctx);
            if (accepted) {
                log.info("Re-launch accepted for run={}", runId);
                return true;
            }
            log.warn("Re-launch rejected for run={}", runId);
            return false;
        } catch (Exception e) {
            log.error("Re-launch threw for run={}, will retry on next scan", runId, e);
            return false;
        }
    }

    private boolean doLaunch(String runId, ToolJobAnchor anchor) {
        if (resumeLauncher == null) {
            log.warn("No ToolJobResumeLauncher wired — cannot launch run={}, rolling back to READY", runId);
            anchor.setResumeState("READY");
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
            return false;
        }

        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            boolean accepted = resumeLauncher.launch(runId, ctx);
            if (!accepted) {
                log.warn("Resume launcher rejected run={}, rolling back to READY", runId);
                anchor.setResumeState("READY");
                anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
                return false;
            }
            log.info("Resume launched for run={}, todoId={}", runId, ctx.getTodoId());
            return true;
        } catch (Exception e) {
            log.error("Resume launcher threw for run={}, rolling back to READY", runId, e);
            anchor.setResumeState("READY");
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
            return false;
        }
    }

    /**
     * Mark a successfully resumed run as CONSUMED.
     * Only clears the anchor if both usage and terminal event have been persisted
     * (§9.3 / §9.8 — anchor cleanup must wait for usagePersisted && terminalEventEmitted).
     */
    public void markConsumed(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) {
            return;
        }

        // Guard: do not clean up until usage and event are done
        if (!anchor.isUsagePersisted() || !anchor.isTerminalEventEmitted()) {
            log.info("Deferring cleanup for run={}: usagePersisted={}, terminalEventEmitted={}",
                    runId, anchor.isUsagePersisted(), anchor.isTerminalEventEmitted());
            anchor.setResumeState("CONSUMED");
            anchor.setResultConsumed(true);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            return;
        }

        anchor.setResumeState("CONSUMED");
        anchor.setResultConsumed(true);
        anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);

        // Full cleanup: clear Redis cache + DB anchor
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        anchorService.updateAnchorAndStatus(runId, new ToolJobAnchor(),
                AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED);
        log.info("Full cleanup completed for run={}", runId);
    }

    // ---- internal ----

    private void restoreDatasetRegistry(ToolJobAnchor anchor) {
        if (datasetRegistry == null) {
            log.debug("No AgentRunDatasetRegistry wired, skipping restore for run={}", anchor.getOperationId());
            return;
        }
        String snapshotJson = anchor.getDatasetSnapshotJson();
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return;
        }
        try {
            world.willfrog.agent.workflow.AgentRunDatasetSnapshot snapshot =
                    objectMapper.readValue(snapshotJson, world.willfrog.agent.workflow.AgentRunDatasetSnapshot.class);
            datasetRegistry.restore(anchor.getOperationId(), snapshot);
            log.info("Dataset registry restored for run={}", anchor.getOperationId());
        } catch (Exception e) {
            // Restore failure is non-fatal for resume — the run will use
            // what's available; the error is logged for debugging.
            log.error("Dataset registry restore failed for run={}, continuing with launch",
                    anchor.getOperationId(), e);
        }
    }

    ToolJobResumeContext buildResumeContext(String runId, ToolJobAnchor anchor) {
        ToolJobResumeContext ctx = new ToolJobResumeContext();
        ctx.setRunId(runId);
        ctx.setTodoId(anchor.getTodoId());
        ctx.setCompletedTodos(parseCompletedTodos(anchor.getCompletedTodosJson()));
        ctx.setDatasetSnapshotJson(anchor.getDatasetSnapshotJson());
        ctx.setDatasetSnapshotDigest(anchor.getDatasetSnapshotDigest());
        ctx.setToolCallsUsed(anchor.getToolCallsUsed());
        ctx.setTerminalSuccess("SUCCEEDED".equals(anchor.getTerminalStatus()));
        ctx.setTerminalResultPreview(anchor.getTerminalResultPreview());
        ctx.setTerminalRawRef(anchor.getTerminalRawRef());
        return ctx;
    }

    List<CompletedTodoRecord> parseCompletedTodos(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CompletedTodoRecord>>() {});
        } catch (JsonProcessingException e) {
            // Fall back to legacy string-list format
            try {
                List<String> ids = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                return ids.stream()
                        .map(id -> new CompletedTodoRecord(id, null, 0, 0))
                        .toList();
            } catch (JsonProcessingException ex2) {
                log.warn("Failed to parse completedTodosJson", ex2);
                return Collections.emptyList();
            }
        }
    }
}
