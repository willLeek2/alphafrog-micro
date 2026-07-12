package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;
import java.util.Objects;

/** Durable owner for checkpoint-write failures that cannot be resolved inline. */
@Service
public class ToolJobCheckpointFailureRecoveryService {

    public static final String MARKER_PREFIX = "TOOL_JOB_CHECKPOINT_FAILURE_PENDING:";
    private static final Logger log = LoggerFactory.getLogger(ToolJobCheckpointFailureRecoveryService.class);

    private final ToolJobAnchorService anchorService;
    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public ToolJobCheckpointFailureRecoveryService(ToolJobAnchorService anchorService,
                                                   AgentRunMapper runMapper,
                                                   ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    public Outcome handleFailure(ToolJobCheckpointRequest request) {
        if (request == null) return Outcome.UNOWNED;
        if (hasEquivalentOrNewerCheckpoint(request)) return Outcome.HEALTHY_CHECKPOINT;
        try {
            if (anchorService.markCheckpointFailed(request, "durable_checkpoint_write_failed")) {
                return Outcome.FAILURE_OWNED;
            }
        } catch (Exception e) {
            log.warn("Checkpoint-failure narrow merge failed run={}: {}",
                    request.getRunId(), e.getMessage());
        }
        if (hasEquivalentOrNewerCheckpoint(request)) return Outcome.HEALTHY_CHECKPOINT;
        String marker = marker(request);
        if (writePendingMarker(request, marker) == 1) return Outcome.RETRY_OWNED;

        // The marker CAS deliberately fails after another tool job/version takes ownership.
        // Treat that as superseded so the stale writer cannot block the new owner's polling.
        if (!hasSameFrozenOwner(request)) return Outcome.SUPERSEDED;

        AgentRun current = runMapper.findById(request.getRunId());
        if (current != null && markerOwnsSameTuple(current.getLastError(), request)) {
            return Outcome.RETRY_OWNED;
        }

        // Same owner still exists but marker CAS lost for a transient reason. One bounded
        // retry of the narrow failure merge gives this path a durable disposition without
        // overwriting an unrelated last_error.
        try {
            if (anchorService.markCheckpointFailed(request, "durable_checkpoint_write_failed")) {
                return Outcome.FAILURE_OWNED;
            }
        } catch (Exception e) {
            log.warn("Checkpoint-failure bounded retry failed run={}: {}",
                    request.getRunId(), e.getMessage());
        }
        return hasSameFrozenOwner(request) ? Outcome.UNOWNED : Outcome.SUPERSEDED;
    }

    /** @return true when no pending marker exists or this call durably resolved it. */
    public boolean retryPending(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null || run.getLastError() == null
                || !run.getLastError().startsWith(MARKER_PREFIX)) {
            return true;
        }
        String marker = run.getLastError();
        ToolJobCheckpointRequest request;
        try {
            PendingFailure pending = objectMapper.readValue(
                    marker.substring(MARKER_PREFIX.length()), PendingFailure.class);
            request = pending.toRequest();
        } catch (Exception e) {
            log.error("Invalid checkpoint-failure retry marker run={}", runId, e);
            return false;
        }
        boolean resolved = hasEquivalentOrNewerCheckpoint(request);
        if (!resolved && !hasSameFrozenOwner(request)) {
            // The marker belongs to an old owner. Compare-clear it and let the current
            // tool job resume normal polling instead of retrying an impossible old tuple.
            resolved = true;
        } else if (!resolved) {
            resolved = anchorService.markCheckpointFailed(
                    request, "durable_checkpoint_write_failed");
        }
        return resolved && runMapper.clearToolJobCheckpointFailurePending(runId, marker) == 1;
    }

    private int writePendingMarker(ToolJobCheckpointRequest request, String marker) {
        return runMapper.markToolJobCheckpointFailurePending(
                request.getRunId(), request.getOperationId(), request.getToolCallId(),
                request.getAttempt(), request.getTaskId(), request.getExpectedCheckpointVersion(), marker);
    }

    private boolean hasSameFrozenOwner(ToolJobCheckpointRequest request) {
        try {
            AgentRun run = runMapper.findById(request.getRunId());
            if (run == null || run.getStatus() != AgentRunStatus.WAITING_TOOL_JOB
                    || blank(run.getToolJobAnchorJson())) {
                return false;
            }
            ToolJobAnchor anchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
            return anchor != null
                    && Objects.equals(anchor.getOperationId(), request.getOperationId())
                    && Objects.equals(anchor.getToolCallId(), request.getToolCallId())
                    && anchor.getAttempt() == request.getAttempt()
                    && Objects.equals(anchor.getTaskId(), request.getTaskId())
                    && anchor.getCheckpointVersion() == request.getExpectedCheckpointVersion();
        } catch (Exception e) {
            log.warn("Failed to classify checkpoint-failure owner run={}: {}",
                    request.getRunId(), e.getMessage());
            return true;
        }
    }

    private boolean markerOwnsSameTuple(String marker, ToolJobCheckpointRequest request) {
        if (marker == null || !marker.startsWith(MARKER_PREFIX)) return false;
        try {
            PendingFailure pending = objectMapper.readValue(
                    marker.substring(MARKER_PREFIX.length()), PendingFailure.class);
            return Objects.equals(pending.runId(), request.getRunId())
                    && Objects.equals(pending.operationId(), request.getOperationId())
                    && Objects.equals(pending.toolCallId(), request.getToolCallId())
                    && pending.attempt() == request.getAttempt()
                    && Objects.equals(pending.taskId(), request.getTaskId())
                    && pending.expectedVersion() == request.getExpectedCheckpointVersion();
        } catch (Exception e) {
            return false;
        }
    }

    boolean hasEquivalentOrNewerCheckpoint(ToolJobCheckpointRequest request) {
        try {
            ToolJobAnchor anchor = anchorService.loadAnchor(request.getRunId());
            if (anchor == null
                    || !Objects.equals(anchor.getOperationId(), request.getOperationId())
                    || !Objects.equals(anchor.getToolCallId(), request.getToolCallId())
                    || anchor.getAttempt() != request.getAttempt()
                    || !Objects.equals(anchor.getTaskId(), request.getTaskId())
                    || anchor.getCheckpointVersion() <= request.getExpectedCheckpointVersion()
                    || !Objects.equals(anchor.getTodoId(), request.getTodoId())
                    || anchor.getSequence() != request.getSequence()
                    || anchor.getToolCallsUsed() != request.getToolCallsUsed()
                    || blank(request.getDatasetSnapshotJson())
                    || blank(request.getDatasetSnapshotDigest())
                    || blank(request.getDatasetRefsJson())
                    || blank(request.getEstimateJson())) {
                return false;
            }
            return Objects.equals(anchor.getDatasetSnapshotDigest(), request.getDatasetSnapshotDigest())
                    && jsonEquals(anchor.getCompletedTodosJson(), objectMapper.valueToTree(request.getCompletedTodos()))
                    && jsonEquals(anchor.getDatasetSnapshotJson(), objectMapper.readTree(request.getDatasetSnapshotJson()))
                    && jsonEquals(anchor.getDatasetRefsJson(), objectMapper.readTree(request.getDatasetRefsJson()))
                    && jsonEquals(anchor.getEstimateJson(), objectMapper.readTree(request.getEstimateJson()));
        } catch (Exception e) {
            log.warn("Failed to verify newer checkpoint owner run={}: {}", request.getRunId(), e.getMessage());
            return false;
        }
    }

    private boolean jsonEquals(String actual, JsonNode expected) throws Exception {
        return !blank(actual) && Objects.equals(objectMapper.readTree(actual), expected);
    }

    private String marker(ToolJobCheckpointRequest request) {
        try {
            return MARKER_PREFIX + objectMapper.writeValueAsString(PendingFailure.from(request));
        } catch (Exception e) {
            throw new IllegalStateException("checkpoint_failure_marker_serialize_failed", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Outcome { HEALTHY_CHECKPOINT, FAILURE_OWNED, RETRY_OWNED, SUPERSEDED, UNOWNED }

    public record PendingFailure(String runId, String operationId, String toolCallId, int attempt,
                                 String taskId, int expectedVersion, String todoId, int sequence,
                                 List<world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord> completedTodos,
                                 String snapshotJson, String snapshotDigest, String refsJson,
                                 int toolCallsUsed, String estimateJson) {
        static PendingFailure from(ToolJobCheckpointRequest r) {
            return new PendingFailure(r.getRunId(), r.getOperationId(), r.getToolCallId(), r.getAttempt(),
                    r.getTaskId(), r.getExpectedCheckpointVersion(), r.getTodoId(), r.getSequence(),
                    r.getCompletedTodos(), r.getDatasetSnapshotJson(), r.getDatasetSnapshotDigest(),
                    r.getDatasetRefsJson(), r.getToolCallsUsed(), r.getEstimateJson());
        }

        ToolJobCheckpointRequest toRequest() {
            return ToolJobCheckpointRequest.builder(runId).operationId(operationId).toolCallId(toolCallId)
                    .attempt(attempt).taskId(taskId).expectedCheckpointVersion(expectedVersion)
                    .todoId(todoId).sequence(sequence).completedTodos(completedTodos)
                    .datasetSnapshotJson(snapshotJson).datasetSnapshotDigest(snapshotDigest)
                    .datasetRefsJson(refsJson).toolCallsUsed(toolCallsUsed).estimateJson(estimateJson).build();
        }
    }
}
