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
    WAITING_TOOL_JOB; // 外部工具作业未结束，完成后允许自动恢复
}
