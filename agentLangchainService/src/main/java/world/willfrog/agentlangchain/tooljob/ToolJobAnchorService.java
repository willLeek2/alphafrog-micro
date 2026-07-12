package world.willfrog.agentlangchain.tooljob;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.Collections;
import java.util.List;

/**
 * Reads and writes {@link ToolJobAnchor} on {@code alphafrog_agent_run.tool_job_anchor_json}.
 * All mutation methods use CAS (Compare-And-Set) via expected-status preconditions.
 */
@Service
public class ToolJobAnchorService {

    private final AgentRunMapper agentRunMapper;

    public ToolJobAnchorService(AgentRunMapper agentRunMapper) {
        this.agentRunMapper = agentRunMapper;
    }

    /**
     * Reads the anchor for a run. Returns null when no active tool job exists.
     */
    public ToolJobAnchor loadAnchor(String runId) {
        AgentRun run = agentRunMapper.findById(runId);
        if (run == null || run.getToolJobAnchorJson() == null || run.getToolJobAnchorJson().isBlank()) {
            return null;
        }
        return ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
    }

    /**
     * CAS-update the anchor JSON only, requiring the run to be in {@code expectedStatus}.
     *
     * @return true if the update succeeded, false if the status had changed
     */
    public boolean updateAnchor(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
        int rows = agentRunMapper.updateToolJobAnchor(runId, anchor.toJson(), expectedStatus);
        return rows == 1;
    }

    /**
     * CAS-update both the anchor JSON and the run status atomically.
     *
     * @return true if the update succeeded
     */
    @Transactional
    public boolean updateAnchorAndStatus(String runId, ToolJobAnchor anchor,
                                          AgentRunStatus newStatus, AgentRunStatus expectedStatus) {
        int rows = agentRunMapper.updateToolJobAnchorAndStatus(runId, anchor.toJson(), newStatus, expectedStatus);
        return rows == 1;
    }

    /**
     * CAS-update only the run status.
     *
     * @return true if the status was changed by this call
     */
    public boolean casUpdateStatus(String runId, AgentRunStatus newStatus, AgentRunStatus expectedStatus) {
        int rows = agentRunMapper.casUpdateStatus(runId, newStatus, expectedStatus);
        return rows == 1;
    }

    /**
     * Lists all runs with non-empty tool job anchors in WAITING_TOOL_JOB status.
     */
    public List<AgentRun> listActive(int limit) {
        return agentRunMapper.listActiveToolJobAnchors(limit);
    }

    /**
     * Lists runs with status=RECEIVED and resumeState=READY/LAUNCHING,
     * i.e. runs that were CAS-ed back to RECEIVED but may not have been
     * picked up by the resume launcher (crash recovery).
     */
    public List<AgentRun> listResumeReady(int limit) {
        return agentRunMapper.listResumeReadyAnchors(limit);
    }
}
