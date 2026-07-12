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

        // Validate checkpointVersion: request version must match anchor's current version.
        // The caller captured this version at checkpoint time; using anchor's latest DB
        // version would let a stale (delayed) request borrow a newer version and silently
        // overwrite a more recent checkpoint. Reject on mismatch.
        int requestVersion = request.getExpectedCheckpointVersion();
        int anchorVersion = anchor.getCheckpointVersion();
        if (requestVersion != anchorVersion) {
            log.warn("Checkpoint rejected: checkpointVersion mismatch request={} anchor={} for run={}",
                    requestVersion, anchorVersion, runId);
            return false;
        }

        // Fail-closed: validate all required checkpoint fields before writing.
        // Launcher recovery depends on dataset snapshot; finalizer depends on estimate.
        // Missing or invalid fields must reject the checkpoint — never silently inherit old values.
        if (!validateCheckpointFields(request, runId)) {
            return false;
        }

        // Serialize completedTodos (null → empty array string, fail-closed on error)
        String todosJson = serializeTodos(request.getCompletedTodos());
        if (request.getCompletedTodos() != null && !request.getCompletedTodos().isEmpty()
                && todosJson == null) {
            log.error("Checkpoint rejected: failed to serialize completedTodos for run={}", runId);
            return false;
        }
        if (todosJson == null) {
            todosJson = "[]";
        }

        // Write all checkpoint fields atomically into anchor.
        // Use request's expectedCheckpointVersion (not anchor's) for CAS — prevents
        // a delayed request from borrowing the DB's newer version to overwrite.
        anchor.setTodoId(request.getTodoId());
        anchor.setSequence(request.getSequence());
        anchor.setCompletedTodosJson(todosJson);
        anchor.setDatasetSnapshotJson(request.getDatasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(request.getDatasetSnapshotDigest());
        anchor.setDatasetRefsJson(request.getDatasetRefsJson());
        anchor.setToolCallsUsed(request.getToolCallsUsed());
        anchor.setEstimateJson(request.getEstimateJson());
        anchor.setCheckpointVersion(requestVersion);

        // Atomic checkpoint merge: SQL merges only checkpoint whitelist fields
        // via jsonb || concat, preserving reservation/terminal/finalizer.
        // WHERE binds identity + taskId + expectedCheckpointVersion. If another writer
        // changed the anchor (different version), rows=0.
        boolean ok = anchorService.checkpointUpdate(runId, anchor, run.getStatus(),
                request.getTodoId(), request.getSequence(),
                todosJson,
                request.getDatasetSnapshotJson(), request.getDatasetSnapshotDigest(),
                request.getDatasetRefsJson(), request.getToolCallsUsed(),
                request.getEstimateJson());
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
        // All four identity fields are mandatory — missing or mismatch = fail-closed
        String reqOpId = request.getOperationId();
        if (reqOpId == null || reqOpId.isBlank()) {
            log.error("Checkpoint rejected: missing operationId for run={}", request.getRunId());
            return false;
        }
        if (!reqOpId.equals(anchor.getOperationId())) {
            log.error("Checkpoint rejected: operationId mismatch anchor={} request={}",
                    anchor.getOperationId(), reqOpId);
            return false;
        }
        String reqTcId = request.getToolCallId();
        if (reqTcId == null || reqTcId.isBlank()) {
            log.error("Checkpoint rejected: missing toolCallId for run={}", request.getRunId());
            return false;
        }
        if (!reqTcId.equals(anchor.getToolCallId())) {
            log.error("Checkpoint rejected: toolCallId mismatch anchor={} request={}",
                    anchor.getToolCallId(), reqTcId);
            return false;
        }
        if (request.getAttempt() <= 0) {
            log.error("Checkpoint rejected: missing attempt for run={}", request.getRunId());
            return false;
        }
        if (request.getAttempt() != anchor.getAttempt()) {
            log.error("Checkpoint rejected: attempt mismatch anchor={} request={}",
                    anchor.getAttempt(), request.getAttempt());
            return false;
        }
        String reqTaskId = request.getTaskId();
        if (reqTaskId == null || reqTaskId.isBlank()) {
            log.error("Checkpoint rejected: missing taskId for run={}", request.getRunId());
            return false;
        }
        if (!reqTaskId.equals(anchor.getTaskId())) {
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

    private boolean validateCheckpointFields(ToolJobCheckpointRequest request, String runId) {
        if (request.getTodoId() == null || request.getTodoId().isBlank()) {
            log.warn("Checkpoint rejected: missing todoId for run={}", runId);
            return false;
        }
        if (request.getCompletedTodos() == null) {
            log.warn("Checkpoint rejected: completedTodos is null for run={}", runId);
            return false;
        }
        if (request.getSequence() < 0) {
            log.warn("Checkpoint rejected: sequence={} < 0 for run={}", request.getSequence(), runId);
            return false;
        }
        if (request.getToolCallsUsed() < 0) {
            log.warn("Checkpoint rejected: toolCallsUsed={} < 0 for run={}", request.getToolCallsUsed(), runId);
            return false;
        }
        if (request.getDatasetSnapshotJson() == null || request.getDatasetSnapshotJson().isBlank()) {
            log.warn("Checkpoint rejected: missing datasetSnapshotJson for run={}", runId);
            return false;
        }
        if (!isValidJson(request.getDatasetSnapshotJson())) {
            log.warn("Checkpoint rejected: invalid datasetSnapshotJson for run={}", runId);
            return false;
        }
        if (request.getDatasetSnapshotDigest() == null || request.getDatasetSnapshotDigest().isBlank()) {
            log.warn("Checkpoint rejected: missing datasetSnapshotDigest for run={}", runId);
            return false;
        }
        if (request.getDatasetRefsJson() == null || request.getDatasetRefsJson().isBlank()) {
            log.warn("Checkpoint rejected: missing datasetRefsJson for run={}", runId);
            return false;
        }
        if (!isValidJson(request.getDatasetRefsJson())) {
            log.warn("Checkpoint rejected: invalid datasetRefsJson for run={}", runId);
            return false;
        }
        // estimateJson must be present and valid — never silently inherit old estimate
        if (request.getEstimateJson() == null || request.getEstimateJson().isBlank()) {
            log.warn("Checkpoint rejected: missing estimateJson for run={}", runId);
            return false;
        }
        if (!isValidJson(request.getEstimateJson())) {
            log.warn("Checkpoint rejected: invalid estimateJson for run={}", runId);
            return false;
        }
        return true;
    }

    private boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
