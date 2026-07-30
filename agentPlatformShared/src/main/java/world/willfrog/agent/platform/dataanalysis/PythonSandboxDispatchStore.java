package world.willfrog.agent.platform.dataanalysis;

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
}
