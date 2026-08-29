package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointFailureRecoveryService;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointRequest;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长工具挂起点的持久化与失败归属协调器。
 *
 * <p>它只处理“当前 worker 是否可以安全释放”的 durable checkpoint 合同，
 * 不负责首次规划、步骤执行或终态落库。</p>
 */
@Slf4j
final class LangchainToolJobCheckpointCoordinator {

    private final AgentRunMapper runMapper;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AgentRunDatasetRegistry> datasetRegistryProvider;
    private final ToolJobCheckpointWriter checkpointWriter;
    private final ToolJobCheckpointFailureRecoveryService failureRecoveryService;

    LangchainToolJobCheckpointCoordinator(AgentRunMapper runMapper,
                                          AgentRunEventService eventService,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<AgentRunDatasetRegistry> datasetRegistryProvider,
                                          ToolJobCheckpointWriter checkpointWriter,
                                          ToolJobCheckpointFailureRecoveryService failureRecoveryService) {
        this.runMapper = runMapper;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.datasetRegistryProvider = datasetRegistryProvider;
        this.checkpointWriter = checkpointWriter;
        this.failureRecoveryService = failureRecoveryService;
    }

    Attempt persist(String runId, LangchainLinearWorkflowResult result) {
        if (result == null || !result.isSuspended()) {
            return new Attempt(false, null);
        }
        ToolJobCheckpointRequest request = null;
        try {
            AgentRun latest = runMapper.findById(runId);
            if (latest == null || isBlank(latest.getToolJobAnchorJson())) {
                return new Attempt(false, null);
            }
            ToolJobAnchor anchor = ToolJobAnchor.fromJson(latest.getToolJobAnchorJson());
            request = minimalRequest(runId, result, anchor);
            if (checkpointWriter == null) {
                return new Attempt(false, request);
            }
            AgentRunDatasetRegistry registry = datasetRegistryProvider.getIfAvailable();
            if (registry == null) {
                return new Attempt(false, request);
            }
            var datasetSnapshot = registry.snapshot(runId);
            List<CompletedTodoRecord> completed = new ArrayList<>();
            if (result.getCompletedTodos() != null) {
                for (LangchainCompletedTodo todo : result.getCompletedTodos()) {
                    CompletedTodoRecord record = new CompletedTodoRecord();
                    record.setTodoId(todo.getTodoId());
                    record.setSequence(todo.getSequence());
                    record.setDescription(todo.getDescription());
                    record.setModelOutput(todo.getModelOutput());
                    record.setOutput(todo.getOutput());
                    record.setSummary(todo.getSummary());
                    completed.add(record);
                }
            }
            request = fullRequest(
                    runId,
                    result,
                    anchor,
                    completed,
                    objectMapper.writeValueAsString(datasetSnapshot),
                    datasetSnapshot.immutableDigest());
            return new Attempt(checkpointWriter.captureAndSave(request), request);
        } catch (Exception e) {
            log.error("Failed to persist tool-job checkpoint runId={}: {}", runId, e.getMessage());
            return new Attempt(false, request);
        }
    }

    ToolJobCheckpointFailureRecoveryService.Outcome recordFailure(
            String runId,
            String userId,
            LangchainLinearWorkflowResult result,
            ToolJobCheckpointRequest failedRequest) {
        if (failedRequest == null) {
            boolean failed = runMapper.updateTerminalSnapshot(runId, userId, AgentRunStatus.FAILED,
                    "{\"failure\":\"tool_job_checkpoint_anchor_missing\"}", true,
                    "tool_job_checkpoint_anchor_missing") == 1;
            emitFailure(runId, userId, result, failed, false);
            return failed ? ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED
                    : ToolJobCheckpointFailureRecoveryService.Outcome.UNOWNED;
        }
        ToolJobCheckpointFailureRecoveryService.Outcome outcome =
                ToolJobCheckpointFailureRecoveryService.Outcome.UNOWNED;
        if (failureRecoveryService != null) {
            try {
                outcome = failureRecoveryService.handleFailure(failedRequest);
            } catch (Exception e) {
                log.error("Failed to establish checkpoint-failure owner runId={}: {}", runId, e.getMessage());
            }
        }
        if (outcome == ToolJobCheckpointFailureRecoveryService.Outcome.HEALTHY_CHECKPOINT) {
            log.info("Checkpoint failure superseded by healthy durable owner run={} todo={}",
                    runId, result.getSuspendedTodoId());
            return outcome;
        }
        emitFailure(runId, userId, result,
                outcome == ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED,
                outcome == ToolJobCheckpointFailureRecoveryService.Outcome.RETRY_OWNED);
        return outcome;
    }

    private ToolJobCheckpointRequest minimalRequest(String runId,
                                                    LangchainLinearWorkflowResult result,
                                                    ToolJobAnchor anchor) {
        return commonRequest(runId, result, anchor)
                .completedTodos(List.of())
                .build();
    }

    private ToolJobCheckpointRequest fullRequest(String runId,
                                                 LangchainLinearWorkflowResult result,
                                                 ToolJobAnchor anchor,
                                                 List<CompletedTodoRecord> completed,
                                                 String datasetSnapshotJson,
                                                 String datasetSnapshotDigest) {
        ToolJobCheckpointRequest.Builder builder = commonRequest(runId, result, anchor)
                .completedTodos(completed)
                .datasetRefsJson(isBlank(anchor.getDatasetRefsJson()) ? "[]" : anchor.getDatasetRefsJson())
                .estimateJson(anchor.getEstimateJson());
        if (datasetSnapshotJson != null) {
            builder.datasetSnapshotJson(datasetSnapshotJson)
                    .datasetSnapshotDigest(datasetSnapshotDigest);
        }
        return builder.build();
    }

    private ToolJobCheckpointRequest.Builder commonRequest(String runId,
                                                           LangchainLinearWorkflowResult result,
                                                           ToolJobAnchor anchor) {
        return ToolJobCheckpointRequest.builder(runId)
                .operationId(anchor.getOperationId())
                .toolCallId(anchor.getToolCallId())
                .attempt(anchor.getAttempt())
                .taskId(anchor.getTaskId())
                .expectedCheckpointVersion(anchor.getCheckpointVersion())
                .todoId(result.getSuspendedTodoId())
                .sequence(result.getSuspendedTodoSequence() == null ? 0 : result.getSuspendedTodoSequence())
                .toolCallsUsed(result.getToolCallsUsed());
    }

    private void emitFailure(String runId,
                             String userId,
                             LangchainLinearWorkflowResult result,
                             boolean durable,
                             boolean retryable) {
        String dedupeKey = runId + ":" + nvl(result.getPendingToolCallId())
                + ":" + result.getPendingAttempt() + ":checkpoint_failed";
        eventService.appendOnce(runId, userId, "TOOL_JOB_CHECKPOINT_FAILED", dedupeKey, Map.of(
                "run_id", runId,
                "tool_call_id", nvl(result.getPendingToolCallId()),
                "attempt", result.getPendingAttempt(),
                "todo_id", nvl(result.getSuspendedTodoId()),
                "durable_failure_disposition", durable,
                "retryable", retryable));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    record Attempt(boolean persisted, ToolJobCheckpointRequest request) {
    }
}
