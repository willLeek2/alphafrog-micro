package world.willfrog.agent.platform.dag;

/**
 * incrementCancelRpcRetryCount 的返回类型。
 * newRpcRetryCount &gt; 0 表示 CAS 成功，不等于实际计数值。
 */
public record RpcRetryAdvance(int newRpcRetryCount, long newNodeVersion) {
}
