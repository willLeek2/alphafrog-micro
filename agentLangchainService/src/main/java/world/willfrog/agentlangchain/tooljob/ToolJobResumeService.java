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
                                ToolJobRedisCache redisCache, ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
    }

    public boolean tryResume(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) return false;

        String state = anchor.getResumeState();
        if ("CONSUMED".equals(state)) {
            redisCache.removeDue(runId);
            redisCache.deletePendingCache(runId);
            return true;
        }
        if ("READY".equals(state)) return launchFromReady(runId, anchor);
        if ("LAUNCHING".equals(state)) return reenterLaunching(runId, anchor);
        return false;
    }

    private boolean launchFromReady(String runId, ToolJobAnchor anchor) {
        anchor.setResumeState("LAUNCHING");
        if (!anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "READY")) {
            log.info("Resume CAS READY→LAUNCHING failed for run={}", runId);
            return false;
        }
        // Fail-closed: restore failure blocks resume
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed for run={}, rolling back to READY", runId);
            anchor.setResumeState("READY");
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
            return false;
        }
        return doLaunch(runId, anchor);
    }

    private boolean reenterLaunching(String runId, ToolJobAnchor anchor) {
        log.info("Re-entering LAUNCHING resume for run={}", runId);
        // Restore registry on reentry too (may have crashed before restore completed)
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed on LAUNCHING reentry for run={}, will retry", runId);
            return false; // retry on next scan
        }
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — cannot recover LAUNCHING run={}", runId);
            return false;
        }
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            return resumeLauncher.launch(runId, ctx);
        } catch (Exception e) {
            log.error("Re-launch threw for run={}, will retry", runId, e);
            return false;
        }
    }

    private boolean doLaunch(String runId, ToolJobAnchor anchor) {
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — rolling back run={}", runId);
            anchor.setResumeState("READY");
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
            return false;
        }
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            if (!resumeLauncher.launch(runId, ctx)) {
                anchor.setResumeState("READY");
                anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Launch threw for run={}, rolling back", runId, e);
            anchor.setResumeState("READY");
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING");
            return false;
        }
    }

    public void markConsumed(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) return;

        if (!anchor.isUsagePersisted() || !anchor.isTerminalEventEmitted()) {
            log.info("Deferring cleanup for run={}: usagePersisted={} terminalEventEmitted={}",
                    runId, anchor.isUsagePersisted(), anchor.isTerminalEventEmitted());
            anchor.setResumeState("CONSUMED");
            anchor.setResultConsumed(true);
            anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
            return;
        }

        anchor.setResumeState("CONSUMED");
        anchor.setResultConsumed(true);
        if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
            log.warn("markConsumed anchor update CAS failed for run={}, will retry", runId);
            return;
        }

        // Token-gated durable clear FIRST, then Redis (DB before cache)
        String token = anchor.getResumeToken();
        if (token != null && !token.isBlank()) {
            if (!anchorService.clearAnchorWithToken(runId, token)) {
                log.warn("Token-gated clear failed for run={}, token mismatch — another consumer already cleared", runId);
                // Still clean Redis (best-effort: anchor already cleared by winner)
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
                return;
            }
        } else {
            anchorService.clearAnchor(runId);
        }
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        log.info("Full cleanup completed for run={}", runId);
    }

    // ---- internal ----

    /** @return true if restore succeeded or no snapshot to restore */
    private boolean restoreDatasetRegistry(String runId, ToolJobAnchor anchor) {
        if (datasetRegistry == null) return true;
        String snapshotJson = anchor.getDatasetSnapshotJson();
        if (snapshotJson == null || snapshotJson.isBlank()) return true;
        try {
            world.willfrog.agent.workflow.AgentRunDatasetSnapshot snapshot =
                    objectMapper.readValue(snapshotJson, world.willfrog.agent.workflow.AgentRunDatasetSnapshot.class);
            datasetRegistry.restore(runId, snapshot);
            log.info("Dataset registry restored for run={}", runId);
            return true;
        } catch (Exception e) {
            log.error("Dataset registry restore FAILED for run={}, blocking resume", runId, e);
            return false;
        }
    }

    ToolJobResumeContext buildResumeContext(String runId, ToolJobAnchor anchor) {
        ToolJobResumeContext ctx = new ToolJobResumeContext();
        ctx.setRunId(runId);
        ctx.setTodoId(anchor.getTodoId());
        ctx.setResumeToken(anchor.getResumeToken());
        ctx.setResumeLeaseVersion(anchor.getResumeLeaseVersion());
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
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<CompletedTodoRecord>>() {});
        } catch (JsonProcessingException e) {
            try {
                List<String> ids = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                return ids.stream().map(id -> { var r = new CompletedTodoRecord(); r.setTodoId(id); return r; }).toList();
            } catch (JsonProcessingException ex2) {
                log.warn("Failed to parse completedTodosJson", ex2);
                return Collections.emptyList();
            }
        }
    }
}
