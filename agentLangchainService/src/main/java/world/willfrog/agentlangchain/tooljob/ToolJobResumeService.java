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
    private final ToolJobConfig config;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentRunDatasetRegistry datasetRegistry;

    @Autowired(required = false)
    private ToolJobResumeLauncher resumeLauncher;

    public ToolJobResumeService(ToolJobAnchorService anchorService,
                                ToolJobRedisCache redisCache, ToolJobConfig config,
                                ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public boolean tryResume(String runId) {
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) return false;

        String state = anchor.getResumeState();
        if ("CONSUMED".equals(state)) {
            // Durable clear FIRST (DB before cache). Only delete Redis if the
            // token-gated durable clear succeeds. If it fails, leave Redis intact
            // so the next scan cycle retries. Never delete Redis before DB clear.
            String token = anchor.getResumeToken();
            if (token != null && !token.isBlank()) {
                if (!anchorService.clearAnchorWithToken(runId, "CONSUMED", token,
                        anchor.getResumeLeaseVersion())) {
                    log.warn("CONSUMED durable clear failed for run={}, leaving Redis for retry", runId);
                    return true; // anchor still CONSUMED, will retry next scan
                }
            }
            redisCache.removeDue(runId);
            redisCache.deletePendingCache(runId);
            return true;
        }
        if ("READY".equals(state)) return launchFromReady(runId, anchor);
        if ("LAUNCHING".equals(state)) return reenterLaunching(runId, anchor);
        return false;
    }

    private boolean launchFromReady(String runId, ToolJobAnchor anchor) {
        // Snapshot pre-claim identity for optimistic-lock CAS
        String expectedToken = anchor.getResumeToken();
        long expectedVersion = anchor.getResumeLeaseVersion();

        // Increment version atomically in the claim so stale replays fail
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeLeaseVersion(expectedVersion + 1);
        anchor.setResumeClaimedAt(java.time.Instant.now());

        if (!anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "READY",
                expectedToken, expectedVersion)) {
            log.info("Resume CAS READY→LAUNCHING failed for run={}", runId);
            return false;
        }

        // Track claimed identity for rollback
        String claimedToken = anchor.getResumeToken();
        long claimedVersion = anchor.getResumeLeaseVersion();

        // Fail-closed: restore failure blocks resume
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed for run={}, rolling back to READY", runId);
            rollbackToReady(runId, anchor, expectedVersion, claimedToken, claimedVersion);
            return false;
        }
        return doLaunch(runId, anchor, expectedVersion, claimedToken, claimedVersion);
    }

    private void rollbackToReady(String runId, ToolJobAnchor anchor, long originalVersion,
                                  String claimedToken, long claimedVersion) {
        // Monotonic: bump version again on rollback. Never revert to originalVersion —
        // that would create an ABA gap where the old token/version pair becomes valid again.
        long nextVersion = claimedVersion + 1;
        anchor.setResumeState("READY");
        anchor.setResumeLeaseVersion(nextVersion);
        anchor.setResumeClaimedAt(null);
        anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING",
                claimedToken, claimedVersion);
    }

    private boolean reenterLaunching(String runId, ToolJobAnchor anchor) {
        log.info("Re-entering LAUNCHING resume for run={}", runId);

        // Stale detection: if claimedAt + TTL has passed AND launcher reports
        // inactive, roll back to READY for a fresh claim attempt
        if (anchor.getResumeClaimedAt() != null) {
            long staleDeadline = anchor.getResumeClaimedAt().toEpochMilli()
                    + config.getLaunchingStaleSeconds() * 1000;
            if (System.currentTimeMillis() > staleDeadline) {
                long claimedVersion = anchor.getResumeLeaseVersion();
                String claimedToken = anchor.getResumeToken();
                // Check if launcher still considers this token active before rolling back
                if (resumeLauncher != null && resumeLauncher.isActive(runId, claimedToken, claimedVersion)) {
                    log.info("LAUNCHING claim past TTL but launcher still active for run={} token={} v{}",
                            runId, claimedToken, claimedVersion);
                    return false; // don't roll back — launcher is still running
                }
                log.warn("LAUNCHING claim stale for run={}, claimedAt={}, rolling back to READY",
                        runId, anchor.getResumeClaimedAt());
                // Bump version and set back to READY for fresh claim
                anchor.setResumeState("READY");
                anchor.setResumeLeaseVersion(claimedVersion + 1);
                anchor.setResumeToken(java.util.UUID.randomUUID().toString());
                anchor.setResumeClaimedAt(null);
                anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING",
                        claimedToken, claimedVersion);
                return false; // will retry as READY on next scan
            }
        }

        // Active LAUNCHING: re-call the launcher (idempotent on token+version)
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed on LAUNCHING reentry for run={}, will retry", runId);
            return false;
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

    private boolean doLaunch(String runId, ToolJobAnchor anchor,
                              long originalVersion, String claimedToken, long claimedVersion) {
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — rolling back run={}", runId);
            rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
            return false;
        }
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            if (!resumeLauncher.launch(runId, ctx)) {
                rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Launch threw for run={}, rolling back", runId, e);
            rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
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

        // Token+state+version-gated durable clear FIRST, then Redis (DB before cache)
        String token = anchor.getResumeToken();
        if (token == null || token.isBlank()) {
            log.warn("No resumeToken for run={} — refusing to clear anchor, will retry", runId);
            return;
        }
        if (!anchorService.clearAnchorWithToken(runId, "CONSUMED", token,
                anchor.getResumeLeaseVersion())) {
            log.warn("Token+state+version-gated clear failed for run={} — mismatch, retrying", runId);
            return; // keep Redis cache, retry on next cycle
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
