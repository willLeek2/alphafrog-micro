package world.willfrog.agent.platform.dag;

/**
 * incrementCancelNotfoundRetryCount 的返回类型。
 * newNotfoundRetryCount &gt; 0 表示 CAS 成功，不等于实际计数值。
 */
public record RetryAdvance(int newNotfoundRetryCount, long newNodeVersion) {
}
