package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.util.List;
import java.util.Objects;

/**
 * 为无法在旧 worker 内解决的 checkpoint 写失败建立持久化 owner。
 *
 * <p>本类只在冻结的任务身份和版本上做窄合并，整份 anchor 不重试；
 * 若并发写者已经产生等价或更新 checkpoint，则把本写者判为 superseded。
 * 若暂时无法写失败处置，则把完整请求编码进 last_error marker，交给 reconciler 重试。</p>
 */
@Service
public class ToolJobCheckpointFailureRecoveryService {

    public static final String MARKER_PREFIX = "TOOL_JOB_CHECKPOINT_FAILURE_PENDING:";
    private static final Logger log = LoggerFactory.getLogger(ToolJobCheckpointFailureRecoveryService.class);

    private final ToolJobAnchorService anchorService;
    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private DeploymentIdentityProvider deploymentIdentityProvider;

    public ToolJobCheckpointFailureRecoveryService(ToolJobAnchorService anchorService,
                                                   AgentRunMapper runMapper,
                                                   ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    public Outcome handleFailure(ToolJobCheckpointRequest request) {
        // 没有冻结请求就无法确认所有权，返回 UNOWNED 交给 pipeline 走缺 anchor 失败路径。
        if (request == null) return Outcome.UNOWNED;
        // 首先检查是否已有并发写者把等价或更新的 checkpoint 写进数据库，避免把健康 Run 标失败。
        if (hasEquivalentOrNewerCheckpoint(request)) return Outcome.HEALTHY_CHECKPOINT;
        try {
            // 第一次尝试在相同身份/版本上窄写 CHECKPOINT_FAILED disposition。
            if (anchorService.markCheckpointFailed(request, "durable_checkpoint_write_failed")) {
                return Outcome.FAILURE_OWNED;
            }
        } catch (Exception e) {
            log.warn("Checkpoint-failure narrow merge failed run={}: {}",
                    request.getRunId(), e.getMessage());
        }
        // 窄写失败后再次读取，覆盖“刚好有并发健康写入”的竞态窗口。
        if (hasEquivalentOrNewerCheckpoint(request)) return Outcome.HEALTHY_CHECKPOINT;
        // 把冻结请求编码成可重启恢复的 marker。
        String marker = marker(request);
        // marker CAS 成功后，reconciler 成为明确的 retry owner。
        if (writePendingMarker(request, marker) == 1) return Outcome.RETRY_OWNED;

        // marker CAS 在任务身份或版本已经变化时会故意失败。
        // 此时旧写者必须退场，不能阻塞新 owner 的正常轮询。
        if (!hasSameFrozenOwner(request)) return Outcome.SUPERSEDED;

        // CAS 也可能因为同一 marker 已由另一个线程写入；读取 last_error 识别幂等成功。
        AgentRun current = findLocalRun(request.getRunId());
        if (current != null && markerOwnsSameTuple(current.getLastError(), request)) {
            return Outcome.RETRY_OWNED;
        }

        // 同一 owner 仍存在但 marker 因瞬时原因失败，只允许一次有界窄写重试。
        // 不能无界循环，也不能覆盖不相关 last_error。
        try {
            if (anchorService.markCheckpointFailed(request, "durable_checkpoint_write_failed")) {
                return Outcome.FAILURE_OWNED;
            }
        } catch (Exception e) {
            log.warn("Checkpoint-failure bounded retry failed run={}: {}",
                    request.getRunId(), e.getMessage());
        }
        // 最后一次重读区分“仍无人持有”与“已被新任务取代”。
        return hasSameFrozenOwner(request) ? Outcome.UNOWNED : Outcome.SUPERSEDED;
    }

    /** @return true when no pending marker exists or this call durably resolved it. */
    public boolean retryPending(String runId) {
        // 重启/周期扫描从数据库读取 marker；Redis 不参与所有权判断。
        AgentRun run = findLocalRun(runId);
        // 没有 marker 代表无需重试，幂等返回 true。
        if (run == null || run.getLastError() == null
                || !run.getLastError().startsWith(MARKER_PREFIX)) {
            return true;
        }
        // 保留原 marker 字符串，最终 compare-clear 必须精确匹配它。
        String marker = run.getLastError();
        ToolJobCheckpointRequest request;
        try {
            // marker 中含完整冻结元组和 checkpoint 上下文，可跨进程重建请求。
            PendingFailure pending = objectMapper.readValue(
                    marker.substring(MARKER_PREFIX.length()), PendingFailure.class);
            request = pending.toRequest();
        } catch (Exception e) {
            log.error("Invalid checkpoint-failure retry marker run={}", runId, e);
            return false;
        }
        // 如果健康的新 checkpoint 已经存在，marker 可以直接清理。
        boolean resolved = hasEquivalentOrNewerCheckpoint(request);
        if (!resolved && !hasSameFrozenOwner(request)) {
            // marker 属于旧 owner：把它视为已解决并 compare-clear，当前工具继续正常轮询。
            resolved = true;
        } else if (!resolved) {
            // 同一冻结 owner 仍有效，再次执行窄失败合并。
            resolved = anchorService.markCheckpointFailed(
                    request, "durable_checkpoint_write_failed");
        }
        // 只有失败处置已确认写入数据库、且 last_error 仍等于原 marker 时才清理，避免删掉新错误。
        return resolved && runMapper.clearToolJobCheckpointFailurePending(
                runId, marker, deploymentIdentity()) == 1;
    }

    private int writePendingMarker(ToolJobCheckpointRequest request, String marker) {
        return runMapper.markToolJobCheckpointFailurePending(
                request.getRunId(), request.getOperationId(), request.getToolCallId(),
                request.getAttempt(), request.getTaskId(), request.getExpectedCheckpointVersion(), marker,
                deploymentIdentity());
    }

    private boolean hasSameFrozenOwner(ToolJobCheckpointRequest request) {
        try {
            // 每次都从数据库读取当前 anchor，判断旧请求是否仍拥有同一元组。
            AgentRun run = findLocalRun(request.getRunId());
            if (run == null || run.getStatus() != AgentRunStatus.WAITING_TOOL_JOB
                    || blank(run.getToolJobAnchorJson())) {
                return false;
            }
            // operation/toolCall/attempt/task/version 任一变化都表示所有权已经转移。
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
            // 分类异常时保守返回 true，避免把仍有效的失败 owner 错判为 superseded 后丢失处置。
            return true;
        }
    }

    private DeploymentIdentity deploymentIdentity() {
        return deploymentIdentityProvider == null ? null : deploymentIdentityProvider.current();
    }

    private AgentRun findLocalRun(String runId) {
        DeploymentIdentity identity = deploymentIdentity();
        return identity == null
                ? runMapper.findById(runId)
                : runMapper.findByIdForDeployment(
                        runId, identity.deploymentId(), identity.generationId());
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
            // 先校验身份、版本推进和所有标量字段；任一不一致都不是等价 checkpoint。
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
            // JSON 使用结构等价比较，忽略字段顺序但不忽略实际内容差异。
            return Objects.equals(anchor.getDatasetSnapshotDigest(), request.getDatasetSnapshotDigest())
                    && jsonEquals(anchor.getCompletedTodosJson(), objectMapper.valueToTree(request.getCompletedTodos()))
                    && jsonEquals(anchor.getDatasetSnapshotJson(), objectMapper.readTree(request.getDatasetSnapshotJson()))
                    && jsonEquals(anchor.getDatasetRefsJson(), objectMapper.readTree(request.getDatasetRefsJson()))
                    && jsonEquals(anchor.getEstimateJson(), objectMapper.readTree(request.getEstimateJson()));
        } catch (Exception e) {
            // 无法证明等价时返回 false，绝不以解析失败为理由清理失败 owner。
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
