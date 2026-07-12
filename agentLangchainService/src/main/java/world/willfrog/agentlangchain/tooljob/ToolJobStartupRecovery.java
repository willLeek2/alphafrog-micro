package world.willfrog.agentlangchain.tooljob;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * On startup, scans all DB anchors and rebuilds Redis cache/due ZSET,
 * resolves PREPARING anchors against the sandbox, and rebuilds the capacity ledger.
 */
@Service
public class ToolJobStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ToolJobStartupRecovery.class);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final DataAnalysisCapacityService capacityService;
    private final ToolJobFinalizer finalizer;
    private final ToolJobConfig config;

    @DubboReference
    private PythonSandboxService sandboxService;

    public ToolJobStartupRecovery(ToolJobAnchorService anchorService,
                                  ToolJobRedisCache redisCache,
                                  DataAnalysisCapacityService capacityService,
                                  ToolJobFinalizer finalizer,
                                  ToolJobConfig config) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.capacityService = capacityService;
        this.finalizer = finalizer;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("T3 startup recovery beginning");
        try {
            recoverCapacityLedger();
            recoverToolJobAnchors();
            log.info("T3 startup recovery complete");
        } catch (Exception e) {
            log.error("T3 startup recovery failed", e);
        }
    }

    /**
     * Rebuild capacity ledger: collect all non-RELEASED reservations from DB anchors
     * and feed them to the capacity service.
     */
    private void recoverCapacityLedger() {
        List<AgentRun> activeRuns = anchorService.listActive(200);
        List<DataAnalysisReservation> durableReservations = new ArrayList<>();

        for (AgentRun run : activeRuns) {
            ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
            if (anchor == null || anchor.getReservationJson() == null) {
                continue;
            }
            try {
                DataAnalysisReservation reservation = parseReservationJson(anchor.getReservationJson());
                if (reservation != null && reservation.state() != DataAnalysisReservationState.RELEASED) {
                    durableReservations.add(reservation);
                }
            } catch (Exception e) {
                log.error("Failed to parse reservation for run={}", run.getId(), e);
            }
        }

        if (!durableReservations.isEmpty()) {
            DataAnalysisCapacityRecoveryReport report = capacityService.recover(
                    durableReservations, 100, 5);
            log.info("Capacity recovery: restored={} state={}", report.restoredReservations(), report.admissionState());
        }
    }

    /**
     * Scan all anchors and resolve each based on its state.
     */
    private void recoverToolJobAnchors() {
        List<AgentRun> activeRuns = anchorService.listActive(200);

        for (AgentRun run : activeRuns) {
            ToolJobAnchor anchor = anchorService.loadAnchor(run.getId());
            if (anchor == null) {
                continue;
            }

            try {
                String resumeState = anchor.getResumeState();
                if ("CONSUMED".equals(resumeState)) {
                    // Clean up leftover cache
                    redisCache.removeDue(run.getId());
                    redisCache.deletePendingCache(run.getId());
                    continue;
                }
                if ("READY".equals(resumeState) || "LAUNCHING".equals(resumeState)) {
                    // Resume was in progress — rebuild Redis and let the pipeline continue
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }

                // RESULT_FETCH_PENDING: continue polling for result
                if ("PENDING".equals(anchor.getResultFetchState())) {
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }

                // TERMINAL/FINALIZING: continue from last finalizer step
                if (anchor.getFinalizerStep() != null && !anchor.getFinalizerStep().isBlank()) {
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                    continue;
                }

                // ATTACHED/PENDING: rebuild reservation and due/cache
                resolveActiveAnchor(run, anchor);

            } catch (Exception e) {
                log.error("Failed to recover anchor for run={}", run.getId(), e);
            }
        }
    }

    private void resolveActiveAnchor(AgentRun run, ToolJobAnchor anchor) {
        String taskId = anchor.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.warn("Anchor for run={} has no taskId, skipping", run.getId());
            return;
        }

        try {
            TaskStatusResponse statusResp = sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
            String status = statusResp.getStatus();

            if ("NOT_FOUND".equals(status)) {
                // Sandbox task gone — check if we should retry or mark lost
                if (anchor.getTerminalConfirmedAt() == null) {
                    anchor.setResultFetchState("PENDING");
                    anchor.setTerminalConfirmedAt(Instant.now());
                    anchor.setResultFetchAttempts(1);
                    anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
                    anchorService.updateAnchor(run.getId(), anchor, AgentRunStatus.WAITING_TOOL_JOB);
                    redisCache.atomicWritePendingAndDue(run.getId(), anchor);
                }
                return;
            }

            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status)) {
                // Terminal — run through finalizer
                finalizer.handleTerminal(run.getId(), anchor, statusResp);
                return;
            }

            // Still running — rebuild due/cache and resume polling
            if (anchor.isAutoResume()) {
                anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
            }
            anchorService.updateAnchor(run.getId(), anchor, AgentRunStatus.WAITING_TOOL_JOB);
            redisCache.atomicWritePendingAndDue(run.getId(), anchor);

        } catch (Exception e) {
            log.error("Failed to resolve anchor for run={}, taskId={}", run.getId(), taskId, e);
            // Rebuild Redis with next poll in the future so reconciler will retry
            anchor.setNextPollAt(Instant.now().plusMillis(config.getReconcilerIntervalMs()));
            redisCache.atomicWritePendingAndDue(run.getId(), anchor);
        }
    }

    private DataAnalysisReservation parseReservationJson(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            return mapper.readValue(json, DataAnalysisReservation.class);
        } catch (Exception e) {
            log.error("Failed to parse reservation JSON", e);
            return null;
        }
    }
}
