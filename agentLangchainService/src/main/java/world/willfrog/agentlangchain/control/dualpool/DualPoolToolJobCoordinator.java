package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobFaultInjector;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkerProcessProof;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agentlangchain.execution.LangchainCompletedTodo;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointRequest;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;
import world.willfrog.agentlangchain.tooljob.ToolJobRedisCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LINEAR 双池节点与既有长工具状态机之间的持久交接层。
 *
 * <p>Sandbox 创建、幂等查询、终态确认、容量释放和唯一事件继续复用现有工具锚点；本类只负责把
 * 节点工作项推进为 WAITING/RESUMABLE，并在恢复分段提交时原子清理锚点。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DualPoolToolJobCoordinator {

    public static final String RESUME_STATE = "DUAL_POOL_READY";
    private final NodeWorkItemStore workItemStore;
    private final ToolJobAnchorService anchorService;
    private final ToolJobCheckpointWriter checkpointWriter;
    private final DualPoolDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ToolJobFaultInjector faultInjector;

    @Autowired(required = false)
    private DualPoolRunAdmissionRegistry admissionRegistry;

    @Autowired(required = false)
    private ToolJobRedisCache redisCache;

    public boolean supports(ToolJobAnchor anchor) {
        return anchor != null
                && anchor.getWorkItemPlanGeneration() != null
                && anchor.getWorkItemNodeId() != null
                && !anchor.getWorkItemNodeId().isBlank()
                && anchor.getWorkItemNodeAttempt() != null
                && anchor.getWorkItemSegmentSequence() != null
                && anchor.getWorkItemContextVersion() != null
                && anchor.getWorkItemRunControlVersion() != null
                && anchor.getWorkItemClaimEpoch() != null;
    }

    public boolean hasActiveWait(String runId) {
        return runId != null && supports(anchorService.loadAnchor(runId));
    }

    public NodeWorkItemMutationResult suspend(NodeWorkItem item,
                                              NodeWorkItemClaim claim,
                                              JsonNode payload,
                                              LangchainTodoNodeResult result,
                                              int totalToolCalls) {
        if (item == null || claim == null || result == null || !result.isSuspended()) {
            throw new IllegalArgumentException("dual_pool_tool_job_suspend_context_required");
        }
        ToolJobAnchor anchor = anchorService.loadAnchor(item.getRunId());
        if (!matches(item, claim.claimEpoch(), anchor)
                || !safeEquals(result.getPendingToolCallId(), anchor.getToolCallId())
                || result.getPendingAttempt() != anchor.getAttempt()) {
            throw new IllegalStateException("dual_pool_tool_job_anchor_identity_mismatch");
        }

        ToolJobCheckpointRequest checkpoint = checkpointRequest(item, payload, anchor, totalToolCalls);
        if (!checkpointWriter.captureAndSave(checkpoint)) {
            throw new IllegalStateException("dual_pool_tool_job_checkpoint_write_failed");
        }
        NodeWorkItemVersions versions = new NodeWorkItemVersions(
                item.getContextVersion(), item.getRunControlVersion(), claim.claimEpoch());
        return workItemStore.suspendForToolJob(item.identity(), versions, claim.claimedBy(),
                anchor.getOperationId(), anchor.getToolCallId(), anchor.getAttempt());
    }

    /** 工具 finalizer 完成资源释放和唯一事件后调用。 */
    public boolean promoteResumable(String runId, ToolJobAnchor anchor) {
        if (!supports(anchor) || runId == null || !runId.equals(anchorRunId(runId, anchor))) {
            return false;
        }
        NodeWorkItemIdentity identity = identity(runId, anchor);
        NodeWorkItem current = workItemStore.findByIdentity(identity).orElse(null);
        if (current != null && current.stateEnum() == NodeWorkItemState.RESUMABLE
                && RESUME_STATE.equals(anchor.getResumeState())) {
            if (!ensureRecoveryAdmission(runId)) {
                return false;
            }
            dispatcher.offerNode(identity);
            return true;
        }

        // 先复制再生成待提交快照。数据库条件更新失败时，调用方持有的内存锚点仍表示
        // 真实旧状态，下一轮 finalizer 不会被一个从未落库的 RESUME_READY 误导。
        ToolJobAnchor resumableAnchor = ToolJobAnchor.fromJson(anchor.toJson());
        resumableAnchor.setFinalizerStep("RESUME_READY");
        resumableAnchor.setResumeState(RESUME_STATE);
        resumableAnchor.setResumeToken(null);
        resumableAnchor.setResumeLauncherOwnerId(null);
        resumableAnchor.setResumeLauncherLeaseUntil(null);
        NodeWorkItemVersions versions = versions(resumableAnchor);
        NodeWorkItemMutationResult promoted = workItemStore.promoteToolJobResumable(
                identity, versions, resumableAnchor.getOperationId(),
                resumableAnchor.toJson(), resumePayload(resumableAnchor));
        if (!promoted.applied()) {
            log.warn("长工具终态未能推进原双池工作项: identity={} operationId={}",
                    identity.describe(), anchor.getOperationId());
            return false;
        }
        try {
            hitFaultPoint(runId, ToolJobFaultInjector.AFTER_RESUME_COMMITTED);
        } catch (ToolJobInjectedInterruption interruption) {
            // 结果与 RESUMABLE 已经同事务提交。线程级故障只移除了进程内准入，立即按
            // 持久结果重新取得许可并补投递；进程级故障不会返回到这里，由启动恢复接管。
            if (!recoverResumable(runId, resumableAnchor)) {
                log.warn("结果提交后的线程中断未能立即重新投递工作项: identity={} operationId={}",
                        identity.describe(), anchor.getOperationId());
            }
            throw interruption;
        }
        if (!ensureRecoveryAdmission(runId)) {
            return false;
        }
        dispatcher.offerNode(identity);
        return true;
    }

    /** 服务重启后恢复已经生成、但尚未提交的双池恢复分段。 */
    public boolean recoverResumable(String runId, ToolJobAnchor anchor) {
        return recoverResumable(runId, anchor, false);
    }

    /**
     * ApplicationReadyEvent 启动恢复使用的入口。
     *
     * <p>节点池的定时扫描可能在启动恢复回调之前先领取持久工作项。启动恢复只能接管上一
     * 进程留下的领取；本进程已经领取时保持现状，避免再次开放后形成两个领取代际。</p>
     */
    public boolean recoverResumableAtStartup(String runId, ToolJobAnchor anchor) {
        return recoverResumable(runId, anchor, true);
    }

    private boolean recoverResumable(String runId,
                                     ToolJobAnchor anchor,
                                     boolean preserveCurrentProcessClaim) {
        if (!supports(anchor) || !RESUME_STATE.equals(anchor.getResumeState())) {
            return false;
        }
        NodeWorkItemIdentity identity = identity(runId, anchor);
        NodeWorkItem current = workItemStore.findByIdentity(identity).orElse(null);
        if (current == null) {
            return false;
        }
        NodeWorkItemState state = current.stateEnum();
        if (state == NodeWorkItemState.RESUMABLE) {
            if (!ensureRecoveryAdmission(runId)) {
                return false;
            }
            dispatcher.offerNode(identity);
            return true;
        }
        if (state != NodeWorkItemState.CLAIMED && state != NodeWorkItemState.EXECUTING) {
            return false;
        }
        // Dispatcher 可能在 ApplicationReadyEvent 的启动恢复之前，已经由本进程领取了
        // 持久化的 RESUMABLE 工作项。此时再次开放会提升领取代际，让同一 JVM 内两个
        // worker 同时执行恢复分段；旧 worker 最终虽会被提交栅栏拒绝，但模型调用已经重复。
        // 只有领取者属于上一进程时才执行重排；当前进程的领取说明恢复已经在进行。
        if (preserveCurrentProcessClaim
                && NodeWorkerProcessProof.isCurrentProcess(current.getClaimedBy())) {
            return true;
        }
        NodeWorkItemMutationResult requeued = workItemStore.requeueInterruptedToolJob(
                identity,
                new NodeWorkItemVersions(
                        current.getContextVersion(), current.getRunControlVersion(), current.getClaimEpoch()),
                anchor.getOperationId());
        if (!requeued.applied()) {
            log.warn("重启恢复未能重新开放长工具工作项: identity={} operationId={}",
                    identity.describe(), anchor.getOperationId());
            return false;
        }
        if (!ensureRecoveryAdmission(runId)) {
            return false;
        }
        dispatcher.offerNode(identity);
        return true;
    }

    /**
     * 在线对账只补投递尚未领取的恢复工作项，不接管正在执行的领取者。
     *
     * <p>{@link #recoverResumable(String, ToolJobAnchor)} 用于进程启动或已经明确捕获到 worker
     * 中断的场景，可以把 CLAIMED/EXECUTING 重新开放；周期对账无法证明当前 worker 已经退出，
     * 若复用该接管语义，会每轮抬高领取代际并使仍在运行的模型调用全部变成过期提交。
     * 当前阶段的领取租约只参与条件更新，不作为在线接管信号；没有显式中断信号的线程消失
     * 仍由下一次进程启动恢复处理。</p>
     */
    public boolean dispatchOnlineResumable(String runId, ToolJobAnchor anchor) {
        if (!supports(anchor) || !RESUME_STATE.equals(anchor.getResumeState())) {
            return false;
        }
        NodeWorkItemIdentity identity = identity(runId, anchor);
        NodeWorkItem current = workItemStore.findByIdentity(identity).orElse(null);
        if (current == null) {
            return false;
        }
        NodeWorkItemState state = current.stateEnum();
        if (state == NodeWorkItemState.CLAIMED || state == NodeWorkItemState.EXECUTING) {
            return true;
        }
        if (state != NodeWorkItemState.RESUMABLE || !ensureRecoveryAdmission(runId)) {
            return false;
        }
        dispatcher.offerNode(identity);
        return true;
    }

    /**
     * 当前节点线程在一次性故障点退出后，从持久状态补上恢复唤醒。
     *
     * <p>调用位置已经离开模型和工具调用栈，旧 worker 不会再产生业务副作用。本方法仍然只
     * 接受与当前工作项身份和版本精确一致的锚点：结果已经提交时重新开放恢复分段；外部任务
     * 尚在 PREPARING/ATTACHED/PENDING 时只写 Redis 派生唤醒，实际查询、幂等重放和结果收口
     * 继续由长工具对账器从 PostgreSQL 真相源完成。</p>
     */
    public boolean recoverInterruptedWorker(NodeWorkItemIdentity identity,
                                             NodeWorkItemVersions interruptedVersions) {
        if (identity == null || interruptedVersions == null) {
            return false;
        }
        ToolJobAnchor anchor = anchorService.loadAnchor(identity.runId());
        NodeWorkItem current = workItemStore.findByIdentity(identity).orElse(null);
        if (!matchesInterruptedWorker(identity, interruptedVersions, current, anchor)) {
            return false;
        }
        if (RESUME_STATE.equals(anchor.getResumeState())) {
            return recoverResumable(identity.runId(), anchor);
        }
        if (!ensureRecoveryAdmission(identity.runId()) || redisCache == null) {
            return false;
        }
        try {
            return redisCache.atomicWritePendingAndDue(identity.runId(), anchor);
        } catch (RuntimeException cacheFailure) {
            log.warn("双池中断恢复唤醒写入失败，保留 PostgreSQL 锚点等待补扫: identity={}",
                    identity.describe(), cacheFailure);
            return false;
        }
    }

    public boolean isResumePayload(JsonNode payload) {
        return payload != null && payload.path("toolJobResume").isObject();
    }

    public LangchainTodoNodeResult resumeResult(JsonNode payload) {
        JsonNode resume = payload.path("toolJobResume");
        if (!resume.isObject()) {
            throw new IllegalArgumentException("dual_pool_tool_job_resume_payload_missing");
        }
        String terminalStatus = resume.path("terminalStatus").asText("");
        if (!resume.has("toolCallsUsed") || !resume.path("toolCallsUsed").canConvertToInt()) {
            log.warn("长工具恢复载荷缺少有效的工具调用总数，按 0 处理: operationId={}",
                    resume.path("operationId").asText(""));
        }
        int totalToolCalls = Math.max(0, resume.path("toolCallsUsed").asInt(0));
        int completedToolCalls = Math.max(0, payload.path("toolCallsUsed").asInt(0));
        int nodeToolCalls = Math.max(0, totalToolCalls - completedToolCalls);
        if ("SUCCEEDED".equals(terminalStatus)) {
            String output = resume.path("terminalResultPreview").asText("");
            if (output.isBlank()) {
                output = "external tool completed";
            }
            return LangchainTodoNodeResult.success(output, nodeToolCalls);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("terminal_status", terminalStatus);
        metadata.put("terminal_error_code", resume.path("terminalErrorCode").asText(""));
        metadata.put("terminal_retryable", resume.path("terminalRetryable").isBoolean()
                ? resume.path("terminalRetryable").asBoolean() : false);
        return LangchainTodoNodeResult.failure("external_tool_terminal_failure", metadata);
    }

    public int resumeTotalToolCalls(JsonNode payload) {
        return Math.max(0, payload.path("toolJobResume").path("toolCallsUsed").asInt(0));
    }

    public void afterModelCompleted(String runId) {
        hitFaultPoint(runId, ToolJobFaultInjector.AFTER_MODEL_COMPLETED);
    }

    public String resumeToolOutput(JsonNode payload) {
        JsonNode resume = payload == null ? null : payload.path("toolJobResume");
        if (resume == null || !resume.isObject()) {
            throw new IllegalArgumentException("dual_pool_tool_job_resume_payload_missing");
        }
        String output = resume.path("terminalResultPreview").asText("");
        return output.isBlank() ? "external tool completed" : output;
    }

    public boolean resumeTerminalSuccess(JsonNode payload) {
        return payload != null
                && "SUCCEEDED".equals(payload.path("toolJobResume").path("terminalStatus").asText(""));
    }

    public String resumeOperationId(JsonNode payload) {
        return payload == null ? null : payload.path("toolJobResume").path("operationId").asText(null);
    }

    public NodeWorkItemMutationResult commitResumedResult(NodeWorkItemIdentity identity,
                                                          NodeWorkItemVersions versions,
                                                          String operationId,
                                                          String payloadPatchJson,
                                                          String externalSideEffectRef) {
        return workItemStore.commitResumedToolJobResult(identity, versions, operationId,
                payloadPatchJson, externalSideEffectRef);
    }

    private ToolJobCheckpointRequest checkpointRequest(NodeWorkItem item,
                                                       JsonNode payload,
                                                       ToolJobAnchor anchor,
                                                       int totalToolCalls) {
        List<CompletedTodoRecord> completed = new ArrayList<>();
        if (payload != null && payload.path("completedContext").isArray()) {
            List<LangchainCompletedTodo> source = objectMapper.convertValue(
                    payload.path("completedContext"), new TypeReference<List<LangchainCompletedTodo>>() { });
            for (LangchainCompletedTodo todo : source) {
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
        String todoId = payload == null ? null : payload.path("todo").path("id").asText(null);
        int todoSequence = payload == null
                ? item.getSegmentSequence()
                : payload.path("todo").path("sequence").asInt(item.getSegmentSequence());
        return ToolJobCheckpointRequest.builder(item.getRunId())
                .operationId(anchor.getOperationId())
                .toolCallId(anchor.getToolCallId())
                .attempt(anchor.getAttempt())
                .taskId(anchor.getTaskId())
                .expectedCheckpointVersion(anchor.getCheckpointVersion())
                .todoId(todoId == null || todoId.isBlank() ? item.getNodeId() : todoId)
                .sequence(todoSequence)
                .completedTodos(completed)
                .datasetSnapshotJson(anchor.getDatasetSnapshotJson())
                .datasetSnapshotDigest(anchor.getDatasetSnapshotDigest())
                .datasetRefsJson("[]")
                .toolCallsUsed(Math.max(0, totalToolCalls))
                .estimateJson(anchor.getEstimateJson())
                .build();
    }

    private String resumePayload(ToolJobAnchor anchor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", anchor.getOperationId());
        payload.put("toolCallId", anchor.getToolCallId());
        payload.put("attempt", anchor.getAttempt());
        payload.put("taskId", anchor.getTaskId());
        payload.put("terminalStatus", anchor.getTerminalStatus());
        payload.put("terminalResultPreview", anchor.getTerminalResultPreview());
        payload.put("terminalRawRef", anchor.getTerminalRawRef());
        payload.put("terminalErrorCode", anchor.getTerminalErrorCode());
        payload.put("terminalExitReason", anchor.getTerminalExitReason());
        payload.put("terminalRetryable", anchor.getTerminalRetryable());
        payload.put("toolCallsUsed", anchor.getToolCallsUsed());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("dual_pool_tool_job_resume_payload_invalid", e);
        }
    }

    private void hitFaultPoint(String runId, String checkpoint) {
        if (faultInjector != null) {
            faultInjector.hit(runId, checkpoint);
        }
    }

    private boolean ensureRecoveryAdmission(String runId) {
        return admissionRegistry == null || admissionRegistry.restorePersistedToolJob(runId);
    }

    private boolean matches(NodeWorkItem item, int claimEpoch, ToolJobAnchor anchor) {
        return supports(anchor)
                && item.getPlanGeneration().equals(anchor.getWorkItemPlanGeneration())
                && item.getNodeId().equals(anchor.getWorkItemNodeId())
                && item.getNodeAttempt().equals(anchor.getWorkItemNodeAttempt())
                && item.getSegmentSequence().equals(anchor.getWorkItemSegmentSequence())
                && item.getContextVersion().equals(anchor.getWorkItemContextVersion())
                && item.getRunControlVersion().equals(anchor.getWorkItemRunControlVersion())
                && claimEpoch == anchor.getWorkItemClaimEpoch();
    }

    private boolean matchesInterruptedWorker(NodeWorkItemIdentity identity,
                                             NodeWorkItemVersions interruptedVersions,
                                             NodeWorkItem current,
                                             ToolJobAnchor anchor) {
        if (!supports(anchor) || current == null
                || (current.stateEnum() != NodeWorkItemState.CLAIMED
                && current.stateEnum() != NodeWorkItemState.EXECUTING)
                || !identity.equals(current.identity())
                || !identity.equals(identity(identity.runId(), anchor))
                || current.getContextVersion() != interruptedVersions.contextVersion()
                || current.getRunControlVersion() != interruptedVersions.runControlVersion()
                || current.getClaimEpoch() != interruptedVersions.claimEpoch()) {
            return false;
        }
        if (RESUME_STATE.equals(anchor.getResumeState())) {
            return interruptedVersions.claimEpoch() > anchor.getWorkItemClaimEpoch();
        }
        return interruptedVersions.claimEpoch() == anchor.getWorkItemClaimEpoch();
    }

    private NodeWorkItemIdentity identity(String runId, ToolJobAnchor anchor) {
        return new NodeWorkItemIdentity(runId, anchor.getWorkItemPlanGeneration(),
                anchor.getWorkItemNodeId(), anchor.getWorkItemNodeAttempt(),
                anchor.getWorkItemSegmentSequence());
    }

    private NodeWorkItemVersions versions(ToolJobAnchor anchor) {
        return new NodeWorkItemVersions(anchor.getWorkItemContextVersion(),
                anchor.getWorkItemRunControlVersion(), anchor.getWorkItemClaimEpoch());
    }

    private String anchorRunId(String runId, ToolJobAnchor anchor) {
        return anchor.getOperationId() != null && anchor.getOperationId().startsWith(runId + ":")
                ? runId : null;
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}
