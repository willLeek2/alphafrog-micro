package world.willfrog.agent.tools.sandboxjob;

import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 沙箱后台长任务的通用生命周期框架。
 *
 * <p>这里承载工具无关的生命周期骨架；每个工具经 {@link SandboxJobAdapters}
 * 只提供请求、运行器、结果、计量四个适配器。提取遵循一条红线：持久化状态字符串、
 * CAS 条件、Redis 索引键、cancelRequestId 拼接规则全部保持原样，只搬位置不改语义。</p>
 *
 * <p>当前已下沉的入口：{@link #prepareDispatch}（四段持久第一段）。其余入口
 * （attach/run、suspend、墓碑、终态、恢复）随后续批次下沉。</p>
 */
public final class SandboxToolJobLifecycle {

    private SandboxToolJobLifecycle() {}

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
            Consumer<ToolJobAnchor> toolExtras) {
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
}
