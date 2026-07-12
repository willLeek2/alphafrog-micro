package world.willfrog.agentlangchain.tooljob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

/** PostgreSQL-authoritative dispatch handoff; Redis is a rebuildable derivative. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonSandboxDispatchStoreImpl implements PythonSandboxDispatchStore {

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;

    @Override
    public boolean persistPreparing(String runId, ToolJobAnchor anchor) {
        return "PREPARING".equals(anchor.getAnchorState())
                && anchorService.claimPreparing(runId, anchor, AgentRunStatus.EXECUTING);
    }

    @Override
    public boolean persistAttached(String runId, ToolJobAnchor anchor) {
        return ("ATTACHED".equals(anchor.getAnchorState())
                || "TERMINAL".equals(anchor.getAnchorState()))
                && anchorService.updateActive(
                        runId, anchor, AgentRunStatus.EXECUTING, anchor.getOperationId());
    }

    @Override
    public boolean transferToPending(String runId, ToolJobAnchor anchor) {
        if (!"PENDING".equals(anchor.getAnchorState())) {
            return false;
        }
        boolean durable = anchorService.updateActiveAndStatus(
                runId, anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, anchor.getOperationId());
        if (!durable) {
            return false;
        }
        try {
            redisCache.atomicWritePendingAndDue(runId, anchor);
        } catch (Exception cacheFailure) {
            log.warn("Pending Redis derivative write failed for run={}, durable anchor will rebuild it: {}",
                    runId, cacheFailure.getMessage());
        }
        return true;
    }

    @Override
    public boolean clearActive(String runId, String operationId) {
        return anchorService.clearActive(runId, AgentRunStatus.EXECUTING, operationId);
    }
}
