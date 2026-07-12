package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ToolJobCheckpointService(AgentRunMapper agentRunMapper,
                                     ToolJobAnchorService anchorService) {
        this.agentRunMapper = agentRunMapper;
        this.anchorService = anchorService;
    }

    @Override
    public boolean captureAndSave(ToolJobCheckpointRequest request) {
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

        // Validate immutable identity: operationId/toolCallId/attempt/taskId must not drift
        if (!validateIdentity(anchor, request)) {
            return false;
        }

        // Serialize completedTodos (fail-closed: null on error blocks checkpoint)
        String todosJson = serializeTodos(request.getCompletedTodos());
        if (request.getCompletedTodos() != null && !request.getCompletedTodos().isEmpty()
                && todosJson == null) {
            log.error("Checkpoint rejected: failed to serialize completedTodos for run={}", runId);
            return false;
        }

        // Validate required fields are non-null before writing
        if (request.getDatasetSnapshotJson() == null || request.getDatasetSnapshotJson().isBlank()) {
            log.warn("Checkpoint: missing datasetSnapshotJson for run={}, continuing", runId);
        }
        if (request.getDatasetSnapshotDigest() == null || request.getDatasetSnapshotDigest().isBlank()) {
            log.warn("Checkpoint: missing datasetSnapshotDigest for run={}, continuing", runId);
        }

        // Write all checkpoint fields atomically into anchor
        anchor.setTodoId(request.getTodoId());
        anchor.setSequence(request.getSequence());
        anchor.setCompletedTodosJson(todosJson);
        anchor.setDatasetSnapshotJson(request.getDatasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(request.getDatasetSnapshotDigest());
        anchor.setDatasetRefsJson(request.getDatasetRefsJson());
        anchor.setToolCallsUsed(request.getToolCallsUsed());
        if (request.getEstimateJson() != null && !request.getEstimateJson().isBlank()) {
            anchor.setEstimateJson(request.getEstimateJson());
        }

        // Atomic checkpoint merge: binds identity + checkpointVersion in WHERE.
        // The SQL bumps checkpointVersion atomically via jsonb || concat.
        // If another writer changed the anchor (different version), rows=0.
        boolean ok = anchorService.checkpointUpdate(runId, anchor, run.getStatus());
        if (!ok) {
            log.warn("Checkpoint CAS failed for run={} status={} op={} v={}",
                    runId, run.getStatus(), anchor.getOperationId(), anchor.getCheckpointVersion());
            return false;
        }
        log.info("Checkpoint persisted for run={} op={} todo={} todos={} tools={} v={}",
                runId, anchor.getOperationId(), request.getTodoId(),
                request.getCompletedTodos().size(), request.getToolCallsUsed(),
                anchor.getCheckpointVersion());
        return true;
    }

    private boolean validateIdentity(ToolJobAnchor anchor, ToolJobCheckpointRequest request) {
        String reqOpId = request.getOperationId();
        if (reqOpId != null && !reqOpId.isBlank()
                && !reqOpId.equals(anchor.getOperationId())) {
            log.error("Checkpoint rejected: operationId mismatch anchor={} request={}",
                    anchor.getOperationId(), reqOpId);
            return false;
        }
        String reqTcId = request.getToolCallId();
        if (reqTcId != null && !reqTcId.isBlank()
                && !reqTcId.equals(anchor.getToolCallId())) {
            log.error("Checkpoint rejected: toolCallId mismatch anchor={} request={}",
                    anchor.getToolCallId(), reqTcId);
            return false;
        }
        if (request.getAttempt() > 0 && request.getAttempt() != anchor.getAttempt()) {
            log.error("Checkpoint rejected: attempt mismatch anchor={} request={}",
                    anchor.getAttempt(), request.getAttempt());
            return false;
        }
        String reqTaskId = request.getTaskId();
        if (reqTaskId != null && !reqTaskId.isBlank()
                && !reqTaskId.equals(anchor.getTaskId())) {
            log.error("Checkpoint rejected: taskId mismatch anchor={} request={}",
                    anchor.getTaskId(), reqTaskId);
            return false;
        }
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
