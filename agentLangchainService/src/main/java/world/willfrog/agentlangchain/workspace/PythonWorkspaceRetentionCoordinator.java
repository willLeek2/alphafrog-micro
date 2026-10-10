package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** 当前 Agent 定时清理所有部署代际的到期 Python 工作区，不接管旧 Run 执行。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonWorkspaceRetentionCoordinator {
    static final int MIN_RETENTION_HOURS = 24;
    private static final int PAGE_SIZE = 100;

    private final AgentRunMapper runs;
    private final AgentLlmLocalConfigLoader configLoader;
    private final ObjectMapper json;
    private final RunOwnershipGateway ownership;
    private final PythonWorkspaceExpirationService expiration;

    /** 网关接口升级前不启动清理；缺失接口绝不通过旧取得接口查盘。 */
    @Autowired(required = false)
    private PythonWorkspaceRetentionPort sandbox;

    @Scheduled(fixedDelayString = "${agent.python-workspace.cleanup-scan-ms:60000}",
            initialDelayString = "${agent.python-workspace.cleanup-initial-delay-ms:60000}")
    public void scan() {
        if (sandbox == null || !configLoader.hotConfigIsAuthoritative()) {
            return;
        }
        try {
            scanOnce();
        } catch (RuntimeException failure) {
            log.warn("Python 工作区到期扫描暂不可用，下一轮重试", failure);
        }
    }

    void scanOnce() {
        String deploymentId = ownership.requireIdentity().deploymentId();
        scanInterruptedCleanup(deploymentId);
        scanNewCandidates(deploymentId);
    }

    private void scanInterruptedCleanup(String deploymentId) {
        String cursor = "";
        while (true) {
            List<String> runIds = runs.scanWorkspaceCleanupStarted(deploymentId, cursor, PAGE_SIZE);
            if (runIds == null || runIds.isEmpty()) {
                return;
            }
            for (String runId : runIds) {
                try {
                    AgentRun run = runs.findById(runId);
                    if (run != null && deploymentId.equals(run.getDeploymentId()) && eligibleRun(run)) {
                        LaneScopeGateway.call(run, () -> {
                            recoverMarked(run);
                            return null;
                        });
                    }
                } catch (RuntimeException failure) {
                    log.warn("Python 工作区清理恢复失败，下轮重试: runId={}", runId, failure);
                }
            }
            cursor = runIds.get(runIds.size() - 1);
            if (runIds.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private void recoverMarked(AgentRun run) {
        PythonWorkspaceRetentionPort.Lookup found = sandbox.findByRun(run.getId());
        if (found == null || found.state() == null) {
            return;
        }
        if (found.state() == PythonWorkspaceRetentionPort.LookupState.DELETED) {
            expiration.confirmDeleted(run.getId());
        } else if (found.state() == PythonWorkspaceRetentionPort.LookupState.FOUND
                && validWorkspace(run.getId(), found.workspace())) {
            processCandidate(run, found.workspace(), true);
        }
        // NOT_FOUND 不能推断磁盘已删；保持标记并等待可信的沙箱结果。
    }

    private void scanNewCandidates(String deploymentId) {
        List<AgentRun> lanes = runs.listWorkspaceRetentionLanes(deploymentId);
        if (lanes == null) {
            return;
        }
        for (AgentRun lane : lanes) {
            if (lane != null) {
                try {
                    LaneScopeGateway.call(lane, () -> {
                        scanLaneCandidates(deploymentId, lane.getLaneTag());
                        return null;
                    });
                } catch (RuntimeException failure) {
                    // One unavailable sandbox lane must not starve other lanes.
                    log.warn("Python 工作区泳道候选扫描失败，下轮重试: laneTag={}",
                            lane.getLaneTag(), failure);
                }
            }
        }
    }

    private void scanLaneCandidates(String deploymentId, String laneTag) {
        String cursor = "";
        while (true) {
            List<PythonWorkspaceRetentionPort.Workspace> candidates =
                    sandbox.listCandidates(cursor, PAGE_SIZE);
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            for (PythonWorkspaceRetentionPort.Workspace candidate : candidates) {
                if (candidate == null || candidate.workspaceId() == null) {
                    continue;
                }
                try {
                    AgentRun run = runs.findById(candidate.runId());
                    if (run != null && deploymentId.equals(run.getDeploymentId())
                            && sameLane(laneTag, run.getLaneTag()) && eligibleRun(run)) {
                        LaneScopeGateway.call(run, () -> {
                            processCandidate(run, candidate, false);
                            return null;
                        });
                    }
                } catch (RuntimeException failure) {
                    log.warn("Python 工作区到期清理失败，下轮重试: runId={}", candidate.runId(), failure);
                }
            }
            cursor = candidates.get(candidates.size() - 1).workspaceId();
            if (candidates.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private void processCandidate(AgentRun run, PythonWorkspaceRetentionPort.Workspace workspace,
                                  boolean alreadyMarked) {
        if (!validWorkspace(run.getId(), workspace) || !isDue(workspace)) {
            if (alreadyMarked && validWorkspace(run.getId(), workspace)) {
                runs.clearWorkspaceCleanupStarted(run.getId());
            }
            return;
        }
        if (!alreadyMarked && runs.markWorkspaceCleanupStarted(run.getId()) != 1) {
            return;
        }
        // Nacos may change during a long paginated scan. Re-read the hot value
        // after persisting the cleanup marker and immediately before deletion.
        if (!isDue(workspace)) {
            runs.clearWorkspaceCleanupStarted(run.getId());
            return;
        }
        PythonWorkspaceRetentionPort.DeleteResult outcome = sandbox.deleteIfUnchanged(workspace);
        if (outcome == PythonWorkspaceRetentionPort.DeleteResult.DELETED) {
            expiration.confirmDeleted(run.getId());
        } else if (outcome == PythonWorkspaceRetentionPort.DeleteResult.ACTIVITY_CHANGED) {
            runs.clearWorkspaceCleanupStarted(run.getId());
        }
        // 故障或不明结果保留标记；下次按 Run 查沙箱审计后续做。
    }

    private boolean isDue(PythonWorkspaceRetentionPort.Workspace workspace) {
        return !workspace.lastActiveAt().isAfter(
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(retentionHours()));
    }

    private boolean eligibleRun(AgentRun run) {
        if (runs.isDeletionStarted(run.getId()) || !terminal(run.getStatus())) {
            return false;
        }
        try {
            var ext = json.readTree(run.getExt());
            var enabled = ext == null ? null : ext.get("python_workspace_enabled");
            return enabled != null && enabled.isBoolean() && enabled.booleanValue();
        } catch (Exception invalidExt) {
            return false;
        }
    }

    private static boolean validWorkspace(String runId, PythonWorkspaceRetentionPort.Workspace workspace) {
        return workspace != null && runId != null && runId.equals(workspace.runId())
                && workspace.workspaceId() != null && !workspace.workspaceId().isBlank()
                && workspace.workspaceGeneration() != null && !workspace.workspaceGeneration().isBlank()
                && workspace.lastActiveAt() != null;
    }

    private static boolean sameLane(String left, String right) {
        return Objects.equals(LaneContext.toOfficialDubboTag(left),
                LaneContext.toOfficialDubboTag(right));
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    int retentionHours() {
        AgentLlmProperties config = configLoader.currentSnapshot().config();
        AgentLlmProperties.PythonWorkspace workspace = config == null || config.getAgent() == null
                ? null : config.getAgent().getPythonWorkspace();
        Integer configured = workspace == null ? null : workspace.getRetentionHours();
        if (configured == null) {
            return MIN_RETENTION_HOURS;
        }
        if (configured < MIN_RETENTION_HOURS) {
            log.warn("Python 工作区保留时长低于 24 小时，按 24 小时处理: configured={}", configured);
            return MIN_RETENTION_HOURS;
        }
        return configured;
    }
}
