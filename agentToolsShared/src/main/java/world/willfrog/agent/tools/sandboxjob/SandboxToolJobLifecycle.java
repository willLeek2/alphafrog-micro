package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.CapacityAdmissionException;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobFaultInjector;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.exception.ToolJobTransferException;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱后台长任务的通用生命周期框架。
 *
 * <p>这里承载工具无关的生命周期骨架；每个工具经 {@link SandboxJobAdapters}
 * 只提供请求、运行器、结果、计量四个适配器。提取遵循一条红线：持久化状态字符串、
 * CAS 条件、Redis 索引键、cancelRequestId 拼接规则全部保持原样，只搬位置不改语义。</p>
 *
 * <p>当前已下沉的入口：{@link #prepareDispatch}（四段持久第一段）、{@link #attachAfterCreate}
 * （创建裁决、取消墓碑、身份核验与 ATTACHED 持久化）、{@link #pollFastPath} 与
 * {@link #pollDagBlocking}（两种等待策略的轮询）、{@link #finishTerminalByWaitPolicy} 与
 * {@link #completeSynchronously}（终态三段持久）、{@link #suspend}（挂起移交）、
 * {@link #dispatchWaitGroupMember}（等待成员派发）。</p>
 */
public final class SandboxToolJobLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolJobLifecycle.class);

    private SandboxToolJobLifecycle() {}

    /**
     * 生命周期框架的共享依赖束。所有字段取自工具 Bean 的既有注入；
     * {@code waitGroupStore} 与 {@code faultInjector} 允许为空（对应现有 null 检查语义）。
     */
    public record LifecycleDeps(
            PythonSandboxDispatchStore dispatchStore,
            DataAnalysisCapacityService capacityService,
            DataAnalysisTerminalRecorder terminalRecorder,
            WaitGroupStore waitGroupStore,
            ToolJobFaultInjector faultInjector,
            SandboxJobObservability observability,
            ObjectMapper objectMapper,
            long pollIntervalMs,
            long dagLeaseRenewAheadMs,
            long fastPathMs) {

        /** 容量账本、锚点存储与终态记录器缺一不可，对应既有的接线完整性检查。 */
        public boolean wiringAvailable() {
            return capacityService != null && dispatchStore != null && terminalRecorder != null;
        }
    }

    /** 终态副作用钩子：结果格式化前执行工具特有的终态处理（如 finance 记录提取）。 */
    public interface TerminalSideEffect {

        /**
         * @return 传给结果适配器的格式化上下文；没有副作用的工具返回 null
         */
        Object onTerminal(String runId, DataAnalysisOperationIdentity identity,
                          ToolJobAnchor anchor, SandboxTerminalResultView result) throws Exception;
    }

    /** prepareDispatch 的输入包。序列化字段由调用方准备好（框架不替工具做 JSON）。 */
    public record PrepareDispatchRequest(
            String runId,
            String toolName,
            String toolCallId,
            int attempt,
            int schemaVersion,
            String operationId,
            String requestFingerprint,
            String canonicalCreateSpecJson,
            String createRequestJson,
            String runDisposition,
            boolean autoResume,
            boolean durableSuspend,
            String reservationJson,
            String estimateJson,
            String datasetSnapshotJson,
            String datasetSnapshotDigest,
            long timeoutMillis,
            long pollIntervalMillis) {}

    /** 结果：persisted=false 表示 CAS 未取得锚点所有权，调用方必须释放容量并失败退出。 */
    public record PrepareDispatchResult(ToolJobAnchor anchor, boolean persisted) {}

    /**
     * 组装并 CAS 持久化 PREPARING 锚点，覆盖 createTask 前的 RPC 成败不确定窗口。
     *
     * <p>恢复 worker 的第二轮长工具带 resume token/lease version 时，走旧 handoff
     * 原子替换（persistPreparingFromResume）；否则走空锚点抢占（persistPreparing）。
     * 恢复交接一旦被这一版消费，AgentContext 里的 handoff 即刻清掉，防止同一 worker
     * 后续同步调用误走恢复路径。</p>
     *
     * @param toolExtras 工具在锚点上的自有字段写入（如修复计数、通道冻结快照），框架不关心内容
     */
    public static PrepareDispatchResult prepareDispatch(
            PythonSandboxDispatchStore dispatchStore,
            PrepareDispatchRequest req,
            java.util.function.Consumer<ToolJobAnchor> toolExtras) {
        // 在调用 createTask 之前先构造完整 PREPARING anchor，覆盖 RPC 成败不确定窗口。
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setSchemaVersion(req.schemaVersion());
        // 幂等操作身份与请求指纹用于启动恢复查询/重放。
        anchor.setOperationId(req.operationId());
        anchor.setRequestFingerprint(req.requestFingerprint());
        anchor.setCanonicalCreateSpecJson(req.canonicalCreateSpecJson());
        // createRequestJson 允许进程在 RPC 前后崩溃后重放同一 canonical 请求。
        anchor.setCreateRequestJson(req.createRequestJson());
        // PREPARING 表示容量已占用，但 Sandbox taskId 尚未确认附着。
        anchor.setAnchorState("PREPARING");
        anchor.setToolCallId(req.toolCallId());
        anchor.setToolName(req.toolName());
        anchor.setAttempt(req.attempt());
        // 保存当前 Todo 位置，后续 pipeline 完整 checkpoint 会补充已完成前缀。
        anchor.setTodoId(AgentContext.getTodoId());
        anchor.setSequence(AgentContext.getTodoSequence() == null ? 0 : AgentContext.getTodoSequence());
        anchor.setRunDisposition(req.runDisposition());
        anchor.setAutoResume(req.autoResume());
        // reservation/estimate/dataset snapshot 都先写 anchor，确保旧 worker 退出前真相完整。
        anchor.setReservationJson(req.reservationJson());
        anchor.setEstimateJson(req.estimateJson());
        anchor.setDatasetSnapshotJson(req.datasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(req.datasetSnapshotDigest());
        // timeoutAt 和 nextPollAt 都写进了数据库，重启后不重新计时。
        anchor.setTimeoutAt(Instant.now().plusMillis(req.timeoutMillis()));
        anchor.setNextPollAt(Instant.now().plusMillis(req.pollIntervalMillis()));
        if (!req.durableSuspend()) {
            // ownerId 在 JVM 生命周期内稳定；租约从第一次数据库抢占前开始计时。
            anchor.setBlockingOwnerId(DagBlockingWorkerLease.processOwnerId());
            anchor.setBlockingLeaseUntil(DagBlockingWorkerLease.renewedUntil(Instant.now()));
        }
        if (toolExtras != null) {
            toolExtras.accept(anchor);
        }

        // 必须先 CAS 占有 anchor，再调用有副作用的 createTask。恢复 worker 的第二次长工具
        // 不能走“空 anchor”路径：它必须用旧 LAUNCHING token/version 原子替换已消费 handoff。
        String resumeToken = AgentContext.getToolJobResumeToken();
        Long resumeLeaseVersion = AgentContext.getToolJobResumeLeaseVersion();
        boolean persisted;
        if (resumeToken != null && !resumeToken.isBlank()
                && resumeLeaseVersion != null && resumeLeaseVersion > 0) {
            persisted = dispatchStore.persistPreparingFromResume(
                    req.runId(), anchor, resumeToken, resumeLeaseVersion);
            if (persisted) {
                // 旧 handoff 已被这一版 PREPARING 消费；同一 worker 后续同步工具回到普通空-anchor CAS。
                AgentContext.clearToolJobResumeHandoff();
            }
        } else {
            persisted = dispatchStore.persistPreparing(req.runId(), anchor);
        }
        return new PrepareDispatchResult(anchor, persisted);
    }

    // ==================== attach 段：创建裁决、取消墓碑与 ATTACHED 持久化 ====================

    /** attachAfterCreate 的输入包。 */
    public record AttachAfterCreateRequest<REQ>(
            String runId,
            DataAnalysisOperationIdentity identity,
            CanonicalSandboxCreateSpec spec,
            REQ request,
            SandboxJobRunnerAdapter<REQ, ?, ?> runner,
            DataAnalysisReservation reservation,
            DataAnalysisEstimate estimate,
            ToolJobAnchor anchor,
            boolean durableSuspend,
            long toolStartMs) {}

    /** attach 段的两类出口：附着成功继续推进，或直接给模型一段失败文本。 */
    public sealed interface AttachOutcome {
        /** taskId 与 canonical 指纹同时确认，锚点已持久化 ATTACHED。 */
        record Attached(String taskId, DataAnalysisReservation reservation) implements AttachOutcome {}
        /** 失败文本（如 DAG 租约丢失），调用方原样返回给模型。 */
        record FailureText(String text) implements AttachOutcome {}
    }

    /**
     * createTask 之后的附着段：裁决创建结果、必要时写取消墓碑、核验 canonical 身份、
     * 把 reservation 转 TASK_ATTACHED 并持久化 ATTACHED 锚点。
     *
     * <p>判定语义与既有 Run 级 durable 路径逐字对应（宽松档）：found 且指纹一致即证实；
     * 未找到且无错误先写墓碑再接回终态；证据矛盾保留 PREPARING 抛异常交给恢复流程。</p>
     */
    public static <REQ> AttachOutcome attachAfterCreate(
            LifecycleDeps deps, AttachAfterCreateRequest<REQ> req) throws Exception {
        return attachTyped(deps, req, req.runner());
    }

    private static <REQ, RESP, L> AttachOutcome attachTyped(
            LifecycleDeps deps, AttachAfterCreateRequest<REQ> req,
            SandboxJobRunnerAdapter<REQ, RESP, L> runner) throws Exception {
        String runId = req.runId();
        DataAnalysisOperationIdentity identity = req.identity();
        CanonicalSandboxCreateSpec spec = req.spec();
        ToolJobAnchor anchor = req.anchor();
        // createResp 可能来自首次 RPC，也可能来自 operationId 灾后查询。
        RESP createResp;
        try {
            deps.observability().installDebugRpcAttachments();
            // Sandbox 必须按 operationId/requestFingerprint 幂等创建。
            createResp = runner.createTask(req.request());
        } catch (Exception createFailure) {
            try {
                // RPC 异常不代表服务端未创建；先按 operationId 查找，禁止立即重建第二任务。
                L lookup = runner.lookupRaw(identity.operationId());
                SandboxCreateVerdict verdict = runner.verdictFromLookupForRunPath(
                        lookup, spec.requestFingerprint());
                if (verdict instanceof SandboxCreateVerdict.Confirmed confirmed) {
                    // canonical operation 必须返回完全相同且非空的 fingerprint，才能附着已有任务。
                    createResp = runner.confirmedResponse(confirmed.taskId(), spec.requestFingerprint());
                } else if (verdict instanceof SandboxCreateVerdict.Absent) {
                    if (!req.durableSuspend()) {
                        if (!renewDagBlockingLease(deps, runId, anchor, true)) {
                            return new AttachOutcome.FailureText(dagBlockingLeaseLost(
                                    deps, anchor.getToolName(), null, req.toolStartMs(),
                                    "DAG worker lost its lease before canceling an uncertain create"));
                        }
                    }
                    // 查到暂时不存在也不能释放名额：迟到的 create RPC 仍可能到达。
                    // 先用同一 operation/fingerprint 写取消墓碑，取得稳定 taskId，
                    // 再像普通 Sandbox 任务一样接收终态并释放容量。
                    log.warn("DAG Sandbox create response lost and lookup absent; preserving capacity "
                                    + "until cancellation tombstone: runId={} operationId={}",
                            runId, identity.operationId());
                    createResp = tombstoneCreateUncertain(deps, runner,
                            identity.operationId(), spec.requestFingerprint(), "");
                } else {
                    // 查询也无法证明结果时保留 PREPARING，交给 startup recovery 决定，不能猜测释放。
                    throw new IllegalStateException(
                            "createTask outcome is ambiguous; PREPARING anchor retained", createFailure);
                }
            } catch (Exception lookupFailure) {
                // 回查块里任何失败（含墓碑与「结果不确定」标记异常）都归并到原始创建异常上抛出：
                // 原始异常带回了创建现场的真实原因，分支异常只作 suppressed 诊断。
                if (lookupFailure != createFailure) {
                    createFailure.addSuppressed(lookupFailure);
                }
                throw createFailure;
            }
        }
        // 无效响应也不能直接释放 PREPARING；按外部作业身份写墓碑并接回终态。
        if (createResp == null || runner.errorOf(createResp) != null && !runner.errorOf(createResp).isEmpty()
                || runner.taskIdOf(createResp) == null || runner.taskIdOf(createResp).isBlank()) {
            if (!req.durableSuspend()) {
                if (!renewDagBlockingLease(deps, runId, anchor, true)) {
                    return new AttachOutcome.FailureText(dagBlockingLeaseLost(
                            deps, anchor.getToolName(), null, req.toolStartMs(),
                            "DAG worker lost its lease before canceling an uncertain create"));
                }
            }
            log.warn("DAG Sandbox create response unverified; preserving capacity "
                            + "until cancellation tombstone: runId={} operationId={}",
                    runId, identity.operationId());
            createResp = tombstoneCreateUncertain(deps, runner, identity.operationId(),
                    spec.requestFingerprint(),
                    createResp == null ? "" : SandboxJobResponses.nvl(runner.taskIdOf(createResp)));
        }
        String taskId = runner.taskIdOf(createResp);
        /*
         * create 响应里的 canonical fingerprint 是 taskId 的身份凭据，不是可选诊断字段。
         * 直接响应若为空或漂移，必须先用 operationId 做一次权威回读；只有同 taskId、精确且
         * 非空的 fingerprint 才允许 PREPARING→ATTACHED。查询错误、未找到、taskId 漂移
         * 都保留 PREPARING，严禁转普通 PENDING 后让 reconciler 消费未验证任务。
         */
        if (runner.requestFingerprintOf(createResp).isBlank()
                || !spec.requestFingerprint().equals(runner.requestFingerprintOf(createResp))) {
            boolean canonicalIdentityConfirmed = false;
            try {
                L lookup = runner.lookupRaw(identity.operationId());
                canonicalIdentityConfirmed = runner.verdictFromLookupForRunPath(
                                lookup, spec.requestFingerprint()) instanceof SandboxCreateVerdict.Confirmed confirmed
                        && taskId.equals(confirmed.taskId());
            } catch (Exception lookupFailure) {
                log.error("Sandbox create identity lookup failed for run={}, operationId={}, taskId={}",
                        runId, identity.operationId(), taskId, lookupFailure);
            }
            if (!canonicalIdentityConfirmed) {
                log.error("Sandbox create identity unverified for run={}, operationId={}, taskId={}; "
                                + "PREPARING anchor retained for fail-closed recovery",
                        runId, identity.operationId(), taskId);
                throw new IllegalStateException(
                        "createTask identity is unverified; PREPARING anchor retained");
            }
        }

        // 这个检查点位于 Sandbox 已确认接受、taskId 尚未写回 Agent 数据库的精确窗口。
        hitFaultPoint(deps, runId, ToolJobFaultInjector.AFTER_SANDBOX_ACCEPTED);

        // taskId 与 canonical fingerprint 同时确认后，才把 reservation 转为 TASK_ATTACHED。
        DataAnalysisReservation attached = transitionReservation(
                req.reservation(), DataAnalysisReservationState.TASK_ATTACHED, taskId);
        /*
         * 先生成完整 ATTACHED 快照，再改变本地 anchor；序列化失败时 outer fallback
         * 仍以 PREPARING 做 operationId recovery，成功后则 capacity 异常也携带 task proof。
         */
        String attachedReservationJson;
        try {
            attachedReservationJson = deps.objectMapper().writeValueAsString(attached);
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("attached reservation could not be serialized", serializationFailure);
        }
        anchor.setTaskId(taskId);
        anchor.setAnchorState("ATTACHED");
        anchor.setReservationJson(attachedReservationJson);
        // 容量账本必须接受同一 reservation 的附着状态，冲突时停止推进。
        if (deps.capacityService().restoreReservation(attached) == DataAnalysisRestoreOutcome.CONFLICT) {
            throw new IllegalStateException("capacity reservation attachment conflicted for task=" + taskId);
        }
        // taskId、ATTACHED 和名额状态一起写进数据库进度记录。
        if (!deps.dispatchStore().persistAttached(runId, anchor)) {
            if (!req.durableSuspend()) {
                return new AttachOutcome.FailureText(dagBlockingLeaseLost(
                        deps, anchor.getToolName(), taskId, req.toolStartMs(),
                        "attach CAS rejected the live DAG owner"));
            }
            throw new IllegalStateException("failed to persist attached Sandbox task");
        }
        return new AttachOutcome.Attached(taskId, attached);
    }

    /**
     * 创建结果不确定时的取消墓碑：确定性 cancelRequestId 取消 + 回查三方核验，
     * 核验通过后返回一份只含任务号与指纹的确认响应。核验不过抛异常，PREPARING 保留。
     */
    public static <REQ, RESP, L> RESP tombstoneCreateUncertain(
            LifecycleDeps deps, SandboxJobRunnerAdapter<REQ, RESP, L> runner,
            String operationId, String fingerprint, String responseTaskId) {
        String cancelId = "tool-job-create-" + UUID.nameUUIDFromBytes(
                operationId.getBytes(StandardCharsets.UTF_8));
        SandboxCancelOutcomeView canceled = runner.cancelByOperation(
                operationId, fingerprint, cancelId, "CREATE_RESULT_UNCERTAIN");
        L lookup = runner.lookupRaw(operationId);
        if (lookup == null || !runner.verifyTombstoneIdentity(
                lookup, canceled.taskId(), fingerprint, responseTaskId)) {
            throw new IllegalStateException("Sandbox cancel tombstone identity could not be verified");
        }
        log.info("Sandbox create cancellation tombstone verified: operationId={} taskId={} outcome={}",
                operationId, canceled.taskId(), canceled.outcomeName());
        // 核验已过即回查任务号与取消确认一致，直接用取消侧的任务号合成确认响应。
        return runner.confirmedResponse(canceled.taskId(), fingerprint);
    }

    // ==================== 轮询段：fast-path 与 DAG 阻塞 ====================

    /** 轮询段的输入包。runner 只需要状态与结果两个方法，泛型在此擦除。 */
    public record PollRequest(
            String runId,
            DataAnalysisOperationIdentity identity,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            ToolJobAnchor anchor,
            String taskId,
            boolean durableSuspend,
            long toolStartMs,
            SandboxJobRunnerAdapter<?, ?, ?> runner,
            SandboxJobResultAdapter resultAdapter,
            SandboxJobMeteringAdapter meteringAdapter,
            TerminalSideEffect terminalSideEffect) {}

    /**
     * 两种等待策略共享的极短 fast-path：到期后 LINEAR 转后台挂起，DAG 改阻塞轮询。
     * 返回 null 表示 fast-path 耗尽且本方法已分流；非 null 是终态或失败文本。
     */
    public static String pollFastPath(LifecycleDeps deps, PollRequest req) throws Exception {
        String runId = req.runId();
        ToolJobAnchor anchor = req.anchor();
        String taskId = req.taskId();
        long fastDeadline = System.currentTimeMillis() + Math.max(1L, deps.fastPathMs());
        while (System.currentTimeMillis() < fastDeadline) {
            // 状态查询短而轻量，终态时再拉取结果体。
            SandboxJobStatusView statusView;
            try {
                if (!req.durableSuspend()
                        && !renewDagBlockingLease(deps, runId, anchor, false)) {
                    return dagBlockingLeaseLost(
                            deps, anchor.getToolName(), taskId, req.toolStartMs(),
                            "lease renewal was rejected before fast-path poll");
                }
                statusView = req.runner().statusOf(taskId);
            } catch (Exception pollFailure) {
                if (!req.durableSuspend()) {
                    return promoteDagBlockingFailure(
                            deps,
                            runId,
                            anchor,
                            "DAG_BLOCKING_POLL_FAILED",
                            "DAG Sandbox status polling failed",
                            req.toolStartMs(),
                            Map.of("task_id", taskId,
                                    "message", SandboxJobResponses.nvl(pollFailure.getMessage())));
                }
                throw pollFailure;
            }
            String status = statusView == null ? "" : SandboxJobResponses.nvl(statusView.statusName());
            if (isTerminal(status)) {
                return finishTerminalByWaitPolicy(deps, req, status);
            }
            if (!req.durableSuspend()
                    && statusView != null
                    && !SandboxJobResponses.nvl(statusView.error()).isBlank()) {
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_POLL_FAILED",
                        "DAG Sandbox status polling failed",
                        req.toolStartMs(),
                        Map.of("task_id", taskId,
                                "message", SandboxJobResponses.nvl(statusView.error())));
            }
            // 每次最多睡 100ms，并且不越过 fastDeadline。
            try {
                TimeUnit.MILLISECONDS.sleep(
                        Math.min(100L, Math.max(1L, fastDeadline - System.currentTimeMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (!req.durableSuspend()) {
                    return promoteDagBlockingFailure(
                            deps,
                            runId,
                            anchor,
                            "DAG_BLOCKING_INTERRUPTED",
                            "DAG Sandbox task polling was interrupted",
                            req.toolStartMs(),
                            Map.of("task_id", taskId));
                }
                throw interrupted;
            }
        }
        // 线性模式在快路径后转后台等待；DAG 留在当前线程，禁止生成 WAITING_TOOL_JOB。
        if (req.durableSuspend()) {
            return suspend(deps, runId, anchor, req.reservation(), taskId);
        }
        return pollDagBlocking(deps, req);
    }

    /**
     * DAG 节点在同一 worker 内阻塞轮询到 anchor 已冻结的 timeoutAt。
     * 本方法从不调用 transferToPending，也不抛 ExternalToolJobPendingException。
     */
    public static String pollDagBlocking(LifecycleDeps deps, PollRequest req) throws Exception {
        String runId = req.runId();
        ToolJobAnchor anchor = req.anchor();
        String taskId = req.taskId();
        Instant timeoutAt = anchor.getTimeoutAt();
        int pollIndex = 0;
        String lastRemoteStatus = "";
        while (true) {
            SandboxJobStatusView statusView;
            try {
                if (!renewDagBlockingLease(deps, runId, anchor, false)) {
                    return dagBlockingLeaseLost(
                            deps, anchor.getToolName(), taskId, req.toolStartMs(),
                            "lease renewal was rejected before blocking poll");
                }
                statusView = req.runner().statusOf(taskId);
            } catch (Exception pollFailure) {
                log.warn("DAG blocking poll failed: run={}, taskId={}, error={}",
                        runId, taskId, pollFailure.getMessage());
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_POLL_FAILED",
                        "DAG Sandbox status polling failed",
                        req.toolStartMs(),
                        Map.of("task_id", taskId, "message",
                                SandboxJobResponses.nvl(pollFailure.getMessage())));
            }
            String status = statusView == null ? "" : SandboxJobResponses.nvl(statusView.statusName());
            if (pollIndex == 0 || !status.equals(lastRemoteStatus) || pollIndex % 5 == 0) {
                deps.observability().emit("sandbox_poll", Map.of(
                        "status", "OK",
                        "pollIndex", pollIndex,
                        "remoteStatus", status,
                        "taskId", taskId,
                        "waitPolicy", "BLOCKING_POLL"));
            }
            lastRemoteStatus = status;
            pollIndex++;
            if (isTerminal(status)) {
                return finishTerminalByWaitPolicy(deps, req, status);
            }
            if (statusView != null && !SandboxJobResponses.nvl(statusView.error()).isBlank()) {
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_POLL_FAILED",
                        "DAG Sandbox status polling failed",
                        req.toolStartMs(),
                        Map.of("task_id", taskId,
                                "message", SandboxJobResponses.nvl(statusView.error())));
            }

            long remainingMillis = timeoutAt.toEpochMilli() - System.currentTimeMillis();
            if (remainingMillis <= 0L) {
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_TIMEOUT",
                        "DAG Sandbox task did not reach terminal state before the frozen timeout",
                        req.toolStartMs(),
                        Map.of("task_id", taskId, "timeout_at", timeoutAt.toString()));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(deps.pollIntervalMs(), remainingMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_INTERRUPTED",
                        "DAG Sandbox task polling was interrupted",
                        req.toolStartMs(),
                        Map.of("task_id", taskId));
            }
        }
    }

    /** 规范化终态判定：SUCCEEDED / FAILED / CANCELED 三种。 */
    public static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    // ==================== DAG 租约与失败移交 ====================

    /** 续 DAG 阻塞租约；剩余超过半程时不写库。续租失败会回滚内存里的新租约。 */
    public static boolean renewDagBlockingLease(
            LifecycleDeps deps, String runId, ToolJobAnchor anchor, boolean force) {
        Instant expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        Instant now = Instant.now();
        if (expectedLeaseUntil == null
                || anchor.getBlockingOwnerId() == null
                || anchor.getBlockingOwnerId().isBlank()) {
            return false;
        }
        if (!force && expectedLeaseUntil.isAfter(
                now.plusMillis(deps.dagLeaseRenewAheadMs()))) {
            return true;
        }
        Instant renewedUntil = DagBlockingWorkerLease.renewedUntil(now);
        anchor.setBlockingLeaseUntil(renewedUntil);
        boolean renewed;
        try {
            renewed = deps.dispatchStore().renewDagBlockingLease(
                    runId, anchor, expectedLeaseUntil);
        } catch (Exception renewalFailure) {
            log.warn("DAG blocking lease renewal failed: run={}, taskId={}, owner={}, error={}",
                    runId, anchor.getTaskId(), anchor.getBlockingOwnerId(),
                    renewalFailure.getMessage());
            renewed = false;
        }
        if (!renewed) {
            // 续租失败后，旧线程不能拿着新租约继续写数据库。
            anchor.setBlockingLeaseUntil(expectedLeaseUntil);
        }
        return renewed;
    }

    /** DAG worker 无法继续负责时的失败移交：把清理所有权转给恢复流程，再给模型失败文本。 */
    public static String promoteDagBlockingFailure(
            LifecycleDeps deps,
            String runId,
            ToolJobAnchor anchor,
            String errorCode,
            String message,
            long toolStartMs,
            Map<String, Object> details) {
        Instant expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setAutoResume(false);
        anchor.setFinalizerError(errorCode);
        anchor.setNextPollAt(Instant.now());
        boolean promoted;
        try {
            promoted = deps.dispatchStore().promoteDagBlockingWorkerLost(
                    runId, anchor, expectedLeaseUntil);
        } catch (Exception promotionFailure) {
            log.warn("DAG blocking cleanup ownership transfer failed: run={}, taskId={}, "
                            + "owner={}, error={}",
                    runId, anchor.getTaskId(), anchor.getBlockingOwnerId(),
                    promotionFailure.getMessage());
            promoted = false;
        }
        if (!promoted) {
            return dagBlockingLeaseLost(
                    deps, anchor.getToolName(), anchor.getTaskId(), toolStartMs,
                    "live-to-cleanup ownership CAS was rejected");
        }
        deps.observability().emitToolTotal(toolStartMs,
                "DAG_BLOCKING_TIMEOUT".equals(errorCode) ? "TIMEOUT" : "ERROR",
                errorCode);
        return SandboxJobResponses.fail(deps.objectMapper(), anchor.getToolName(),
                errorCode, message, details);
    }

    /** DAG 租约丢失的统一失败文本。 */
    public static String dagBlockingLeaseLost(
            LifecycleDeps deps, String toolName, String taskId, long toolStartMs, String reason) {
        deps.observability().emitToolTotal(toolStartMs, "ERROR", "DAG_BLOCKING_LEASE_LOST");
        return SandboxJobResponses.fail(deps.objectMapper(), toolName, "DAG_BLOCKING_LEASE_LOST",
                "DAG Sandbox worker lost its durable blocking lease",
                Map.of("task_id", SandboxJobResponses.nvl(taskId), "reason", SandboxJobResponses.nvl(reason)));
    }

    // ==================== 终态段：结果取回、三段持久与容量释放 ====================

    /**
     * 处理已经观察到的沙箱终态。线性模式如果同步走完终态流程失败，就把任务转成后台等待；
     * DAG 模式只能正常返回显式失败、保留数据库里的进度记录，交给恢复流程按「执行线程丢失」处理。
     */
    public static String finishTerminalByWaitPolicy(
            LifecycleDeps deps, PollRequest req, String status) throws Exception {
        String runId = req.runId();
        ToolJobAnchor anchor = req.anchor();
        String taskId = anchor.getTaskId();
        // 终态结果必须同时证明 taskId、status、payload 完整性与 retryable 字段存在。
        if (!req.durableSuspend()
                && !renewDagBlockingLease(deps, runId, anchor, true)) {
            return dagBlockingLeaseLost(
                    deps, anchor.getToolName(), taskId, req.toolStartMs(),
                    "lease renewal was rejected before terminal result fetch");
        }
        SandboxTerminalResultView result;
        try {
            result = req.runner().fetchResult(runId, taskId, status);
        } catch (Exception resultFailure) {
            if (!req.durableSuspend()) {
                return promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_RESULT_FETCH_FAILED",
                        "DAG Sandbox terminal result fetch failed",
                        req.toolStartMs(),
                        Map.of("task_id", taskId,
                                "status", status,
                                "message", SandboxJobResponses.nvl(resultFailure.getMessage())));
            }
            throw resultFailure;
        }
        if (!req.durableSuspend()
                && !renewDagBlockingLease(deps, runId, anchor, true)) {
            return dagBlockingLeaseLost(
                    deps, anchor.getToolName(), taskId, req.toolStartMs(),
                    "lease renewal was rejected after terminal result fetch");
        }
        if (result != null && result.retryable() != null) {
            try {
                String completed = completeSynchronously(deps, req, status, result);
                if (completed != null) {
                    deps.observability().emitToolTotal(req.toolStartMs(), "OK", "");
                    return completed;
                }
            } catch (Exception terminalFailure) {
                log.warn("Synchronous terminal finalization incomplete: run={}, taskId={}, "
                                + "waitPolicy={}, error={}",
                        runId, taskId,
                        req.durableSuspend() ? "DURABLE_SUSPEND" : "BLOCKING_POLL",
                        terminalFailure.getMessage());
            }
        }
        if (req.durableSuspend()) {
            return suspend(deps, runId, anchor, req.reservation(), taskId);
        }
        return promoteDagBlockingFailure(
                deps,
                runId,
                anchor,
                "DAG_BLOCKING_TERMINAL_INCOMPLETE",
                "DAG Sandbox task reached terminal state but durable finalization proof is incomplete",
                req.toolStartMs(),
                Map.of("task_id", taskId, "status", status));
    }

    /**
     * 同步终态的三段持久：先 ENVELOPE（终态快照进锚点）、再 RELEASE（容量释放）、
     * 最后 USAGE（终态信封进用量账）。任何一段失败都返回 null，由调用方按策略转后台或移交。
     */
    public static String completeSynchronously(
            LifecycleDeps deps, PollRequest req, String status,
            SandboxTerminalResultView result) throws Exception {
        String runId = req.runId();
        ToolJobAnchor anchor = req.anchor();
        DataAnalysisOperationIdentity identity = req.identity();
        DataAnalysisReservation attached = req.reservation();
        DataAnalysisReservation confirmed = transitionReservation(
                attached, DataAnalysisReservationState.TERMINAL_CONFIRMED, attached.taskId());
        if (deps.capacityService().restoreReservation(confirmed) == DataAnalysisRestoreOutcome.CONFLICT) {
            return null;
        }
        Object formatContext = req.terminalSideEffect() == null
                ? null
                : req.terminalSideEffect().onTerminal(runId, identity, anchor, result);
        // Build the public allowlist before persisting ENVELOPE. A projection/serialization
        // failure must never leave a durable success preview behind.
        String output = req.resultAdapter().formatTerminalResult(result, formatContext);
        String preview = output;
        String rawRef = SandboxJobResponses.blankToNull(result.rawRef());
        String errorCode = SandboxJobResponses.blankToNull(result.errorDetail());
        if (!"SUCCEEDED".equals(status) && errorCode == null) {
            errorCode = status;
        }
        Instant terminalAt = Instant.now();
        anchor.setAnchorState("TERMINAL");
        anchor.setTerminalStatus(status);
        anchor.setSandboxTerminalStatus(status);
        anchor.setTerminalResultPreview(preview);
        anchor.setTerminalRawRef(rawRef);
        anchor.setTerminalErrorCode(errorCode);
        anchor.setTerminalRetryable(result.retryable());
        anchor.setTerminalAt(terminalAt);
        anchor.setTerminalUsageJson(result.usageJson());
        anchor.setReservationJson(deps.objectMapper().writeValueAsString(confirmed));
        anchor.setFinalizerStep("ENVELOPE");
        if (!deps.dispatchStore().persistAttached(runId, anchor)) {
            return null;
        }
        DataAnalysisResourceUsage usage = req.meteringAdapter().toUsage(result, confirmed.resourceClass());
        DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                runId, identity.toolCallId(), identity.attempt(), identity.operationId(),
                attached.taskId(), status, "SUCCEEDED".equals(status), preview, rawRef,
                errorCode, "SUCCEEDED".equals(status) ? null : "sandbox " + status,
                result.retryable(), req.estimate(), confirmed, usage, terminalAt, false);

        DataAnalysisReleaseOutcome released = deps.capacityService().releaseReservation(
                new DataAnalysisReleaseRequest(confirmed,
                        new DataAnalysisReleaseProof.Terminal(envelope),
                        DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED));
        if (released != DataAnalysisReleaseOutcome.RELEASED
                && released != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
            return null;
        }
        DataAnalysisReservation releasedReservation = transitionReservation(
                confirmed, DataAnalysisReservationState.RELEASED, confirmed.taskId());
        anchor.setReservationJson(deps.objectMapper().writeValueAsString(releasedReservation));
        anchor.setFinalizerStep("RELEASE");
        if (!deps.dispatchStore().persistAttached(runId, anchor)) {
            return null;
        }
        DataAnalysisUpsertOutcome recorded = deps.terminalRecorder().upsert(envelope);
        if (recorded != DataAnalysisUpsertOutcome.INSERTED
                && recorded != DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME) {
            return null;
        }
        anchor.setUsagePersisted(true);
        anchor.setFinalizerStep("USAGE");
        if (!deps.dispatchStore().persistAttached(runId, anchor)) {
            return null;
        }
        return output;
    }

    // ==================== 挂起段：名额过户与 WAITING_TOOL_JOB 移交 ====================

    /**
     * 把长任务从当前线程移交给后台：先把占用的资源名额过户给后台任务（线程一旦释放，
     * 名额就没人管了），再把任务凭证和 Run 状态一起写进数据库，最后抛出挂起信号通知
     * 上层释放线程。
     */
    public static String suspend(
            LifecycleDeps deps,
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisReservation current,
            String taskId) throws Exception {
        // 先看数据库进度记录里有没有最新的资源占用信息，有就用它，防止拿调用栈里的旧数据覆盖新状态。
        if (anchor.getReservationJson() != null && !anchor.getReservationJson().isBlank()) {
            current = deps.objectMapper().readValue(anchor.getReservationJson(), DataAnalysisReservation.class);
        }
        // 只有 TASK_ATTACHED（任务已交给沙箱）需要转成 PENDING_TRANSFERRED（待过户给后台）；
        // 更靠后的状态保持原样，让本方法可以安全重入。
        DataAnalysisReservation pending = current.state() == DataAnalysisReservationState.TASK_ATTACHED
                ? transitionReservation(current, DataAnalysisReservationState.PENDING_TRANSFERRED, taskId)
                : current;
        // 把占用的资源名额从当前线程过户给后台任务。
        if (current.state() == DataAnalysisReservationState.TASK_ATTACHED
                && deps.capacityService().restoreReservation(pending) == DataAnalysisRestoreOutcome.CONFLICT) {
            // 这是失败：过户冲突时绝不能释放线程，否则任务和名额都无人负责。
            throw new ToolJobTransferException("capacity transfer to pending conflicted");
        }
        // 把后台状态和最新的名额记录写进内存凭证，并安排好第一次后台轮询时间（同时写数据库和 Redis 到期索引）。
        anchor.setAnchorState("PENDING");
        anchor.setReservationJson(deps.objectMapper().writeValueAsString(pending));
        anchor.setNextPollAt(Instant.now().plusMillis(deps.pollIntervalMs()));
        // 一条 SQL 同时写任务凭证、并把 Run 状态从执行中改为等待长工具，要么都成功、要么都不改。
        if (!deps.dispatchStore().transferToPending(runId, anchor)) {
            // 这是失败：落库没成功就不抛挂起信号，防止上层释放线程。
            throw new ToolJobTransferException("durable transfer to WAITING_TOOL_JOB failed");
        }
        // 这是信号：后台任务、名额和 Run 状态都已写进数据库，上层看到它就释放线程，
        // 随后把信号转换成正常的挂起结果。
        throw new ExternalToolJobPendingException(
                runId, anchor.getToolCallId(), anchor.getAttempt(),
                "Python Sandbox task continues in background: " + taskId);
    }

    // ==================== 名额与状态的共用小步 ====================

    /** 派发前的名额释放（创建尚未开始或证明未落库时的归还路径）。 */
    public static boolean releasePreDispatch(LifecycleDeps deps, DataAnalysisReservation reservation) {
        return releasePreDispatch(deps, reservation, DataAnalysisReleaseReason.CREATE_NOT_STARTED);
    }

    public static boolean releasePreDispatch(
            LifecycleDeps deps, DataAnalysisReservation reservation, DataAnalysisReleaseReason reason) {
        DataAnalysisReleaseOutcome outcome = deps.capacityService().releaseReservation(
                new DataAnalysisReleaseRequest(
                reservation,
                new DataAnalysisReleaseProof.PreDispatchAbort(reservation.identity()),
                reason));
        return outcome == DataAnalysisReleaseOutcome.RELEASED
                || outcome == DataAnalysisReleaseOutcome.ALREADY_RELEASED;
    }

    /** 只改状态的名额迁移：其余身份字段完全不变。 */
    public static DataAnalysisReservation transitionReservation(
            DataAnalysisReservation current,
            DataAnalysisReservationState state,
            String taskId) {
        return new DataAnalysisReservation(
                current.reservationId(), current.identity(), current.resourceClass(),
                current.capacityUnits(), state, taskId, current.acquiredAt());
    }

    // ==================== 等待成员派发：建后台任务并把派发证明交出去 ====================

    /** 等待成员派发的输入包。冻结事实（estimate/spec/指纹）由调用方的容量规划给出。 */
    public record WaitGroupDispatchRequest<REQ>(
            WaitGroupMemberExecutionContext.Snapshot member,
            DataAnalysisOperationIdentity identity,
            CanonicalSandboxCreateSpec spec,
            DataAnalysisEstimate estimate,
            REQ baseRequest,
            SandboxJobRequestAdapter<REQ> requestAdapter,
            SandboxJobRunnerAdapter<REQ, ?, ?> runner,
            String toolName,
            long toolStartMs) {}

    /**
     * 新调度器版本上的一次沙箱调用：把这次调用建成沙箱后台任务，然后立刻交出去。
     *
     * <p>与 Run 级路径的差别都在「事实写在哪」：Run 级路径把任务凭证、名额预留与运行状态写进
     * 一条 Run 级的长工具进度记录，一条 Run 同时只放得下一个；这里把同样的事实装进派发证明，
     * 由派发器写进这个成员自己的行。这次调用也从不等待结果——建好任务就抛出挂起信号，
     * 当前线程把节点执行名额交还。</p>
     *
     * <p>容量接线完整性检查与成员身份一致性比对在调用方完成（它们依赖框架拿不到的工具侧
     * 配置）；本方法从名额预留开始。返回字符串时表示这次调用当场就结束了，内容是给模型看的
     * 失败文本（{@code ok=false} 的 JSON）；建好任务、或建任务结果还不明确时用挂起信号返回，
     * 不走这里。</p>
     */
    public static <REQ> String dispatchWaitGroupMember(
            LifecycleDeps deps, WaitGroupDispatchRequest<REQ> req) {
        return dispatchWaitGroupMemberTyped(deps, req, req.runner());
    }

    private static <REQ, RESP, L> String dispatchWaitGroupMemberTyped(
            LifecycleDeps deps, WaitGroupDispatchRequest<REQ> req,
            SandboxJobRunnerAdapter<REQ, RESP, L> runner) {
        WaitGroupMemberExecutionContext.Snapshot member = req.member();
        DataAnalysisOperationIdentity identity = req.identity();
        DataAnalysisEstimate estimate = req.estimate();
        CanonicalSandboxCreateSpec spec = req.spec();
        DataAnalysisReservation reservation;
        try {
            reservation = deps.capacityService().reserve(identity, estimate);
        } catch (CapacityAdmissionException admission) {
            String code = admission.reason() == CapacityAdmissionException.Reason.TASK_TOO_LARGE
                    ? "DATA_ANALYSIS_TASK_TOO_LARGE"
                    : "DATA_ANALYSIS_SERVER_BUSY";
            return SandboxJobResponses.fail(deps.objectMapper(), req.toolName(), code, admission.getMessage(),
                    Map.of("retryable",
                            admission.reason() != CapacityAdmissionException.Reason.TASK_TOO_LARGE));
        }
        // 取消与外部 createTask 之间需要一份已经落库的请求指纹。若取消先赢，
        // 成员已不再待派发，不能继续创建一个无人负责的 Sandbox 任务。
        boolean preparingRecorded = false;
        try {
            WaitMemberDispatchProof preparingProof = proofForWaitGroup(deps, spec, estimate, reservation, null);
            preparingRecorded = deps.waitGroupStore() != null && deps.waitGroupStore().recordMemberPreparing(
                    member.groupId(), member.memberIdentity(), identity.operationId(),
                    preparingProof.toJson(deps.objectMapper()));
        } catch (RuntimeException persistenceFailure) {
            log.warn("Sandbox 创建前未能保存成员身份：{}", member.describe(), persistenceFailure);
        }
        if (!preparingRecorded) {
            // 预留只存在于本进程的容量账本中；尚未持久化创建证明，也没有发起外部请求。
            // 包括构造证明时的异常在内，都必须把这份本地预留归还。进程在此处退出时账本随进程消失。
            if (!releasePreDispatch(deps, reservation)) {
                log.error("成员创建证明未落库，且本地预留归还失败：{} reservation={}",
                        member.describe(), reservation.reservationId());
                return SandboxJobResponses.fail(deps.objectMapper(), req.toolName(), "WAIT_GROUP_PREPARING_RELEASE_FAILED",
                        "Sandbox request identity could not be saved and local capacity could not be released",
                        Map.of());
            }
            return SandboxJobResponses.fail(deps.objectMapper(), req.toolName(), "WAIT_GROUP_PREPARING_NOT_RECORDED",
                    "Sandbox request identity could not be saved before dispatch", Map.of());
        }
        // 名额凭证落库之后，把准入结果与 canonical identity 写进真正发送给沙箱的请求。
        REQ request = req.requestAdapter().enrichWithCapacity(
                req.baseRequest(), reservation, estimate, spec);
        long createStartMs = System.currentTimeMillis();
        SandboxCreateVerdict verdict;
        try {
            deps.observability().installDebugRpcAttachments();
            verdict = runner.verdictOf(runner.createTask(request), request);
        } catch (Exception createFailure) {
            verdict = runner.verdictOfFailure(createFailure, request);
        }
        if (verdict instanceof SandboxCreateVerdict.Absent absent) {
            // 即使当前回查不存在，原 create RPC 仍可能迟到；接收侧会先写稳定取消墓碑，
            // 再按 Sandbox 的真实取消终态结算容量与用量。
            log.info("成员创建当前未找到，交给结果接收侧写取消墓碑并收尾：{} 原因={}",
                    member.describe(), absent.detail());
            throw pendingForWaitGroup(deps, member, spec, estimate, reservation, null);
        }
        if (verdict instanceof SandboxCreateVerdict.Unknown unknown) {
            // 还没有结论：名额留着，成员照样按执行中记，由结果接收侧按外部作业身份回查。
            // 这条路上既不能释放名额，也不能把成员记成失败——两种做法都会让一个可能真实存在的
            // 后台任务无人负责。
            log.warn("成员建任务的结果还没被证实，先按执行中记，由结果接收侧回查：{} 原因={}",
                    member.describe(), unknown.detail());
            throw pendingForWaitGroup(deps, member, spec, estimate, reservation, null);
        }
        String taskId = ((SandboxCreateVerdict.Confirmed) verdict).taskId();
        // 任务编号与 canonical 指纹都确认之后，名额凭证从「准备中」改成绑在这个任务上。
        DataAnalysisReservation attached = transitionReservation(
                reservation, DataAnalysisReservationState.TASK_ATTACHED, taskId);
        // 容量账本必须接受同一份名额的附着状态，这道检查与旧路径一致。账本不接受时不能当成派发失败：
        // 沙箱任务已经真实存在，把这次调用记成失败就再也没人认领它。所以证明照写、成员照转后台，
        // 冲突只留在日志里，由结果接收侧按证明收尾时再遇到。
        if (deps.capacityService().restoreReservation(attached) == DataAnalysisRestoreOutcome.CONFLICT) {
            log.error("等待成员的名额附着被容量账本拒绝，仍按执行中记：{} taskId={}",
                    member.describe(), taskId);
        }
        deps.observability().emit("sandbox_create_task", Map.of(
                "durationMs", System.currentTimeMillis() - createStartMs,
                "status", "OK",
                "taskId", taskId,
                "operationId", identity.operationId()));
        throw pendingForWaitGroup(deps, member, spec, estimate, attached, taskId);
    }

    /**
     * 把这次后台作业整理成挂起信号：证明里带上 canonical 规格、预估值、名额凭证与任务编号。
     *
     * <p>这些事实是结果接收侧收尾的全部依据，所以在这里一次拼完整再带出去。任务编号为空表示
     * 建任务的结果还没有被证实。</p>
     */
    private static WaitGroupMemberPendingException pendingForWaitGroup(
            LifecycleDeps deps,
            WaitGroupMemberExecutionContext.Snapshot member,
            CanonicalSandboxCreateSpec spec,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            String taskId) {
        WaitMemberDispatchProof proof = proofForWaitGroup(deps, spec, estimate, reservation, taskId);
        return new WaitGroupMemberPendingException(proof,
                "wait group member dispatched: " + member.describe());
    }

    private static WaitMemberDispatchProof proofForWaitGroup(
            LifecycleDeps deps,
            CanonicalSandboxCreateSpec spec,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            String taskId) {
        return new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                reservation.identity().operationId(),
                taskId,
                spec.requestFingerprint(),
                toJsonOrThrow(deps.objectMapper(), "canonical 请求规格", spec),
                toJsonOrThrow(deps.objectMapper(), "预估值", estimate),
                toJsonOrThrow(deps.objectMapper(), "名额预留凭证", reservation),
                Instant.now().toString());
    }

    /** 序列化不成功就抛错：写不出派发证明时宁可让这次调用失败，也不能交出一份不完整的证明。 */
    private static String toJsonOrThrow(ObjectMapper objectMapper, String what, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(what + "写不成 JSON", e);
        }
    }

    /** 命中验收故障点；未注入故障器时静默跳过。 */
    private static void hitFaultPoint(LifecycleDeps deps, String runId, String checkpoint) {
        if (deps.faultInjector() != null) {
            deps.faultInjector().hit(runId, checkpoint);
        }
    }
}
