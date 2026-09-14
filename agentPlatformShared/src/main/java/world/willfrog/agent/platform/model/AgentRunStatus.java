package world.willfrog.agent.platform.model;

public enum AgentRunStatus {
    RECEIVED,
    PLANNING,
    EXECUTING,
    WAITING,
    SUMMARIZING,
    COMPLETED,
    PARTIAL, // 部分完成：DAG recovery judge 判定缺节点仍可交付
    FAILED,
    CANCELING,  // 正在取消中，用于通知执行线程停止
    CANCELED,
    EXPIRED,
    /**
     * 外部工具作业未结束，当前 pipeline 已保存恢复检查点并释放 Agent worker。
     * 工具完成后不会直接把本状态改成 EXECUTING：finalizer 先把结果与 READY token 写入 anchor 并将
     * Run CAS 到 RECEIVED，resume service 再声明 LAUNCHING、重新排队取得 worker 后继续执行。
     */
    WAITING_TOOL_JOB;
}
