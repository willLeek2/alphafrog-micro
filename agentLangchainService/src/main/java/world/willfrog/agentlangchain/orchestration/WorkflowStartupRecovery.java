package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 单实例服务启动后的一次性遗留 Run 扫描。
 *
 * <p>它不是常驻 reconciler，也没有多实例租约。多实例部署前必须关闭本 Bean，
 * 或把 {@code claimStartupRestart} 升级成具备实例所有权和租约的协议。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "agent.workflow-restart", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WorkflowStartupRecovery {

    private final AgentRunMapper runMapper;
    private final LangchainLinearRunPipeline pipeline;
    private final AgentEventService eventService;
    private final AgentRunFinalizationService finalizationService;

    @Autowired(required = false)
    private LangchainSchedulerMetrics schedulerMetrics;

    @Value("${agent.workflow-restart.max-restart-attempts:1}")
    private int maxRestartAttempts;

    @Value("${agent.workflow-restart.scan-limit:100}")
    private int scanLimit;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        OffsetDateTime startedBefore = OffsetDateTime.now();
        int boundedLimit = Math.max(1, Math.min(scanLimit, 1000));
        List<AgentRun> candidates = runMapper.listStartupRecoveryCandidates(startedBefore, boundedLimit);
        for (AgentRun candidate : candidates) {
            recoverOne(candidate);
        }
        log.info("Workflow startup recovery scanned {} candidate(s), limit={}",
                candidates.size(), boundedLimit);
    }

    void recoverOne(AgentRun candidate) {
        if (candidate == null || candidate.getStatus() == null) {
            return;
        }
        String runId = candidate.getId();
        String userId = candidate.getUserId();
        AgentRunStatus status = candidate.getStatus();
        try {
            if (status == AgentRunStatus.CANCELING) {
                if (runMapper.completeStartupCancellation(runId) == 1) {
                    if (schedulerMetrics != null) {
                        schedulerMetrics.recordCompletion(AgentRunStatus.CANCELED);
                    }
                    eventService.append(runId, userId, "WORKFLOW_RESTART_CANCELED", Map.of(
                            "reason", "canceling_during_service_restart"));
                    finalizationService.publishFinalizedEvent(
                            runId, userId, AgentRunStatus.CANCELED.name());
                }
                return;
            }
            int attempt = candidate.getRestartAttempt() == null ? 0 : candidate.getRestartAttempt();
            int maxAttempts = Math.max(0, maxRestartAttempts);
            if (attempt >= maxAttempts) {
                fail(candidate, "workflow_restart_attempts_exhausted");
                return;
            }
            if (candidate.getTtlExpiresAt() != null
                    && OffsetDateTime.now().isAfter(candidate.getTtlExpiresAt())) {
                fail(candidate, "workflow_restart_ttl_expired");
                return;
            }
            boolean frozenPlan = hasFrozenPlan(candidate);
            boolean replan = (status == AgentRunStatus.RECEIVED || status == AgentRunStatus.PLANNING)
                    && !frozenPlan;
            if (!replan && !frozenPlan) {
                fail(candidate, "workflow_restart_plan_missing");
                return;
            }
            if (runMapper.claimStartupRestart(
                    runId, status, attempt, maxAttempts) != 1) {
                return;
            }
            AgentRun claimed = runMapper.findById(runId);
            if (claimed == null) {
                return;
            }
            boolean accepted;
            if (replan) {
                pipeline.launchAsync(claimed);
                accepted = true;
            } else {
                accepted = pipeline.launchRestartedAsync(claimed);
            }
            if (!accepted) {
                failClaimed(claimed, "workflow_restart_scheduler_rejected");
                return;
            }
            eventService.append(runId, userId, "WORKFLOW_RESTART_QUEUED", Map.of(
                    "restart_attempt", claimed.getRestartAttempt() == null ? attempt + 1 : claimed.getRestartAttempt(),
                    "planner_skipped", !replan,
                    "previous_status", status.name()));
        } catch (Exception e) {
            log.error("Workflow startup recovery failed for run={}", runId, e);
            AgentRun latest = runMapper.findById(runId);
            if (latest != null) {
                failClaimed(latest, "workflow_restart_launch_failed:" + safeMessage(e));
            }
        }
    }

    private void fail(AgentRun run, String reason) {
        if (runMapper.failStartupRecovery(run.getId(), run.getStatus(), reason) == 1) {
            publishRejected(run, reason);
        }
    }

    private void failClaimed(AgentRun run, String reason) {
        if (runMapper.failStartupRecovery(run.getId(), AgentRunStatus.RECEIVED, reason) == 1) {
            publishRejected(run, reason);
        }
    }

    private void publishRejected(AgentRun run, String reason) {
        if (schedulerMetrics != null) {
            schedulerMetrics.recordCompletion(AgentRunStatus.FAILED);
        }
        try {
            eventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESTART_REJECTED", Map.of(
                    "reason", reason,
                    "restart_attempt", run.getRestartAttempt() == null ? 0 : run.getRestartAttempt()));
        } finally {
            finalizationService.publishFinalizedEvent(
                    run.getId(), run.getUserId(), AgentRunStatus.FAILED.name());
        }
    }

    private boolean hasFrozenPlan(AgentRun run) {
        String planJson = run.getPlanJson();
        return planJson != null && !planJson.isBlank() && !"{}".equals(planJson.trim());
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
