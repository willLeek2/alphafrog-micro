package world.willfrog.agent.platform.dag;

/**
 * Selector 六桶路由的桶标识。
 * 由 cancel_bucket CASE 表达式在 SQL 层计算，Java 层据此派发。
 */
public enum CancelBucket {
    PREPARING_STUCK,
    PREPARING,
    RECOVERY,
    RPC_EXHAUSTED,
    FIRST,
    RETRY
}
