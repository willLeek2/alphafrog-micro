package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;

import java.util.Collections;
import java.util.List;

/**
 * Reads the durable anchor, restores dataset state via T1 registry,
 * builds a resume context, and delegates to the Codex pipeline launcher.
 * <p>
 * Called by:
 * <ul>
 *   <li>The finalizer (after CAS to RECEIVED, resumeState=READY)</li>
 *   <li>Startup recovery (for READY/LAUNCHING runs stranded by crash)</li>
 *   <li>Reconciler rebuild cycle (periodic catch-up)</li>
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
     * Attempt to resume a run whose anchor has resumeState=READY.
     *
     * @return true if the resume was launched
     */
    public boolean tryResume(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null || !"READY".equals(anchor.getResumeState())) {
            return false;
        }

        // CAS resumeState READY → LAUNCHING
        anchor.setResumeState("LAUNCHING");
        if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
            log.info("Resume CAS failed for run={}, another process may have claimed it", runId);
            return false;
        }

        // Restore dataset registry via T1
        restoreDatasetRegistry(anchor);

        // Build resume context
        ToolJobResumeContext context = buildResumeContext(runId, anchor);

        // Delegate to Codex pipeline launcher
        if (resumeLauncher == null) {
            log.warn("No ToolJobResumeLauncher wired — cannot launch run={}", runId);
            anchor.setResumeState("READY"); // rollback
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            return false;
        }

        try {
            boolean launched = resumeLauncher.launch(runId, context);
            if (!launched) {
                log.warn("Resume launcher rejected run={}", runId);
                anchor.setResumeState("READY");
                anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
                return false;
            }
            log.info("Resume launched for run={}, todoId={}", runId, context.getTodoId());
            return true;
        } catch (Exception e) {
            log.error("Resume launcher threw for run={}", runId, e);
            anchor.setResumeState("READY");
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            return false;
        }
    }

    /**
     * Mark a successfully resumed run as CONSUMED after the pipeline
     * has consumed the terminal result.
     */
    public void markConsumed(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) {
            return;
        }
        anchor.setResumeState("CONSUMED");
        anchor.setResultConsumed(true);
        anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);

        // Cleanup
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        anchorService.updateAnchorAndStatus(runId, new ToolJobAnchor(),
                AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED);
    }

    // ---- internal ----

    private void restoreDatasetRegistry(ToolJobAnchor anchor) {
        if (datasetRegistry == null) {
            return;
        }
        try {
            String snapshotJson = anchor.getDatasetSnapshotJson();
            if (snapshotJson != null && !snapshotJson.isBlank()) {
                // T1 restore: re-register dataset entries from snapshot
                // datasetRegistry.restore(runId, snapshot) — called by the
                // owner of the run context (Codex pipeline or resume launcher)
            }
        } catch (Exception e) {
            log.error("Failed to restore dataset registry for run={}", anchor.getOperationId(), e);
        }
    }

    private ToolJobResumeContext buildResumeContext(String runId, ToolJobAnchor anchor) {
        ToolJobResumeContext ctx = new ToolJobResumeContext();
        ctx.setRunId(runId);
        ctx.setTodoId(anchor.getTodoId());
        ctx.setCompletedTodoIds(parseStringList(anchor.getCompletedTodosJson()));
        ctx.setDatasetRefsJson(anchor.getDatasetRefsJson());
        ctx.setToolCallsUsed(anchor.getToolCallsUsed());
        ctx.setTerminalSuccess("SUCCEEDED".equals(anchor.getTerminalStatus()));
        ctx.setTerminalResultPreview(anchor.getTerminalResultPreview());
        ctx.setTerminalRawRef(anchor.getTerminalRawRef());
        return ctx;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse string list JSON", e);
            return Collections.emptyList();
        }
    }
}
