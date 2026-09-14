package world.willfrog.agentlangchain.execution;

/**
 * 工具在 Todo 语义重试和服务重启重放时的安全等级。
 * 未声明的工具必须按 {@link #UNSAFE} 处理，不能根据名字猜测。
 */
public enum ToolRetrySafety {
    READ_ONLY,
    IDEMPOTENT,
    UNSAFE
}
