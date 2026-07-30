package world.willfrog.agent.platform.dataanalysis;

import java.time.Instant;

/**
 * {@code PythonSandboxTools} 使用的 durable 分发边界。
 * 接口放在 shared 模块，避免 agentToolsShared 反向依赖 agentLangchainService。
 */
public interface PythonSandboxDispatchStore {

    /** 在 createTask 前抢占空 anchor；成功后 PREPARING reservation 才有持久化 owner。 */
    boolean persistPreparing(String runId, ToolJobAnchor anchor);

    /** 按 operationId 保存 Sandbox taskId 和 ATTACHED/TERMINAL 状态。 */
    boolean persistAttached(String runId, ToolJobAnchor anchor);

    /** 原子写 PENDING anchor 并把 Run 转为 WAITING_TOOL_JOB；true 才允许释放 worker。 */
    boolean transferToPending(String runId, ToolJobAnchor anchor);

    /** 仅在 operationId 仍属于调用方时清空失败的 active dispatch。 */
    boolean clearActive(String runId, String operationId);

    /**
     * 仅在同步终态的 envelope、容量释放和 usage 持久化凭证齐全时清 active anchor。
     * 返回 false 表示凭证不完整或 operation/status 所有权已变化。
     */
    boolean clearSynchronouslyCompleted(String runId, String operationId);

    /**
     * 以当前 owner、operation、NO_RESUME disposition 和未过期旧租约为 fencing 条件续租。
     * {@code anchor} 已包含新 lease；返回 false 后旧 worker 不得再写 anchor。
     */
    boolean renewDagBlockingLease(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil);

    /**
     * 当前 DAG worker 在正常返回失败前，把 live anchor 原子移交给 cleanup-only。
     * 成功后会尽力派生 Redis due；失败表示 worker 已失去 owner，不能无条件覆盖。
     */
    boolean promoteDagBlockingWorkerLost(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil);

    /**
     * DAG create 已被权威证明未生效时，先按 live PREPARING、owner 与精确未过期租约
     * 把 durable anchor 推进到 ABORTING。调用方只有赢得此 CAS 后才能释放容量。
     */
    boolean beginDagBlockingPreparingAbort(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil);

    /**
     * 容量幂等释放完成后，按 operation、owner、精确租约与 ABORTING disposition 清 anchor。
     * 此步不要求租约仍未过期，允许进程在 begin 后崩溃并由恢复者重入完成。
     */
    boolean completeDagBlockingPreparingAbort(
            String runId,
            ToolJobAnchor anchor,
            Instant expectedLeaseUntil);
}
