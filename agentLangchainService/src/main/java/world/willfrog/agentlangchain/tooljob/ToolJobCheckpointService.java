package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.util.List;

/**
 * Production implementation of {@link ToolJobCheckpointWriter}.
 * Atomically persists the full checkpoint payload to the durable anchor
 * before the pipeline suspends for a slow tool job.
 */
@Service
public class ToolJobCheckpointService implements ToolJobCheckpointWriter {

    private static final Logger log = LoggerFactory.getLogger(ToolJobCheckpointService.class);

    private final AgentRunMapper agentRunMapper;
    private final ToolJobAnchorService anchorService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired(required = false)
    private ToolJobCheckpointWriter customWriter;

    public ToolJobCheckpointService(AgentRunMapper agentRunMapper,
                                     ToolJobAnchorService anchorService) {
        this.agentRunMapper = agentRunMapper;
        this.anchorService = anchorService;
    }

    @Override
    public boolean captureAndSave(ToolJobCheckpointRequest request) {
        // Delegate to custom writer if wired (e.g. for testing)
        if (customWriter != null) {
            return customWriter.captureAndSave(request);
        }
        return doCaptureAndSave(request);
    }

    private boolean doCaptureAndSave(ToolJobCheckpointRequest request) {
        // Validate immutable identity
        String runId = request.getRunId();
        if (runId == null || runId.isBlank()) {
            log.warn("Checkpoint rejected: blank runId");
            return false;
        }

        AgentRun run = agentRunMapper.findById(runId);
        if (run == null) {
            log.warn("Checkpoint rejected: run not found id={}", runId);
            return false;
        }

        ToolJobAnchor anchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        if (anchor == null) {
            log.warn("Checkpoint rejected: no anchor for run={}", runId);
            return false;
        }

        // Write all checkpoint fields atomically into anchor
        anchor.setTodoId(request.getTodoId());
        anchor.setSequence(request.getSequence());
        anchor.setCompletedTodosJson(serializeTodos(request.getCompletedTodos()));
        anchor.setDatasetSnapshotJson(request.getDatasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(request.getDatasetSnapshotDigest());
        anchor.setDatasetRefsJson(request.getDatasetRefsJson());
        anchor.setToolCallsUsed(request.getToolCallsUsed());
        if (request.getEstimateJson() != null) {
            anchor.setEstimateJson(request.getEstimateJson());
        }

        // CAS with the current run status (read-then-write)
        boolean ok = anchorService.updateAnchor(runId, anchor, run.getStatus());
        if (!ok) {
            log.warn("Checkpoint CAS failed for run={} status={}", runId, run.getStatus());
            return false;
        }
        log.info("Checkpoint persisted for run={} todo={} todos={} tools={}",
                runId, request.getTodoId(),
                request.getCompletedTodos().size(), request.getToolCallsUsed());
        return true;
    }

    private String serializeTodos(List<CompletedTodoRecord> todos) {
        if (todos == null || todos.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(todos);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize completedTodos", e);
            return null;
        }
    }
}
